package dev.owlibi.reference.reports;

import static org.junit.jupiter.api.Assertions.*;
import static dev.owlibi.reference.support.ApiClient.*;
import dev.owlibi.reference.support.*;
import dev.owlibi.reference.snapshots.*;
import dev.owlibi.reference.security.SessionState;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import tools.jackson.databind.JsonNode;

/** Real HTTP/PostgreSQL owned fixtures; workbook parsing is not Excel/Sheets acceptance. */
class ProfessorReportIT {
    private PostgresFixture db;
    private ServletWebServerApplicationContext app;
    private JdbcTemplate jdbc;
    private ApiClient leader,member;
    private UUID project;
    private Probe probe;
    private final MutableClock clock=MutableClock.INSTANCE;
    public static class Configuration { @Bean @Primary public Probe exportProbe() { return new Probe(); } }
    public static class Probe extends ReportWorkbook {
        volatile String mode="normal";
        volatile ProjectSnapshot captured;
        volatile CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
        void arm(String mode) { this.mode=mode;entered=new CountDownLatch(1);release=new CountDownLatch(1);finished=new CountDownLatch(1); }
        volatile boolean transaction;
        volatile long workspacePeak,generationNanos;
        @Override public Path generate(ProjectSnapshot s,ReportWorkspace workspace,ReportBudget budget) throws IOException {
            transaction=TransactionSynchronizationManager.isActualTransactionActive();captured=s;entered.countDown();
            try {
                if(mode.equals("pause")) try { if(!release.await(30,TimeUnit.SECONDS)) throw new IOException("Test barrier timeout"); } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IOException(e); }
                if(mode.equals("failure")) { Files.writeString(workspace.file("failure-",".tmp"),"synthetic partial output");throw new IOException("Synthetic generation failure"); }
                if(mode.equals("incomplete")) { var file=workspace.file("incomplete-",".xlsx");Files.writeString(file,"PK synthetic broken ZIP");return file; }
                if(mode.equals("timeout")) try { Thread.sleep(20100); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                if(mode.equals("epoch")) { var request=((ServletRequestAttributes)RequestContextHolder.currentRequestAttributes()).getRequest();var state=(SessionState)request.getAttribute(SessionState.ATTRIBUTE);state.login(state.account(),clockInstant()); }
                long start=System.nanoTime();try {
                    var generated=super.generate(s,workspace,budget);
                    if(mode.equals("missing-directory")) WorkbookFaults.removeDirectory(generated);
                    return generated;
                } finally { generationNanos=System.nanoTime()-start;workspacePeak=workspace.peakBytes(); }
            } finally { finished.countDown(); }
        }
        private Instant clockInstant() { return MutableClock.INSTANCE.instant(); }
    }
    @BeforeEach void start() throws Exception {
        clock.reset();clock.advance(Duration.between(clock.instant(),Instant.parse("2026-10-13T04:00:00Z")).getSeconds());db=new PostgresFixture();app=db.startApp(BoardSnapshotProbe.SOURCE+",dev.owlibi.reference.reports.ProfessorReportIT$Configuration");jdbc=app.getBean(JdbcTemplate.class);probe=app.getBean(Probe.class);
        leader=new ApiClient(app.getWebServer().getPort());leader.account("r4-leader@example.invalid");member=new ApiClient(app.getWebServer().getPort());member.account("r4-member@example.invalid");
        project=leader.project("Synthetic R4");assertEquals(200,member.accept(leader.invitation(project)).statusCode());
    }
    @AfterEach void stop() { if(probe!=null) probe.release.countDown();if(app!=null) app.close();if(db!=null) db.close(); }
    private String root() { return "/projects/"+project; }
    private void configure() throws Exception {
        var values=tree(leader.send("GET","/templates/blank",null)).get("suggestedSettings");var body=JSON.convertValue(values,LinkedHashMap.class);body.put("settingsVersion","0");
        assertEquals(200,leader.send("PATCH",root()+"/settings",json(body)).statusCode());
    }
    private HttpResponse<byte[]> report(ApiClient client) throws Exception { return request(client,root()+"/professor-report",null,Map.of()); }
    private HttpResponse<byte[]> request(ApiClient client,String path,String body,Map<String,String> headers) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(client.origin+"/api/v1"+path)).timeout(Duration.ofSeconds(40));headers.forEach(b::header);
        return client.client.send(b.method("GET",body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    private static void refusal(HttpResponse<byte[]> r,int status,String code) {
        assertEquals(status,r.statusCode(),new String(r.body(),java.nio.charset.StandardCharsets.UTF_8));var node=JSON.readTree(r.body());
        assertEquals(Set.of("code","message","fieldErrors"),node.propertyNames());assertEquals(code,node.get("code").asText());assertTrue(node.get("fieldErrors").isObject());
        assertEquals("no-store",r.headers().firstValue("cache-control").orElseThrow());
        assertTrue(r.headers().firstValue("content-disposition").isEmpty());assertTrue(r.headers().firstValue("x-owlibi-state-token").isEmpty());assertTrue(r.headers().firstValue("x-owlibi-snapshot-at").isEmpty());
        assertFalse(r.headers().firstValue("content-type").orElse("").contains("spreadsheet"));assertFalse(new String(r.body()).contains("jdbc:"));
    }
    private JsonNode task(String points,ApiClient... assignees) throws Exception {
        var r=leader.send("POST",root()+"/tasks",json(Map.of("title","Synthetic task "+UUID.randomUUID(),"effortPoints",points,"assigneeIds",Arrays.stream(assignees).map(a -> a.id).toList())));assertEquals(201,r.statusCode());return tree(r);
    }
    private JsonNode current(JsonNode task) throws Exception { for(var t:tree(leader.send("GET",root()+"/board",null)).get("tasks")) if(t.get("id").equals(task.get("id"))) return t;throw new AssertionError(); }
    private void move(JsonNode task,String action) throws Exception {
        var r=leader.send("POST",root()+"/tasks/"+task.get("id").asText()+"/transitions",json(Map.of("action",action,"version",current(task).get("version").asLong(),"reason","Synthetic R4 reason")));assertEquals(200,r.statusCode());
    }
    private void accepted(JsonNode task,ApiClient... assignees) throws Exception { for(var a:assignees) assertEquals(200,a.send("POST",root()+"/tasks/"+task.get("id").asText()+"/acceptance",json(Map.of("agreementVersion",current(task).get("agreementVersion").asLong()))).statusCode()); }
    private void done(JsonNode task,ApiClient... assignees) throws Exception { accepted(task,assignees);move(task,"START");move(task,"SUBMIT_REVIEW");move(task,"APPROVE"); }
    private static XSSFWorkbook book(HttpResponse<byte[]> r) throws Exception { assertEquals(200,r.statusCode());double ratio=org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio();try { org.apache.poi.openxml4j.util.ZipSecureFile.setMinInflateRatio(0);return new XSSFWorkbook(new ByteArrayInputStream(r.body())); } finally { org.apache.poi.openxml4j.util.ZipSecureFile.setMinInflateRatio(ratio); } }
    private static String value(Sheet sheet,int row,int col) { var cell=sheet.getRow(row).getCell(col);return cell==null?"":cell.toString(); }
    private static Row key(Sheet sheet,String key) { for(var r:sheet) if(r.getCell(0)!=null && r.getCell(0).toString().equals(key)) return r;throw new AssertionError(key); }
    private void event(String payload) { jdbc.update("INSERT INTO activity_events (id,project_id,actor_id,event_type,occurred_at_utc,change_summary) VALUES (?,?,?,'TASK_EDITED',?::timestamptz,?::jsonb)",UUID.randomUUID(),project,leader.id,clock.instant().toString(),payload); }
    private long owned() throws Exception { var parent=ReportWorkspace.defaultParent();if(!Files.exists(parent)) return 0;try(var files=Files.list(parent)) { return files.filter(p -> p.getFileName().toString().startsWith("export-")).count(); } }
    private void awaitCleanup() throws Exception {
        var field=ReportController.class.getDeclaredField("SLOT");field.setAccessible(true);var slot=(java.util.concurrent.Semaphore)field.get(null);
        for(int n=0;n<250 && (owned()!=0 || slot.availablePermits()!=1);n++) Thread.sleep(20);
        assertEquals(0,owned());assertEquals(1,slot.availablePermits());
    }
    private void evidence(String name,byte[] bytes) throws Exception { var dir=Path.of("target/r4-captures");Files.createDirectories(dir);Files.write(dir.resolve(name+".xlsx"),bytes); }

    @Test void r4_19MissingZipDirectoryRefusesSuccessHeadersCleansAndReleasesSlot() throws Exception {
        configure();probe.mode="missing-directory";var response=report(leader);refusal(response,503,"REPORT_UNAVAILABLE");awaitCleanup();
        var captured=Map.of("scope","Synthetic generated archive with injected missing central directory","status",response.statusCode(),
            "code",JSON.readTree(response.body()).get("code").asText(),"xlsxHeadersAbsent",response.headers().firstValue("content-disposition").isEmpty()
                && response.headers().firstValue("x-owlibi-state-token").isEmpty() && response.headers().firstValue("x-owlibi-snapshot-at").isEmpty()
                && !response.headers().firstValue("content-type").orElse("").contains("spreadsheet"),
            "ownedDirectoriesAfterCleanup",owned());
        probe.mode="normal";assertEquals(200,report(leader).statusCode());awaitCleanup();
        var dir=Path.of("target/backend-fix-captures");Files.createDirectories(dir);Files.writeString(dir.resolve("incomplete-report.json"),json(captured));
    }

    @Test void r4_01AuthenticationRoleProjectIsolationAndAuthorizationBeforeParsing() throws Exception {
        var anon=new ApiClient(app.getWebServer().getPort());refusal(report(anon),401,"UNAUTHENTICATED");
        refusal(request(member,root()+"/professor-report?bad=1","{}",Map.of()),403,"FORBIDDEN");
        var other=member.project("Other private project");refusal(request(leader,"/projects/"+other+"/professor-report",null,Map.of()),404,"NOT_FOUND");
        assertNull(probe.captured);awaitCleanup();
    }
    @Test void r4_02StrictQueryBodyOriginSafeErrorsAndNoCsrfForGet() throws Exception {
        configure();for(String q:List.of("?title=bad","?date=2026-10-13","?limit=1","?=")) refusal(request(leader,root()+"/professor-report"+q,null,Map.of()),400,"MALFORMED_REQUEST");
        for(String body:List.of("{}"," ","[1]")) refusal(request(leader,root()+"/professor-report",body,Map.of()),400,"MALFORMED_REQUEST");
        var foreign=request(leader,root()+"/professor-report",null,Map.of("Origin","https://foreign.example.invalid"));assertEquals(200,foreign.statusCode());assertTrue(foreign.headers().firstValue("access-control-allow-origin").isEmpty());assertEquals(200,report(leader).statusCode());
        var head=leader.client.send(HttpRequest.newBuilder(URI.create(leader.origin+"/api/v1"+root()+"/professor-report")).method("HEAD",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());assertEquals(405,head.statusCode());
    }
    @Test void r4_03UnconfiguredRefusalAndConfiguredEmptyCompleteHeadersMetadataFormats() throws Exception {
        refusal(report(leader),409,"SETTINGS_REQUIRED");configure();int events=jdbc.queryForObject("SELECT count(*) FROM activity_events",Integer.class);
        var r=report(leader);assertEquals(ReportController.MIME,r.headers().firstValue("content-type").orElseThrow());assertEquals(r.body().length,Long.parseLong(r.headers().firstValue("content-length").orElseThrow()));
        assertEquals("attachment; filename=\"owlibi-"+project+"-20261013T040000Z.xlsx\"",r.headers().firstValue("content-disposition").orElseThrow());assertEquals("nosniff",r.headers().firstValue("x-content-type-options").orElseThrow());assertEquals(leader.epoch,r.headers().firstValue("x-session-epoch").orElseThrow());
        try(var w=book(r)) {
            assertEquals(List.of("Project summary","Member contributions","Task details","Workload report","Activity history","Project rules"),java.util.stream.IntStream.range(0,6).mapToObj(w::getSheetName).toList());assertEquals(6,w.getNumberOfSheets());
            assertEquals("Owlibi",w.getProperties().getCoreProperties().getCreator());assertEquals("Owlibi",w.getProperties().getCoreProperties().getLastModifiedByUser());
            for(var s:w) { assertEquals(r.headers().firstValue("x-owlibi-snapshot-at").orElseThrow(),value(s,2,1));assertTrue(value(s,3,1).startsWith("Asia/Manila / "));assertEquals(r.headers().firstValue("x-owlibi-state-token").orElseThrow(),value(s,4,1));assertEquals("1",value(s,5,1));assertEquals(7,s.getPaneInformation().getHorizontalSplitPosition());assertFalse(s.isDisplayGridlines());
                assertEquals(FillPatternType.SOLID_FOREGROUND,s.getRow(0).getCell(0).getCellStyle().getFillPattern());
                assertTrue(s.getColumnWidth(0)>0);assertTrue(((org.apache.poi.xssf.usermodel.XSSFSheet)s).getCTWorksheet().isSetAutoFilter()); }
            assertEquals(CellType.BLANK,w.getSheet("Member contributions").getRow(7).getCell(2).getCellType());assertEquals("No completed tasks yet",value(w.getSheet("Member contributions"),7,3));assertEquals("0.0",key(w.getSheetAt(0),"Completed total").getCell(1).getCellStyle().getDataFormatString());
            var workload=w.getSheet("Workload report");
            assertEquals(0.0,workload.getRow(7).getCell(1).getNumericCellValue());
            assertTrue(workload.isColumnHidden(7));
            assertTrue(w.getSheet("Task details").isColumnHidden(14));
            assertTrue(w.getSheet("Activity history").isColumnHidden(8));
            var rules=w.getSheet("Project rules");
            assertEquals(20.0,key(rules,"Overload threshold").getCell(1).getNumericCellValue());
            assertEquals("0.00",key(rules,"Overload threshold").getCell(1).getCellStyle().getDataFormatString());
        }assertEquals(events,jdbc.queryForObject("SELECT count(*) FROM activity_events",Integer.class));assertFalse(probe.transaction);awaitCleanup();evidence("empty",r.body());
    }
    @Test void r4_04AllStatusesSharedReopenRecompleteIndependentRoundingAndFullHistory() throws Exception {
        configure();var shared=task("1.00",leader,member);done(shared,leader,member);var todo=task("10.00",leader);var open=task("10.00",member);accepted(open,member);move(open,"START");var review=task("10.00",member);accepted(review,member);move(review,"START");move(review,"SUBMIT_REVIEW");var cancelled=task("50.00",leader);move(cancelled,"CANCEL");
        var r=report(leader);try(var w=book(r)) { assertEquals(1.0,key(w.getSheetAt(0),"Completed total").getCell(1).getNumericCellValue());assertEquals(5,w.getSheet("Task details").getLastRowNum()-6);assertEquals(Set.of("TODO","IN_PROGRESS","REVIEW","DONE","CANCELLED"),java.util.stream.IntStream.rangeClosed(7,11).mapToObj(i -> value(w.getSheet("Task details"),i,15)).collect(java.util.stream.Collectors.toSet()));assertEquals(probe.captured.history().count(),w.getSheet("Activity history").getLastRowNum()-6);assertEquals(50.0,w.getSheet("Member contributions").getRow(7).getCell(2).getNumericCellValue());assertEquals(CanonicalState.hash(CanonicalState.json(probe.captured.history().events())),probe.captured.history().hash());assertEquals("0.0",w.getSheet("Member contributions").getRow(7).getCell(2).getCellStyle().getDataFormatString()); }
        try(var w=book(r)) {
            var workload=w.getSheet("Workload report");
            assertEquals(10.0,workload.getRow(7).getCell(1).getNumericCellValue());
            assertEquals(10.0,workload.getRow(8).getCell(1).getNumericCellValue());
            assertEquals(2.0,key(workload,"Tasks").getCell(1).getNumericCellValue());
            assertEquals(1.0,key(workload,"Tasks").getCell(2).getNumericCellValue());
            var reviewRow=key(workload,current(review).get("title").asText());
            assertEquals("In review",reviewRow.getCell(1).getStringCellValue());
            assertEquals(0.0,reviewRow.getCell(4).getNumericCellValue());
            assertEquals("No due date",reviewRow.getCell(6).getStringCellValue());
            assertEquals("Action",value(w.getSheet("Activity history"),6,1));
            assertTrue(java.util.stream.StreamSupport.stream(w.getSheet("Activity history").spliterator(),false)
                .anyMatch(row -> row.getCell(5)!=null && row.getCell(5).toString().equals("APPROVE")));
        }
        move(shared,"REOPEN");try(var w=book(report(leader))) { assertEquals(0.0,key(w.getSheetAt(0),"Completed total").getCell(1).getNumericCellValue()); }
        move(shared,"SUBMIT_REVIEW");move(shared,"APPROVE");try(var w=book(report(leader))) { assertEquals(1.0,key(w.getSheetAt(0),"Completed total").getCell(1).getNumericCellValue()); }
        evidence("all-status-shared",r.body());
        var third=new ApiClient(app.getWebServer().getPort());third.account("r4-third@example.invalid");assertEquals(200,third.accept(leader.invitation(project)).statusCode());move(shared,"REOPEN");
        assertEquals(200,leader.send("PATCH",root()+"/tasks/"+shared.get("id").asText(),json(Map.of("version",current(shared).get("version").asLong(),"assigneeIds",List.of(leader.id,member.id,third.id),"reason","Synthetic shared rework"))).statusCode());accepted(shared,leader,member,third);move(shared,"SUBMIT_REVIEW");move(shared,"APPROVE");
        var thirds=report(leader);try(var w=book(thirds)) { double sum=0;for(int row=7;row<=9;row++) { var cell=w.getSheet("Member contributions").getRow(row).getCell(2);assertEquals(33.3,cell.getNumericCellValue());sum+=cell.getNumericCellValue(); }assertEquals(99.9,sum,0.000001); }
        evidence("thirds",thirds.body());
    }
    @Test void r4_05DuplicateNamesPrivacyStableIdsSafeInvitationAndNoExecutableContent() throws Exception {
        configure();jdbc.update("UPDATE users SET display_name='Duplicate name' WHERE id IN (?,?)",leader.id,member.id);leader.invitation(project);task("99999999.99",leader,member);
        for(String path:List.of("/licenses/R4-ATTRIBUTION.txt","/.env","/r4-captures/maximum.xlsx")) { var privateResponse=leader.client.send(HttpRequest.newBuilder(URI.create(leader.origin+path)).GET().build(),HttpResponse.BodyHandlers.discarding());assertTrue(Set.of(403,404).contains(privateResponse.statusCode())); }
        var r=report(leader);try(var w=book(r)) { assertNotEquals(value(w.getSheet("Member contributions"),7,4),value(w.getSheet("Member contributions"),8,4));assertEquals("Duplicate name",value(w.getSheet("Member contributions"),7,0));assertTrue(value(w.getSheet("Task details"),7,4).contains(leader.id.toString()));assertEquals("0.00",w.getSheet("Task details").getRow(7).getCell(3).getCellStyle().getDataFormatString());for(var s:w) for(var row:s) for(var cell:row) { assertNotEquals(CellType.FORMULA,cell.getCellType());assertNull(cell.getHyperlink());if(cell.getCellType()==CellType.STRING) { for(String forbidden:List.of("@example.invalid","password_hash","tokenHash","invitationToken","johnallenalvarez")) assertFalse(cell.getStringCellValue().contains(forbidden)); } }assertTrue(w.getExternalLinksTable().isEmpty());assertTrue(w.getAllPictures().isEmpty()); }
    }
    @Test void r4_06LiteralFormulaUrlsXmlUnicodeControlsAndLongHistoryReconstruction() throws Exception {
        configure();String input="=SUM(A1) + - @ https://example.invalid <>&\"\n\r\t\u0001\u000b\u001f😀_x0041_";
        var t=task("1.00",leader);assertEquals(200,leader.send("PATCH",root()+"/tasks/"+t.get("id").asText(),json(Map.of("version",0,"description",input))).statusCode());
        String longText=(input+"𐐀").repeat(1100);event(json(Map.of("before",Map.of("description",longText),"after",Map.of("description",longText))));
        var r=report(leader);try(var w=book(r)) { assertEquals(input,w.getSheet("Task details").getRow(7).getCell(6).getStringCellValue());var s=w.getSheet("Activity history");var ids=new LinkedHashMap<String,StringBuilder>();for(int row=7;row<=s.getLastRowNum();row++) { var line=s.getRow(row);String id=line.getCell(8).getStringCellValue(),part=line.getCell(9).getStringCellValue(),payload=line.getCell(4).getStringCellValue();assertTrue(payload.length()<=30000);if(part.endsWith("/1")) continue;ids.computeIfAbsent(id,k -> new StringBuilder()).append(payload); }assertEquals(1,ids.size());assertEquals(longText,JSON.readTree(ids.values().iterator().next().toString()).get("before").get("description").asText()); }
        evidence("literal-continuations",r.body());
        for(String prefix:List.of("+CMD","-SUM(A1)","@link","https://example.invalid")) { var current=current(t);assertEquals(200,leader.send("PATCH",root()+"/tasks/"+t.get("id").asText(),json(Map.of("version",current.get("version").asLong(),"title",prefix))).statusCode());try(var w=book(report(leader))) { var cell=w.getSheet("Task details").getRow(7).getCell(0);assertEquals(CellType.STRING,cell.getCellType());assertEquals(prefix,cell.getStringCellValue());assertNull(cell.getHyperlink()); } }

    }
    @Test void r4_07TaskSettingsMembershipAndHistoryChangesStayInEstablishedSnapshot() throws Exception {
        configure();task("1.00",leader);var old=app.getBean(SnapshotService.class).captureReport(leader.id,project,new CaptureBudget(new ReportBudget(Duration.ofSeconds(10))));
        var barrier=app.getBean(BoardSnapshotProbe.class);barrier.armed.set(true);var pool=Executors.newSingleThreadExecutor();
        try { var pending=pool.submit(() -> report(leader));assertTrue(barrier.read.await(5,TimeUnit.SECONDS));
            jdbc.update("UPDATE projects SET near_due_days=0,settings_version=2 WHERE id=?",project);jdbc.update("UPDATE tasks SET category='Concurrent category' WHERE project_id=?",project);event("{\"concurrent\":true}");
            var actor=UUID.randomUUID();jdbc.update("INSERT INTO users VALUES (?,?,'Concurrent member','synthetic-hash',CURRENT_TIMESTAMP)",actor,actor+"@example.invalid");jdbc.update("INSERT INTO project_memberships VALUES (?,?,'MEMBER',CURRENT_TIMESTAMP)",project,actor);
            clock.advance(1);barrier.release.countDown();var r=pending.get(15,TimeUnit.SECONDS);assertEquals(200,r.statusCode());assertEquals(old.snapshot().stateToken(),probe.captured.snapshot().stateToken());assertEquals(old.history().hash(),probe.captured.history().hash());assertEquals(2,probe.captured.members().size());assertEquals("1",probe.captured.settings().settingsVersion());assertEquals(clock.instant(),probe.captured.snapshot().snapshotAtUtc());assertTrue(barrier.readOnly);assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,barrier.isolation);
            var fresh=app.getBean(SnapshotService.class).captureReport(leader.id,project,new CaptureBudget(new ReportBudget(Duration.ofSeconds(10))));assertNotEquals(old.snapshot().stateToken(),fresh.snapshot().stateToken());assertNotEquals(old.history().hash(),fresh.history().hash());assertEquals(3,fresh.members().size());
        } finally { barrier.release.countDown();pool.shutdownNow(); }
    }
    @Test void r4_08CountAndLimitPlusOneRefuseBeforeGenerationWithoutPartialSuccess() throws Exception {
        configure();jdbc.update("INSERT INTO tasks (id,project_id,title,description,category,status,effort_points,agreement_version,version,created_by,created_at_utc,updated_at_utc) SELECT gen_random_uuid(),?,'Boundary','','','TODO',1,1,0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM generate_series(1,1001)",project,leader.id);
        refusal(report(leader),422,"REPORT_TOO_LARGE");assertNull(probe.captured);awaitCleanup();
    }
    @Test void r4_09HistoryLimitPlusOneAndOversizedPayloadBoundCapture() throws Exception {
        configure();jdbc.update("INSERT INTO activity_events (id,project_id,actor_id,event_type,occurred_at_utc,change_summary) SELECT gen_random_uuid(),?,?,'TASK_EDITED',CURRENT_TIMESTAMP,'{}' FROM generate_series(1,10001)",project,leader.id);refusal(report(leader),422,"REPORT_TOO_LARGE");assertNull(probe.captured);
        var separate=leader.project("Text boundary");project=separate;configure();jdbc.update("INSERT INTO activity_events (id,project_id,actor_id,event_type,occurred_at_utc,change_summary) VALUES (?,?,?,'TASK_EDITED',CURRENT_TIMESTAMP,jsonb_build_object('description',repeat('x',33554433)))",UUID.randomUUID(),project,leader.id);
        refusal(report(leader),422,"REPORT_TOO_LARGE");assertNull(probe.captured);awaitCleanup();
    }
    @Test void r4_10BusyAcrossIndependentSessionsUntilWorkerAndCleanupFinish() throws Exception {
        configure();var second=new ApiClient(app.getWebServer().getPort());second.bootstrap();assertEquals(200,second.login("r4-leader@example.invalid",PASSWORD).statusCode());probe.mode="pause";var pool=Executors.newSingleThreadExecutor();
        try { var pending=pool.submit(() -> report(leader));assertTrue(probe.entered.await(5,TimeUnit.SECONDS));var busy=report(second);refusal(busy,429,"REPORT_BUSY");refusal(report(leader),429,"REPORT_BUSY");assertEquals("5",busy.headers().firstValue("retry-after").orElseThrow());assertEquals(1,owned());probe.release.countDown();assertEquals(200,pending.get(15,TimeUnit.SECONDS).statusCode());awaitCleanup();probe.mode="normal";assertEquals(200,report(second).statusCode()); } finally { probe.release.countDown();pool.shutdownNow(); }
    }
    @Test void r4_11ExpiryEpochAndCurrentMembershipRecheckedBeforeDelivery() throws Exception {
        configure();probe.arm("pause");var switchPool=Executors.newSingleThreadExecutor();
        try { var pending=switchPool.submit(() -> report(leader));assertTrue(probe.entered.await(5,TimeUnit.SECONDS));leader.bootstrap();assertEquals(200,leader.login("r4-member@example.invalid",PASSWORD).statusCode());probe.release.countDown();refusal(pending.get(15,TimeUnit.SECONDS),409,"SESSION_CHANGED");awaitCleanup(); } finally { probe.release.countDown();switchPool.shutdownNow(); }
        leader=new ApiClient(app.getWebServer().getPort());leader.bootstrap();assertEquals(200,leader.login("r4-leader@example.invalid",PASSWORD).statusCode());leader.bootstrap();probe.arm("pause");var logoutPool=Executors.newSingleThreadExecutor();
        try { var pending=logoutPool.submit(() -> report(leader));assertTrue(probe.entered.await(5,TimeUnit.SECONDS));assertEquals(204,leader.send("DELETE","/auth/sessions/current",null).statusCode());probe.release.countDown();refusal(pending.get(15,TimeUnit.SECONDS),401,"UNAUTHENTICATED");awaitCleanup(); } finally { probe.release.countDown();logoutPool.shutdownNow(); }
        // Reauthenticate after the deliberately changed test epoch.
        leader=new ApiClient(app.getWebServer().getPort());leader.bootstrap();assertEquals(200,leader.login("r4-leader@example.invalid",PASSWORD).statusCode());probe.arm("pause");var pool=Executors.newSingleThreadExecutor();
        try { var pending=pool.submit(() -> report(leader));assertTrue(probe.entered.await(5,TimeUnit.SECONDS));clock.advance(1800);probe.release.countDown();refusal(pending.get(15,TimeUnit.SECONDS),401,"UNAUTHENTICATED");awaitCleanup(); } finally { probe.release.countDown();pool.shutdownNow(); }
    }
    @Test void r4_12WithdrawnAuthorizationDuringGenerationRefusesAllSuccessHeaders() throws Exception {
        configure();probe.mode="pause";var pool=Executors.newSingleThreadExecutor();
        try { var pending=pool.submit(() -> report(leader));assertTrue(probe.entered.await(5,TimeUnit.SECONDS));try(var connection=db.migrator()) { PostgresFixture.sql(connection,"ALTER TABLE owlibi.projects DROP CONSTRAINT project_leader_membership"); }jdbc.update("UPDATE project_memberships SET role='MEMBER' WHERE project_id=? AND user_id=?",project,leader.id);probe.release.countDown();refusal(pending.get(15,TimeUnit.SECONDS),403,"FORBIDDEN");awaitCleanup(); } finally { probe.release.countDown();pool.shutdownNow(); }
    }
    @Test void r4_13GenerationFailureInvalidPayloadAndDatabaseUnavailableAreSafeAndClean() throws Exception {
        configure();probe.mode="failure";refusal(report(leader),503,"REPORT_UNAVAILABLE");awaitCleanup();probe.mode="incomplete";refusal(report(leader),503,"REPORT_UNAVAILABLE");awaitCleanup();probe.mode="normal";
        var completed=task("1.00",leader);done(completed,leader);jdbc.update("UPDATE task_assignees SET accepted_agreement_version=2 WHERE task_id=?",UUID.fromString(completed.get("id").asText()));refusal(report(leader),503,"REPORT_UNAVAILABLE");jdbc.update("UPDATE task_assignees SET accepted_agreement_version=1 WHERE task_id=?",UUID.fromString(completed.get("id").asText()));event("{\"tokenHash\":\"synthetic-forbidden-secret\"}");refusal(report(leader),503,"REPORT_UNAVAILABLE");awaitCleanup();db.stopDatabase();refusal(report(leader),503,"REPORT_UNAVAILABLE");
    }
    @Test void r4_14GenerationDeadlineWaitsForActualTerminationAndCleanup() throws Exception {
        configure();probe.mode="timeout";long start=System.nanoTime();refusal(report(leader),503,"REPORT_TIMEOUT");assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()>=20000);assertEquals(0,probe.finished.getCount());awaitCleanup();
    }
    @Test void r4_15DisconnectKeepsSlotUntilSynchronousWorkerFinishesThenCleans() throws Exception {
        configure();probe.mode="pause";
        try(var socket=new Socket("127.0.0.1",app.getWebServer().getPort())) { socket.getOutputStream().write(("GET /api/v1"+root()+"/professor-report HTTP/1.1\r\nHost: 127.0.0.1:"+app.getWebServer().getPort()+"\r\nCookie: JSESSIONID="+leader.sessionCookie()+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().flush();assertTrue(probe.entered.await(5,TimeUnit.SECONDS)); }
        var second=new ApiClient(app.getWebServer().getPort());second.bootstrap();second.login("r4-leader@example.invalid",PASSWORD);refusal(report(second),429,"REPORT_BUSY");assertEquals(1,owned());probe.release.countDown();assertTrue(probe.finished.await(15,TimeUnit.SECONDS));
        for(int n=0;n<100 && owned()!=0;n++) Thread.sleep(20);awaitCleanup();probe.mode="normal";assertEquals(200,report(second).statusCode());
    }
    @Test void r4_16SnapshotDeadlineReadOnlyLockAndStatementBudgetsWithoutMutationLocks() throws Exception {
        configure();var snapshots=app.getBean(SnapshotService.class);var deadline=new ReportBudget(Duration.ofNanos(1));assertThrows(dev.owlibi.reference.web.ApiFailure.class,() -> snapshots.captureReport(leader.id,project,new CaptureBudget(deadline)));
        try(var c=db.migrator()) { c.setAutoCommit(false);PostgresFixture.sql(c,"LOCK TABLE owlibi.activity_events IN ACCESS EXCLUSIVE MODE");long start=System.nanoTime();refusal(report(leader),503,"REPORT_TIMEOUT");assertTrue(Duration.ofNanos(System.nanoTime()-start).toSeconds()<13);c.rollback(); }awaitCleanup();
    }
    @Test void r4_17MaximumTasksEventsExactTextBoundaryMeasuredMemoryAndLimitPlusOne() throws Exception {
        configure();
        jdbc.update("INSERT INTO tasks (id,project_id,title,description,category,status,effort_points,agreement_version,version,created_by,created_at_utc,updated_at_utc) SELECT gen_random_uuid(),?,'Maximum task '||n,repeat('d',4000),'Boundary','TODO',99999999.99,1,0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM generate_series(1,1000) n",project,leader.id);
        jdbc.update("INSERT INTO task_assignees (task_id,project_id,user_id,assigned_at_utc) SELECT id,project_id,?,CURRENT_TIMESTAMP FROM tasks WHERE project_id=?",leader.id,project);
        int existing=jdbc.queryForObject("SELECT count(*) FROM activity_events WHERE project_id=?",Integer.class,project);
        jdbc.update("INSERT INTO activity_events (id,project_id,actor_id,event_type,occurred_at_utc,change_summary) SELECT gen_random_uuid(),?,?,'TASK_EDITED',CURRENT_TIMESTAMP,jsonb_build_object('description',repeat('x',2300)||n) FROM generate_series(1,?) n",project,leader.id,10000-existing);
        var snapshots=app.getBean(SnapshotService.class);var initial=snapshots.captureReport(leader.id,project,new CaptureBudget(new ReportBudget(Duration.ofSeconds(10))));
        long delta=ReportBudget.TEXT-initial.history().capturedBytes();assertTrue(delta>0);var selected=initial.history().events().stream().filter(e -> e.eventType().equals("TASK_EDITED")).findFirst().orElseThrow();
        String before=JSON.readTree(selected.changeSummary()).get("description").asText();
        try(var connection=db.migrator();var statement=connection.prepareStatement("UPDATE owlibi.activity_events SET change_summary=jsonb_build_object('description',?::text) WHERE id=?")) { statement.setString(1,before+"x".repeat((int)delta));statement.setObject(2,selected.id());assertEquals(1,statement.executeUpdate()); }
        initial=null;System.gc();var memory=java.lang.management.ManagementFactory.getMemoryMXBean();long baseline=memory.getHeapMemoryUsage().getUsed();var peak=new java.util.concurrent.atomic.AtomicLong(baseline);var nonheap=new java.util.concurrent.atomic.AtomicLong();var sampler=Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(() -> { peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(),Math::max);nonheap.accumulateAndGet(memory.getNonHeapMemoryUsage().getUsed(),Math::max); },0,2,TimeUnit.MILLISECONDS);
        long start=System.nanoTime();HttpResponse<byte[]> response;
        try { response=report(leader); } finally { sampler.shutdownNow(); }
        assertEquals(200,response.statusCode());assertEquals(ReportBudget.TEXT,probe.captured.history().capturedBytes());assertEquals(1000,probe.captured.tasks().size());assertEquals(10000,probe.captured.history().count());awaitCleanup();assertTrue(probe.workspacePeak<=ReportBudget.WORKSPACE);
        // Drain every ZIP entry to check CRC/completeness without claiming native compatibility.
        var entries=new HashSet<String>();
        try(var zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(response.body()))) {
            byte[] buffer=new byte[8192];
            java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                assertTrue(entries.add(entry.getName()));
                while(zip.read(buffer)!=-1) {}
                zip.closeEntry();
            }
        }
        assertEquals(14,entries.size());
        for(int sheet=1;sheet<=6;sheet++) assertTrue(entries.contains("xl/worksheets/sheet"+sheet+".xml"));
        var measured=new LinkedHashMap<String,Object>();measured.put("tasks",1000);measured.put("events",10000);measured.put("capturedBytes",probe.captured.history().capturedBytes());measured.put("xlsxBytes",response.body().length);measured.put("liveWorkspacePeakBytes",probe.workspacePeak);measured.put("baselineHeapUsedBytes",baseline);measured.put("sampledHeapPeakBytes",peak.get());measured.put("sampledNonheapPeakBytes",nonheap.get());measured.put("jvmMaxHeapBytes",Runtime.getRuntime().maxMemory());measured.put("requestMillis",Duration.ofNanos(System.nanoTime()-start).toMillis());measured.put("generationMillis",Duration.ofNanos(probe.generationNanos).toMillis());measured.put("sampling","2 ms MemoryMXBean samples; same JVM including application and capture/generation; not a hard heap cap, RSS measurement or hosting fit");
        Files.createDirectories(Path.of("target/r4-captures"));Files.writeString(Path.of("target/r4-captures/maximum-resources.json"),json(measured));evidence("maximum",response.body());
        try(var connection=db.migrator();var statement=connection.prepareStatement("UPDATE owlibi.activity_events SET change_summary=jsonb_build_object('description',?::text) WHERE id=?")) { statement.setString(1,before+"x".repeat((int)delta)+"x");statement.setObject(2,selected.id());statement.executeUpdate(); }
        refusal(report(leader),422,"REPORT_TOO_LARGE");awaitCleanup();
    }

    @Test void r4_18IncompressibleMaximumTextRefusesFinalZipBeforeHeadersAndMeasuresCleanup() throws Exception {
        configure();
        jdbc.update("INSERT INTO activity_events (id,project_id,actor_id,event_type,occurred_at_utc,change_summary) SELECT gen_random_uuid(),?,?,'TASK_EDITED',CURRENT_TIMESTAMP,jsonb_build_object('description',payload) FROM generate_series(1,970) e CROSS JOIN LATERAL (SELECT string_agg(md5(e::text||':'||n::text),'') AS payload FROM generate_series(1,1000) n) strings",project,leader.id);
        var memory=java.lang.management.ManagementFactory.getMemoryMXBean();var peak=new java.util.concurrent.atomic.AtomicLong(memory.getHeapMemoryUsage().getUsed());var sampler=Executors.newSingleThreadScheduledExecutor();sampler.scheduleAtFixedRate(() -> peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(),Math::max),0,2,TimeUnit.MILLISECONDS);
        long start=System.nanoTime();HttpResponse<byte[]> response;try { response=report(leader); } finally { sampler.shutdownNow(); }
        refusal(response,422,"REPORT_TOO_LARGE");assertNotNull(probe.captured);assertTrue(probe.captured.history().capturedBytes()<ReportBudget.TEXT);assertTrue(probe.workspacePeak<=ReportBudget.WORKSPACE);awaitCleanup();
        var values=Map.of("capturedBytes",probe.captured.history().capturedBytes(),"events",probe.captured.history().count(),"sampledHeapPeakBytes",peak.get(),"liveWorkspacePeakBytes",probe.workspacePeak,"requestMillis",Duration.ofNanos(System.nanoTime()-start).toMillis(),"generationMillis",Duration.ofNanos(probe.generationNanos).toMillis(),"result","422 REPORT_TOO_LARGE; bounded final-output stream refused before XLSX headers; all owned workspace removed; no successful or partial XLSX retained");
        Files.createDirectories(Path.of("target/r4-captures"));Files.writeString(Path.of("target/r4-captures/incompressible-resources.json"),json(values));
    }

}
