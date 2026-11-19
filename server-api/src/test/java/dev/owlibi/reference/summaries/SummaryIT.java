package dev.owlibi.reference.summaries;

import static org.junit.jupiter.api.Assertions.*;
import static dev.owlibi.reference.support.ApiClient.*;
import dev.owlibi.reference.support.*;
import dev.owlibi.reference.snapshots.*;
import java.math.*;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import tools.jackson.databind.JsonNode;

/** Required R2 HTTP/PostgreSQL/decimal/Clock cases. No development data or containers. */
class SummaryIT {
    private PostgresFixture db;
    private ServletWebServerApplicationContext app;
    private JdbcTemplate jdbc;
    private ApiClient leader,a,b;
    private UUID project;
    private final MutableClock clock=MutableClock.INSTANCE;
    @BeforeEach void start() throws Exception {
        clock.reset();clock.advance(Duration.between(clock.instant(),Instant.parse("2026-10-13T15:59:59Z")).getSeconds());
        db=new PostgresFixture();app=db.startApp(BoardSnapshotProbe.SOURCE);jdbc=app.getBean(JdbcTemplate.class);
        leader=account("r2-leader@example.invalid");a=account("r2-a@example.invalid");b=account("r2-b@example.invalid");
        project=leader.project("Synthetic R2");join(a);join(b);
    }
    @AfterEach void stop() { if(app!=null) app.close();if(db!=null) db.close(); }
    private ApiClient account(String email) throws Exception { var c=new ApiClient(app.getWebServer().getPort());c.account(email);return c; }
    private void join(ApiClient member) throws Exception { assertEquals(200,member.accept(leader.invitation(project)).statusCode()); }
    private String root() { return "/projects/"+project; }
    private JsonNode get(ApiClient client,String route) throws Exception { var r=client.send("GET",root()+route,null);assertEquals(200,r.statusCode(),r.body());return tree(r); }
    private JsonNode contribution() throws Exception { return get(a,"/contribution-summary"); }
    private JsonNode workload() throws Exception { return get(b,"/workload-summary"); }
    private JsonNode board() throws Exception { return get(leader,"/board"); }
    private String token(JsonNode result) { return result.get("snapshot").get("stateToken").asText(); }
    private int events() { return jdbc.queryForObject("SELECT count(*) FROM activity_events WHERE project_id=?",Integer.class,project); }
    private JsonNode settings(Map<String,Object> fields) throws Exception {
        var body=new LinkedHashMap<>(fields);body.put("settingsVersion",get(leader,"/settings").get("settingsVersion").asText());
        var r=leader.send("PATCH",root()+"/settings",json(body));assertEquals(200,r.statusCode(),r.body());return tree(r);
    }
    private void configure() throws Exception {
        var catalogue=tree(leader.send("GET","/templates/blank",null));settings(JSON.convertValue(catalogue.get("suggestedSettings"),Map.class));
    }
    private JsonNode task(String points,String due,ApiClient... members) throws Exception {
        var fields=new LinkedHashMap<String,Object>();fields.put("title","Synthetic task "+UUID.randomUUID());fields.put("effortPoints",points);
        fields.put("dueDate",due);fields.put("assigneeIds",Arrays.stream(members).map(c -> c.id).toList());
        var r=leader.send("POST",root()+"/tasks",json(fields));assertEquals(201,r.statusCode(),r.body());return tree(r);
    }
    private UUID id(JsonNode task) { return UUID.fromString(task.get("id").asText()); }
    private JsonNode current(JsonNode task) throws Exception { for(var t:board().get("tasks")) if(t.get("id").equals(task.get("id"))) return t;throw new AssertionError(); }
    private JsonNode move(JsonNode task,String action) throws Exception {
        var r=leader.send("POST",root()+"/tasks/"+id(task)+"/transitions",json(Map.of("action",action,"version",current(task).get("version").asLong(),"reason","Synthetic test reason")));
        assertEquals(200,r.statusCode(),r.body());return tree(r);
    }
    private void accept(JsonNode task,ApiClient... clients) throws Exception {
        for(var c:clients) assertEquals(200,c.send("POST",root()+"/tasks/"+id(task)+"/acceptance",json(Map.of("agreementVersion",current(task).get("agreementVersion").asLong()))).statusCode());
    }
    private JsonNode done(JsonNode task,ApiClient... clients) throws Exception { accept(task,clients);move(task,"START");move(task,"SUBMIT_REVIEW");return move(task,"APPROVE"); }
    private JsonNode edit(JsonNode task,Map<String,Object> fields) throws Exception {
        var body=new LinkedHashMap<>(fields);body.put("version",current(task).get("version").asLong());
        var r=leader.send("PATCH",root()+"/tasks/"+id(task),json(body));assertEquals(200,r.statusCode(),r.body());return tree(r);
    }
    private JsonNode member(JsonNode result,UUID id) { for(var m:result.get("members")) if(m.get("userId").asText().equals(id.toString())) return m;throw new AssertionError(); }
    private JsonNode resultTask(JsonNode result,JsonNode task) { for(var t:result.get("tasks")) if(t.get("taskId").equals(task.get("id"))) return t;throw new AssertionError(); }
    private static BigDecimal number(JsonNode n,String field) { assertTrue(n.get(field).isString());return new BigDecimal(n.get(field).asText()); }
    private static void numeric(String expected,JsonNode n,String field) { assertEquals(0,new BigDecimal(expected).compareTo(number(n,field)),field); }
    private static void evidence(String name,JsonNode response) throws Exception {
        var directory=java.nio.file.Path.of("target/r2-captures");java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve(name+".json"),JSON.writeValueAsString(response)+"\n");
    }

    @Test void r2_01AuthenticatedMembershipStrictRequestsPrivacyAndIdleBoundary() throws Exception {
        configure();var outside=account("r2-outside@example.invalid");var anon=new ApiClient(app.getWebServer().getPort());int before=events();
        for(String route:List.of("/contribution-summary","/workload-summary")) {
            error(anon.send("GET",root()+route,null),401,"UNAUTHENTICATED");error(outside.send("GET",root()+route,null),404,"NOT_FOUND");
            error(a.send("GET","/projects/"+UUID.randomUUID()+route,null),404,"NOT_FOUND");
            error(a.send("GET",root()+route+"?threshold=0",null),400,"MALFORMED_REQUEST");error(a.send("GET",root()+route,"{}"),400,"MALFORMED_REQUEST");
            var r=a.send("GET",root()+route,null);assertEquals(200,r.statusCode());assertTrue(r.headers().firstValue("cache-control").orElse("").contains("no-store"));assertEquals(a.epoch,r.headers().firstValue("X-Session-Epoch").orElseThrow());
            for(String secret:List.of("@example.invalid","password","tokenHash","sessionEpoch","jdbc:")) assertFalse(r.body().contains(secret));
            assertEquals(405,leader.send("POST",root()+route,"{}").statusCode());
        }
        UUID other=outside.project("Synthetic other project");var isolated=outside.send("GET","/projects/"+other+"/contribution-summary",null);assertEquals(200,isolated.statusCode());assertEquals(1,tree(isolated).get("members").size());
        jdbc.update("INSERT INTO project_memberships VALUES (?,?,'MEMBER',CURRENT_TIMESTAMP)",project,outside.id);assertEquals(200,outside.send("GET",root()+"/workload-summary",null).statusCode());
        jdbc.update("DELETE FROM project_memberships WHERE project_id=? AND user_id=?",project,outside.id);error(outside.send("GET",root()+"/workload-summary",null),404,"NOT_FOUND");
        assertEquals(before,events());clock.advance(1800);error(a.send("GET",root()+"/contribution-summary",null),401,"UNAUTHENTICATED");
    }
    @Test void r2_02LegacyEmptyContributionNullPercentagesAndRequiredWorkloadSetup() throws Exception {
        var result=contribution();assertEquals(Set.of("projectId","snapshot","projectZone","projectToday","rules","empty","totalCompletedPoints","totalCompletedPointsDisplay","members","allocations","formula","limitations"),result.propertyNames());
        assertTrue(result.get("empty").asBoolean());assertTrue(result.get("rules").isNull());assertEquals("0.00",result.get("totalCompletedPoints").asText());assertEquals("0.0",result.get("totalCompletedPointsDisplay").asText());assertTrue(result.get("allocations").isEmpty());
        for(var m:result.get("members")) { assertEquals("0",m.get("completedPoints").asText());assertEquals("0.0",m.get("completedPointsDisplay").asText());assertTrue(m.get("sharePercent").isNull());assertTrue(m.get("sharePercentDisplay").isNull()); }
        assertTrue(result.get("formula").asText().contains("No completed tasks yet"));assertEquals(token(board()),token(result));error(b.send("GET",root()+"/workload-summary",null),409,"SETTINGS_REQUIRED");
        evidence("contribution-empty",result);
        configure();assertFalse(contribution().get("rules").isNull());assertEquals(0,workload().get("projectCounts").get("openTasks").asInt());
    }
    @Test void r2_03SoloMultipleDoneAndDuplicateDisplayNamesKeepMembershipOrder() throws Exception {
        configure();done(task("5.50",null,a),a);done(task("2.50",null,b),b);task("99.00",null,a);var cancelled=task("99.00",null,b);move(cancelled,"CANCEL");
        var result=contribution();numeric("8.00",result,"totalCompletedPoints");numeric("5.50",member(result,a.id),"completedPoints");numeric("68.75",member(result,a.id),"sharePercent");assertEquals("68.8",member(result,a.id).get("sharePercentDisplay").asText());
        assertEquals("0.0",member(result,leader.id).get("sharePercentDisplay").asText());assertEquals(2,result.get("allocations").size());
        var boardMembers=board().get("members");for(int i=0;i<3;i++) { assertEquals(boardMembers.get(i).get("id"),result.get("members").get(i).get("userId"));assertEquals("Synthetic account",result.get("members").get(i).get("displayName").asText()); }
    }
    @Test void r2_04RepeatingSharedDivisionsAggregateBeforeDisplayAndCountDenominatorOnce() throws Exception {
        configure();for(int i=0;i<3;i++) done(task("1.00",null,leader,a,b),leader,a,b);
        var result=contribution();assertEquals("3.00",result.get("totalCompletedPoints").asText());
        for(var allocation:result.get("allocations")) assertEquals("0.3333333333333333333333333333333333",allocation.get("perAssigneePoints").asText());
        for(var m:result.get("members")) { assertEquals("0.9999999999999999999999999999999999",m.get("completedPoints").asText());assertEquals("1.0",m.get("completedPointsDisplay").asText());assertEquals("33.3",m.get("sharePercentDisplay").asText()); }
        assertTrue(result.get("limitations").toString().contains("99.9%"));
        evidence("contribution-shared",result);
        done(task("99999999.99",null,a),a);numeric("100000000.9899999999999999999999999999999999",member(contribution(),a.id),"completedPoints");
    }
    @Test void r2_05MaximumEstimateAndTinyPositiveShareRemainPlainDecimalStrings() throws Exception {
        configure();done(task("99999999.99",null,a),a);done(task("0.01",null,b),b);var result=contribution();
        assertEquals("100000000.00",result.get("totalCompletedPoints").asText());assertEquals("0.0000000100",member(result,b.id).get("sharePercent").asText());assertEquals("0.0",member(result,b.id).get("sharePercentDisplay").asText());assertTrue(number(member(result,b.id),"sharePercent").signum()>0);
        for(var m:result.get("members")) for(String field:List.of("completedPoints","sharePercent")) assertFalse(m.get(field).asText().contains("E"));
    }
    @Test void r2_06IndependentHalfUpPercentagesMaySumToOneHundredPointOne() throws Exception {
        configure();done(task("16.67",null,leader),leader);done(task("16.67",null,a),a);done(task("66.66",null,b),b);var result=contribution();
        assertEquals("16.7",member(result,leader.id).get("sharePercentDisplay").asText());assertEquals("16.7",member(result,a.id).get("sharePercentDisplay").asText());assertEquals("66.7",member(result,b.id).get("sharePercentDisplay").asText());
        BigDecimal sum=BigDecimal.ZERO;for(var m:result.get("members")) sum=sum.add(number(m,"sharePercentDisplay"));assertEquals(new BigDecimal("100.1"),sum);
    }
    @Test void r2_07ReopenRecompleteAndCancelledPartialExclusionsFollowRealWorkflow() throws Exception {
        configure();var completed=done(task("8.00",null,a,b),a,b);var cancelled=task("100.00","2026-10-12",a);move(cancelled,"CANCEL");var partial=task("100.00","2026-10-12",b);accept(partial,b);move(partial,"START");move(partial,"SUBMIT_REVIEW");
        String old=token(contribution());move(completed,"REOPEN");var reopened=contribution();assertTrue(reopened.get("empty").asBoolean());assertNotEquals(old,token(reopened));numeric("4.00",member(workload(),a.id),"remainingPoints");
        move(completed,"SUBMIT_REVIEW");move(completed,"APPROVE");numeric("8.00",contribution(),"totalCompletedPoints");assertEquals(1,contribution().get("allocations").size());
    }
    @Test void r2_08CorruptedDoneAcceptanceOrEmptyAssigneesFailSafeWithoutSkippingCredit() throws Exception {
        configure();var t=done(task("4.00",null,a),a);int count=events();
        jdbc.update("UPDATE task_assignees SET accepted_agreement_version=2 WHERE task_id=?",id(t));error(a.send("GET",root()+"/contribution-summary",null),503,"SUMMARY_UNAVAILABLE");
        jdbc.update("UPDATE task_assignees SET accepted_agreement_version=NULL,accepted_at_utc=NULL WHERE task_id=?",id(t));error(a.send("GET",root()+"/contribution-summary",null),503,"SUMMARY_UNAVAILABLE");
        jdbc.update("DELETE FROM task_assignees WHERE task_id=?",id(t));error(a.send("GET",root()+"/contribution-summary",null),503,"SUMMARY_UNAVAILABLE");assertEquals(count,events());
    }
    @Test void r2_09StrictUnroundedThresholdEqualityBelowAboveAndSharedOpenEffort() throws Exception {
        configure();var solo=task("12.00",null,a);var shared=task("16.00",null,a,b);accept(shared,a,b);move(shared,"START");
        var equal=member(workload(),a.id);numeric("20.00",equal,"remainingPoints");assertFalse(equal.get("overloaded").asBoolean());
        edit(solo,Map.of("effortPoints","11.99"));assertFalse(member(workload(),a.id).get("overloaded").asBoolean());
        edit(solo,Map.of("effortPoints","12.01"));var above=member(workload(),a.id);numeric("20.01",above,"remainingPoints");assertEquals("20.0",above.get("remainingPointsDisplay").asText());assertTrue(above.get("overloaded").asBoolean());numeric("8.00",member(workload(),b.id),"remainingPoints");
        settings(Map.of("overloadThreshold","99999999.99"));assertFalse(member(workload(),a.id).get("overloaded").asBoolean());
        var thirds=task("1.00",null,leader,a,b);settings(Map.of("overloadThreshold","0.33"));var fractional=member(workload(),leader.id);
        assertEquals("0.3333333333333333333333333333333333",fractional.get("remainingPoints").asText());assertEquals("0.3",fractional.get("remainingPointsDisplay").asText());assertTrue(fractional.get("overloaded").asBoolean());
        task("99999999.99",null,leader);numeric("100000000.3233333333333333333333333333333333",member(workload(),leader.id),"remainingPoints");
    }
    @Test void r2_10InclusiveDateWindowDisjointOverdueUndatedAndUniqueSharedCounts() throws Exception {
        configure();var overdue=task("2.00","2026-10-12",a,b);var today=task("2.00","2026-10-13",a,b);var edge=task("2.00","2026-10-16",a);var later=task("2.00","2026-10-17",b);var undated=task("2.00",null,a);
        var result=workload();assertEquals("2026-10-13",result.get("projectToday").asText());assertEquals("Asia/Manila",result.get("projectZone").asText());
        for(var pair:List.of(List.of(overdue,"OVERDUE"),List.of(today,"NEAR_DUE"),List.of(edge,"NEAR_DUE"),List.of(later,"LATER"),List.of(undated,"NONE"))) assertEquals(pair.get(1),resultTask(result,(JsonNode)pair.get(0)).get("dateState").asText());
        assertEquals(JSON.valueToTree(Map.of("openTasks",5,"reviewTasks",0,"overdueTasks",1,"nearDueTasks",2)),result.get("projectCounts"));
        var ma=member(result,a.id);assertEquals(1,ma.get("overdueCount").asInt());assertEquals(2,ma.get("nearDueCount").asInt());assertEquals(4,ma.get("openTaskIds").size());numeric("6.00",ma,"remainingPoints");
        assertEquals(1,member(result,b.id).get("overdueCount").asInt());
        assertEquals(Set.of("projectId","snapshot","projectZone","projectToday","rules","calculationKey","members","tasks","reviewTaskIds","projectCounts","formula","limitations"),result.propertyNames());evidence("workload-dates",result);
    }
    @Test void r2_11ZeroWindowManilaMidnightChangesCalculationKeyButNotStateToken() throws Exception {
        configure();settings(Map.of("nearDueDays",0));var oldDay=task("1.00","2026-10-13",a);var newDay=task("1.00","2026-10-14",b);var old=workload();
        assertEquals("NEAR_DUE",resultTask(old,oldDay).get("dateState").asText());assertEquals("LATER",resultTask(old,newDay).get("dateState").asText());clock.advance(1);
        var next=workload();assertEquals("2026-10-14",next.get("projectToday").asText());assertEquals(token(old),token(next));assertNotEquals(old.get("calculationKey"),next.get("calculationKey"));
        assertEquals("OVERDUE",resultTask(next,oldDay).get("dateState").asText());assertEquals("NEAR_DUE",resultTask(next,newDay).get("dateState").asText());
    }
    @Test void r2_12ReviewDateVisibleReturnRestoresEffortAndPendingTermsKeepStatusTreatment() throws Exception {
        configure();var t=task("100.00","2026-10-12",a,b);accept(t,a,b);move(t,"START");move(t,"SUBMIT_REVIEW");
        var review=workload();assertEquals("0",resultTask(review,t).get("perAssigneeRemainingPoints").asText());assertEquals(JSON.valueToTree(List.of(id(t))),review.get("reviewTaskIds"));assertEquals(1,member(review,a.id).get("overdueCount").asInt());numeric("0",member(review,a.id),"remainingPoints");
        edit(t,Map.of("title","Revised review terms","reason","Synthetic changed terms"));assertEquals("REVIEW",current(t).get("status").asText());assertEquals(1,member(workload(),a.id).get("overdueCount").asInt());
        move(t,"RETURN");numeric("50.00",member(workload(),a.id),"remainingPoints");assertTrue(member(workload(),a.id).get("overloaded").asBoolean());
        var r=leader.send("POST",root()+"/tasks/"+id(t)+"/transitions",json(Map.of("action","SUBMIT_REVIEW","version",current(t).get("version").asLong())));error(r,422,"ACCEPTANCE_REQUIRED");
    }
    @Test void r2_13TokensChangeForAcceptanceTermsMetadataAssignmentsSettingsAndMembership() throws Exception {
        configure();var t=task("2.00",null,a);String token=token(board());accept(t,a);assertNotEquals(token,token(board()));token=token(board());accept(t,a);assertEquals(token,token(board()));
        edit(t,Map.of("title",current(t).get("title").asText(),"effortPoints","02.0"));assertEquals(token,token(board()));settings(Map.of("overloadThreshold","020.0"));assertEquals(token,token(board()));
        for(var fields:List.of(Map.<String,Object>of("category","Metadata"),Map.<String,Object>of("dueDate","2026-10-14"),Map.<String,Object>of("description","Changed description"),Map.<String,Object>of("effortPoints","2.01"),Map.<String,Object>of("assigneeIds",List.of(b.id)))) {
            String before=token(board());edit(t,fields);assertNotEquals(before,token(board()));assertEquals(token(board()),token(contribution()));assertEquals(token(board()),token(workload()));
        }
        token=token(board());settings(Map.of("nearDueDays",0));assertNotEquals(token,token(board()));token=token(board());join(account("r2-new-member@example.invalid"));assertNotEquals(token,token(board()));
    }
    @Test void r2_14CanonicalSortedKeysExplicitNullsCallerClockAndActivityExclusion() throws Exception {
        configure();var t=task("1.00",null,a);accept(t,a);var ba=get(a,"/board");var bb=get(b,"/board");assertEquals(token(ba),token(bb));assertNotEquals(ba.get("tasks").get(0).get("currentUserAcceptedAgreement"),bb.get("tasks").get(0).get("currentUserAcceptedAgreement"));
        assertEquals(Set.of("stateToken","snapshotAtUtc"),ba.get("snapshot").propertyNames());assertTrue(token(ba).matches("[a-f0-9]{64}"));
        String original=token(ba);leader.invitation(project);assertEquals(original,token(board()));clock.advanceNanos(987654321);assertEquals(original,token(board()));assertEquals("2026-10-13T15:59:59.987654Z",board().get("snapshot").get("snapshotAtUtc").asText());
        assertEquals("{\"a\":[{\"a\":1,\"z\":null}],\"b\":\"é\"}",CanonicalState.json(JSON.readTree("{\"b\":\"é\",\"a\":[{\"z\":null,\"a\":1}]}")));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",CanonicalState.hash("abc"));
        assertEquals(CanonicalState.json(JSON.readTree("{\"one\":1,\"two\":2}")),CanonicalState.json(JSON.readTree("{\"two\":2,\"one\":1}")));
        assertEquals("{\"at\":\"2026-10-13T16:00:00.123456Z\"}",CanonicalState.json(Map.of("at",Instant.parse("2026-10-13T16:00:00.123456Z"))));
    }
    @Test void r2_15ConcurrentReopenTermsReacceptCompletionCaptureIsEntirelyOldOrNew() throws Exception {
        configure();var t=done(task("9.00",null,a),a);var before=contribution();
        // SessionBoundaryFilter serializes one cookie session. The paused reader must be independent of acceptance writes.
        var reader=new ApiClient(app.getWebServer().getPort());reader.bootstrap();assertEquals(200,reader.login("r2-a@example.invalid",PASSWORD).statusCode());reader.bootstrap();
        var probe=app.getBean(BoardSnapshotProbe.class);probe.armed.set(true);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var pending=pool.submit(() -> reader.send("GET",root()+"/contribution-summary",null));
            try { assertTrue(probe.read.await(5,TimeUnit.SECONDS));assertEquals(Connection.TRANSACTION_REPEATABLE_READ,probe.isolation);assertTrue(probe.readOnly);
                move(t,"REOPEN");edit(t,Map.of("effortPoints","12.00","assigneeIds",List.of(a.id,b.id),"reason","Synthetic changed terms"));accept(t,a,b);move(t,"SUBMIT_REVIEW");move(t,"APPROVE");
            } finally { probe.release.countDown(); }
            var r=pending.get(15,TimeUnit.SECONDS);assertEquals(200,r.statusCode());assertEquals(before,tree(r));
        }
        var after=contribution();numeric("12.00",after,"totalCompletedPoints");numeric("6.00",member(after,a.id),"completedPoints");numeric("6.00",member(after,b.id),"completedPoints");assertNotEquals(token(before),token(after));
    }
    @Test void r2_16ConcurrentSettingsMembershipCreationAndAcceptanceUseOneWorkloadSnapshot() throws Exception {
        configure();var t=task("25.00","2026-10-16",a);var outside=account("r2-snapshot@example.invalid");String invite=leader.invitation(project);var before=workload();var probe=app.getBean(BoardSnapshotProbe.class);probe.armed.set(true);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var pending=pool.submit(() -> b.send("GET",root()+"/workload-summary",null));
            try { assertTrue(probe.read.await(5,TimeUnit.SECONDS));assertTrue(probe.readOnly);assertEquals(Connection.TRANSACTION_REPEATABLE_READ,probe.isolation);
                settings(Map.of("overloadThreshold","30.00","nearDueDays",0));assertEquals(200,outside.accept(invite).statusCode());task("2.00",null,outside);accept(t,a);
            } finally { probe.release.countDown(); }
            var r=pending.get(15,TimeUnit.SECONDS);assertEquals(200,r.statusCode());assertEquals(before,tree(r));
        }
        var after=workload();assertEquals(4,after.get("members").size());assertEquals(2,after.get("projectCounts").get("openTasks").asInt());assertFalse(member(after,a.id).get("overloaded").asBoolean());assertEquals("LATER",resultTask(after,t).get("dateState").asText());assertEquals("2",after.get("rules").get("settingsVersion").asText());assertEquals(token(board()),token(after));
    }
    @Test void r2_17CompleteThousandTaskBoundaryAndOversizedFixtureNeverTruncates() throws Exception {
        configure();jdbc.update("INSERT INTO tasks (id,project_id,title,status,effort_points,agreement_version,version,created_by,created_at_utc,updated_at_utc) SELECT gen_random_uuid(),?,'Synthetic capacity','TODO',1,1,0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM generate_series(1,1000)",project,leader.id);
        jdbc.update("INSERT INTO task_assignees (task_id,project_id,user_id,assigned_at_utc) SELECT id,project_id,?,created_at_utc FROM tasks WHERE project_id=?",a.id,project);
        var result=workload();assertEquals(1000,result.get("tasks").size());assertEquals(1000,result.get("projectCounts").get("openTasks").asInt());numeric("1000.00",member(result,a.id),"remainingPoints");assertEquals(token(board()),token(result));
        jdbc.update("INSERT INTO tasks (id,project_id,title,status,effort_points,agreement_version,version,created_by,created_at_utc,updated_at_utc) VALUES (gen_random_uuid(),?,'Synthetic overflow','TODO',1,1,0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",project,leader.id);
        for(String route:List.of("/board","/contribution-summary","/workload-summary")) error(a.send("GET",root()+route,null),422,"TASK_LIMIT");
    }
    @Test void r2_18DetachedImmutableCaptureSamplesClockOnceAfterMembershipAndCannotWrite() throws Exception {
        configure();var t=task("3.00",null,a);var service=app.getBean(SnapshotService.class);var capture=service.capture(a.id,project);String old=token(board());
        assertThrows(UnsupportedOperationException.class,() -> capture.members().clear());assertThrows(UnsupportedOperationException.class,() -> capture.tasks().clear());assertThrows(UnsupportedOperationException.class,() -> capture.tasks().get(0).assignees().clear());assertThrows(UnsupportedOperationException.class,() -> capture.settings().effortScaleExamples().clear());
        var count=new java.util.concurrent.atomic.AtomicInteger();var observed=new java.util.concurrent.atomic.AtomicBoolean();
        Clock once=new Clock() {
            public Instant instant() { assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM project_memberships WHERE project_id=? AND user_id=?",Integer.class,project,a.id));count.incrementAndGet();observed.set(true);return Instant.parse("2026-10-13T16:00:00.123456789Z"); }
            public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId z) { return this; }
        };
        var tx=new org.springframework.transaction.support.TransactionTemplate(app.getBean(org.springframework.transaction.PlatformTransactionManager.class));tx.setReadOnly(true);tx.setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
        var manual=new SnapshotService(jdbc,once,app.getBean(dev.owlibi.reference.projects.ProjectSettingsService.class));
        var sampled=tx.execute(status -> manual.capture(a.id,project));assertTrue(observed.get());assertEquals(1,count.get());assertEquals("2026-10-14",sampled.projectToday().toString());assertEquals("2026-10-13T16:00:00.123456Z",sampled.snapshot().snapshotAtUtc().toString());
        var probe=app.getBean(BoardSnapshotProbe.class);probe.armed.set(true);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var pending=pool.submit(() -> service.capture(a.id,project));
            try { assertTrue(probe.read.await(5,TimeUnit.SECONDS));clock.advance(1);edit(t,Map.of("effortPoints","4.00")); }
            finally { probe.release.countDown(); }
            var paused=pending.get(15,TimeUnit.SECONDS);assertEquals(old,paused.snapshot().stateToken());assertEquals("2026-10-14",paused.projectToday().toString());
        }
        numeric("3.00",JSON.valueToTree(SummaryCalculator.workload(capture).members().stream().filter(m -> m.userId().equals(a.id)).findFirst().orElseThrow()),"remainingPoints");assertEquals(old,capture.snapshot().stateToken());assertNotEquals(old,token(board()));
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        int before=events();assertThrows(org.springframework.dao.DataAccessException.class,() -> tx.execute(status -> {
            jdbc.update("UPDATE tasks SET title='Synthetic forbidden snapshot write' WHERE id=?",id(t));return null;
        }));assertEquals(before,events());assertNotEquals("Synthetic forbidden snapshot write",current(t).get("title").asText());
    }
}
