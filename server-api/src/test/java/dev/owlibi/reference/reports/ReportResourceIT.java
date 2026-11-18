package dev.owlibi.reference.reports;

import static org.junit.jupiter.api.Assertions.*;
import dev.owlibi.reference.snapshots.*;
import dev.owlibi.reference.projects.*;
import dev.owlibi.reference.web.ApiFailure;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;

/** Boundary mechanisms and owned resources, without development PostgreSQL or workbook imports. */
class ReportResourceIT {
    @TempDir Path parent;
    private static long entryCount(Path directory) throws IOException { try(var files=Files.list(directory)) { return files.count(); } }
    private static void assertPrivate(Path path,boolean directory) throws IOException {
        var posix=Files.getFileAttributeView(path,java.nio.file.attribute.PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null) { assertEquals(directory?"rwx------":"rw-------",PosixFilePermissions.toString(posix.readAttributes().permissions()));return; }
        var acl=Files.getFileAttributeView(path,java.nio.file.attribute.AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);assertNotNull(acl,"Owner-only export permissions required");
        var entries=acl.getAcl();assertEquals(1,entries.size());var entry=entries.getFirst();
        assertEquals(acl.getOwner(),entry.principal());assertEquals(java.nio.file.attribute.AclEntryType.ALLOW,entry.type());
        assertEquals(EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class),entry.permissions());
    }
    private ReportBudget deadline() { return new ReportBudget(Duration.ofSeconds(20)); }
    private static void code(String code,org.junit.jupiter.api.function.Executable action) { assertEquals(code,assertThrows(ApiFailure.class,action).error().code()); }
    private ProjectSnapshot snapshot(String name,String description,String taskText) {
        UUID project=UUID.randomUUID(),member=UUID.randomUUID(),task=UUID.randomUUID();Instant at=Instant.parse("2026-10-13T16:00:00Z");
        var settings=new ProjectRules.Settings(project,true,"9223372036854775807","Asia/Manila","Relative effort",List.of(new ProjectRules.Example("1.00","Small scoped change")),"20.00",3,at);
        var assignment=new ProjectSnapshot.Assignment(task,project,member,name,at,1L,at);
        var row=new ProjectSnapshot.Task(task,project,taskText,taskText,"Category","DONE",LocalDate.of(1,1,1),"1.00",9007199254740993L,9007199254740993L,at,member,at,at,List.of(new ProjectSnapshot.Assignment(task,project,member,name,at,9007199254740993L,at)));
        var draft=new ProjectSnapshot(new ProjectService.Project(project,name,description,"LEADER",at),"{\"id\":\"blank\",\"version\":1}",List.of(new ProjectSnapshot.Member(member,name,"LEADER",at)),settings,List.of(row),new ProjectSnapshot.Metadata("a".repeat(64),at),LocalDate.of(2026,10,14));
        return new ProjectSnapshot(draft.project(),draft.templateJson(),draft.members(),draft.settings(),draft.tasks(),draft.snapshot(),draft.projectToday(),new ProjectSnapshot.History(List.of(),0,CanonicalState.hash("[]"),0));
    }
    @Test void r4_resource_01MonotonicDeadlineExactBoundaryAndInterruptDoesNotPretendWorkerStopped() {
        var tick=new AtomicLong(123);var budget=new ReportBudget(Duration.ofSeconds(10),tick::get);tick.addAndGet(9_999_999_999L);budget.check();tick.incrementAndGet();code("REPORT_TIMEOUT",budget::check);
        var fresh=deadline();Thread.currentThread().interrupt();try { code("REPORT_TIMEOUT",fresh::check); } finally { Thread.interrupted(); }fresh.check();
    }
    @Test void r4_resource_02ExactOutputLimitPlusOneAndActualCumulativeLiveWorkspace() throws Exception {
        try(var workspace=new ReportWorkspace(parent,deadline(),ReportBudget.WORKSPACE)) {
            var output=workspace.file("output-",".xlsx");try(var stream=workspace.output(output,ReportBudget.OUTPUT)) { var block=new byte[8192];for(int n=0;n<2048;n++) stream.write(block);assertEquals(ReportBudget.OUTPUT,Files.size(output));code("REPORT_TOO_LARGE",() -> stream.write(1)); }
            var other=workspace.file("other-",".tmp");long before=workspace.measure();try(var stream=workspace.output(other,ReportBudget.WORKSPACE)) {
                long remaining=ReportBudget.WORKSPACE-before;byte[] block=new byte[8192];while(remaining>0) { int count=(int)Math.min(block.length,remaining);stream.write(block,0,count);remaining-=count; }
                assertEquals(ReportBudget.WORKSPACE,workspace.measure());code("REPORT_TOO_LARGE",() -> stream.write(1));assertEquals(ReportBudget.WORKSPACE,workspace.peakBytes());
            }
            Files.delete(other);assertTrue(workspace.measure()<ReportBudget.WORKSPACE);
        }assertEquals(0,entryCount(parent));
    }
    @Test void r4_resource_03PrivateOwnedTempFilesAndStartupCleanupPreservesActiveForeignAndLinks() throws Exception {
        var untouched=Files.createDirectory(parent.resolve("unrelated"));Files.writeString(untouched.resolve("keep"),"Unrelated user file");
        var falseOwner=Files.createDirectory(parent.resolve("export-false"));Files.writeString(falseOwner.resolve("ownership"),"Not an export");
        try(var active=new ReportWorkspace(parent,deadline(),ReportBudget.WORKSPACE)) {
            var path=active.file("poi-",".gz");assertPrivate(active.directory,true);assertPrivate(path,false);
            var abandoned=Files.createDirectory(parent.resolve("export-abandoned"));Files.writeString(abandoned.resolve("ownership"),"Owlibi professor export v1\n");Files.writeString(abandoned.resolve("sheet.gz"),"abandoned synthetic sheet");
            Files.createSymbolicLink(parent.resolve("export-link"),untouched);ReportWorkspace.cleanupAbandoned(parent);assertFalse(Files.exists(abandoned));assertTrue(Files.exists(active.directory));assertTrue(Files.exists(untouched.resolve("keep")));assertTrue(Files.exists(falseOwner));assertTrue(Files.isSymbolicLink(parent.resolve("export-link")));
        }assertTrue(Files.exists(untouched.resolve("keep")));
    }
    @Test void r4_resource_04Poi551WindowCompressedSharedStringsAndCloseDisposeReallyDeleteSheets() throws Exception {
        var deadline=deadline();try(var workspace=new ReportWorkspace(parent,deadline,ReportBudget.WORKSPACE)) {
            var w=new SXSSFWorkbook(null,100,true,true);assertEquals(100,w.getRandomAccessWindowSize());assertTrue(w.isCompressTempFiles());var s=w.createSheet("Synthetic");for(int n=0;n<201;n++) s.createRow(n).createCell(0).setCellValue("=literal "+n);
            List<Path> paths;try(var files=Files.list(workspace.directory)) { paths=files.filter(p -> p.getFileName().toString().endsWith(".gz")).toList(); }assertEquals(1,paths.size());w.close();for(var path:paths) assertFalse(Files.exists(path));assertFalse(w.dispose());
        }
    }
    @Test void r4_resource_05EveryLongColumnScalarContinuationReconstructsWithoutNumericDuplication() throws Exception {
        String longText="😀𐐀=+-@ <>& _x0041_\n".repeat(2500);String edge="x".repeat(29999)+"😀"+"y".repeat(30000);
        assertEquals(edge,String.join("",ReportWorkbook.chunks(edge)));assertEquals(29999,ReportWorkbook.chunks(edge).getFirst().length());
        var capture=snapshot(longText,longText,longText);var deadline=deadline();try(var workspace=new ReportWorkspace(parent,deadline,ReportBudget.WORKSPACE)) {
            var file=new ReportWorkbook().generate(capture,workspace,deadline);double ratio=org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio();org.apache.poi.openxml4j.util.ZipSecureFile.setMinInflateRatio(0);try(var stream=Files.newInputStream(file);var w=new XSSFWorkbook(stream)) {
                var tasks=w.getSheet("Task details");var description=new StringBuilder();int numbers=0;for(int n=7;n<=tasks.getLastRowNum();n++) { var row=tasks.getRow(n);description.append(row.getCell(6).getStringCellValue());if(row.getCell(3).getCellType()==CellType.NUMERIC) numbers++;else assertEquals(CellType.BLANK,row.getCell(3).getCellType());assertEquals("9007199254740993",row.getCell(7).getStringCellValue());assertEquals("0001-01-01",row.getCell(2).getStringCellValue()); }
                assertEquals(longText,description.toString());assertEquals(1,numbers);
                var summary=w.getSheetAt(0);var reconstruction=new StringBuilder();for(var row:summary) if(row.getCell(0).getStringCellValue().equals("Project description")) reconstruction.append(row.getCell(1).getStringCellValue());assertEquals(longText,reconstruction.toString());
                var contribution=w.getSheet("Member contributions");int totals=0;for(var row:contribution) if(row.getCell(4)!=null && row.getCell(4).toString().equals(capture.members().getFirst().id().toString()) && row.getCell(1).getCellType()==CellType.NUMERIC) totals++;assertEquals(1,totals);
            } finally { org.apache.poi.openxml4j.util.ZipSecureFile.setMinInflateRatio(ratio); }
        }
    }
    @Test void r4_resource_06ExactDeterministicUtf8CaptureAccountingIncludingEscapesAndLimitPlusOne() {
        var budget=new CaptureBudget(deadline());var value=Map.of("text","😀\u0001<>&\n");long size=CanonicalState.json(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        budget.add("project",value);budget.add("members",value);budget.add("members",value);assertEquals(12+3*size+1,budget.bytes());
        var exact=new CaptureBudget(deadline());String text="a".repeat((int)ReportBudget.TEXT-14);exact.add("project",text);assertEquals(ReportBudget.TEXT,exact.bytes());code("REPORT_TOO_LARGE",() -> exact.add("settings",1));
    }
    @Test void r4_resource_07FailedAndTimedOutWorkbookCloseOwnedStreamsAndDirectory() throws Exception {
        code("REPORT_TOO_LARGE",() -> new ReportWorkspace(parent,deadline(),0));
        assertEquals(0,entryCount(parent));assertThrows(NullPointerException.class,ReportWorkspace::current);
        Path directory;var tick=new AtomicLong();var deadline=new ReportBudget(Duration.ofSeconds(20),tick::get);
        try(var workspace=new ReportWorkspace(parent,deadline,ReportBudget.WORKSPACE)) { directory=workspace.directory;tick.set(20_000_000_000L);code("REPORT_TIMEOUT",() -> new ReportWorkbook().generate(snapshot("Synthetic","description","title"),workspace,deadline)); }
        assertFalse(Files.exists(directory));assertEquals(0,entryCount(parent));
    }
    @Test void r4_resource_09ExactWorksheetDataRowBoundaryAndLimitPlusOne() {
        var limit=new ReportWorkbook.RowBudget();for(int n=0;n<100000;n++) limit.add();code("REPORT_TOO_LARGE",limit::add);
    }
    @Test void r4_resource_08OwnedSymlinkParentRefusesWithoutTraversingOrDeletingUserContent() throws Exception {
        var user=Files.createDirectory(parent.resolve("user"));Files.writeString(user.resolve("keep"),"unrelated");var link=parent.resolve("link");Files.createSymbolicLink(link,user);
        assertThrows(IOException.class,() -> new ReportWorkspace(link,deadline(),ReportBudget.WORKSPACE));assertEquals("unrelated",Files.readString(user.resolve("keep")));
    }
    @Test void r4_resource_10PackagedLibrariesAttributionPublicAssetAndFixtureBoundaries() throws Exception {
        var jar=Path.of("target/server-api-0.1.0-SNAPSHOT.jar");assertTrue(Files.isRegularFile(jar));var libraries=new ArrayList<Map<String,Object>>();
        try(var packageZip=new java.util.zip.ZipFile(jar.toFile())) {
            for(String notice:List.of("POI-LICENSE.txt","POI-NOTICE.txt","curvesapi-LICENSE.txt","SparseBitSet-1.3.pom","curvesapi-1.08.pom","R4-ATTRIBUTION.txt")) {
                var entry=packageZip.getEntry("BOOT-INF/classes/licenses/"+notice);assertNotNull(entry,notice);try(var stream=packageZip.getInputStream(entry)) { assertArrayEquals(Files.readAllBytes(Path.of("src/main/resources/licenses/"+notice)),stream.readAllBytes()); }
            }
            var entries=packageZip.stream().map(java.util.zip.ZipEntry::getName).toList();var publicAssets=entries.stream().filter(n -> n.startsWith("BOOT-INF/classes/static/") && !n.endsWith("/")).toList();assertEquals(8,publicAssets.size());
            for(String path:publicAssets) { var source=Path.of("../web-client/src/"+path.substring("BOOT-INF/classes/static/".length()));try(var stream=packageZip.getInputStream(packageZip.getEntry(path))) { assertArrayEquals(Files.readAllBytes(source),stream.readAllBytes()); } }
            for(String name:entries) { assertFalse(name.contains("ProfessorReportIT"));assertFalse(name.contains("RoutingBoundaryIT"));assertFalse(name.contains("WorkbookFaults"));assertFalse(name.contains("ReportResourceIT"));assertFalse(name.contains("R3ManualFixture"));assertFalse(name.contains(".env"));assertFalse(name.contains("r4-captures"));assertFalse(name.contains(".cache")); }
            for(String name:entries.stream().filter(n -> n.startsWith("BOOT-INF/lib/") && n.endsWith(".jar")).toList()) {
                var notices=new ArrayList<String>();try(var bytes=packageZip.getInputStream(packageZip.getEntry(name));var nested=new java.util.zip.ZipInputStream(bytes)) { java.util.zip.ZipEntry entry;while((entry=nested.getNextEntry())!=null) if(entry.getName().toLowerCase(Locale.ROOT).matches(".*(license|notice|copying).*")) notices.add(entry.getName()); }
                boolean pomOnly=notices.isEmpty();
                if(pomOnly) assertNotNull(packageZip.getEntry("BOOT-INF/classes/licenses/"+name.substring("BOOT-INF/lib/".length(),name.length()-4)+".pom"),name);libraries.add(Map.of("jar",name,"embeddedNotices",notices,"pomOnlyAttribution",pomOnly));
            }
            for(String pin:List.of("poi-5.5.1.jar","poi-ooxml-5.5.1.jar","poi-ooxml-lite-5.5.1.jar","commons-io-2.21.0.jar","xmlbeans-5.3.0.jar","commons-compress-1.28.0.jar","log4j-api-2.25.5.jar","tomcat-embed-core-11.0.26.jar","jackson-databind-3.1.7.jar")) assertNotNull(packageZip.getEntry("BOOT-INF/lib/"+pin),pin);
        }
        assertEquals(63,libraries.size());Files.createDirectories(Path.of("target/r4-captures"));Files.writeString(Path.of("target/r4-captures/package-review.json"),dev.owlibi.reference.support.ApiClient.json(Map.of("runtimeJarCount",libraries.size(),"runtimeLibraries",libraries,"publicAssetCount",8,"fixtureAndPrivatePathsExcluded",true,"pomOnlyNoticesRetained",true)));
    }

    @Test void r4_resource_11CompleteZipRequiresDirectoryEndRecordAndMatchingLocalMetadata() throws Exception {
        var workbook=new ReportWorkbook();var budget=deadline();
        try(var workspace=new ReportWorkspace(parent,budget,ReportBudget.WORKSPACE)) {
            var valid=workbook.generate(snapshot("Synthetic","Description","Literal =text"),workspace,budget);workbook.verifyComplete(valid,budget);
            var bytes=Files.readAllBytes(valid);int central=WorkbookFaults.directoryOffset(bytes);
            var missing=workspace.file("missing-directory-",".xlsx");Files.write(missing,Arrays.copyOf(bytes,central));
            assertThrows(IOException.class,()->workbook.verifyComplete(missing,budget));
            var partial=workspace.file("partial-end-",".xlsx");Files.write(partial,Arrays.copyOf(bytes,bytes.length-10));
            assertThrows(IOException.class,()->workbook.verifyComplete(partial,budget));
            var corrupt=workspace.file("corrupt-directory-",".xlsx");var bad=bytes.clone();bad[central]=0;Files.write(corrupt,bad);
            assertThrows(IOException.class,()->workbook.verifyComplete(corrupt,budget));
            var mismatch=workspace.file("mismatched-crc-",".xlsx");bad=bytes.clone();bad[central+16]^=1;Files.write(mismatch,bad);
            code("REPORT_UNAVAILABLE",()->workbook.verifyComplete(mismatch,budget));
            workbook.verifyComplete(valid,budget);
        }
        try(var files=Files.list(parent)) { assertEquals(0,files.count()); }
    }

}
