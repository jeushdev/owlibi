package dev.owlibi.reference.reports;

import dev.owlibi.reference.snapshots.*;
import dev.owlibi.reference.summaries.*;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.*;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.openxml4j.util.ZipFileZipEntrySource;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Professor report from one detached snapshot. Display values do not feed authoritative arithmetic. */
@Component
public class ReportWorkbook {
    private record NumberValue(String value,String format) {}
    private static NumberValue estimate(String value) { return new NumberValue(value,"0.00"); }
    private static NumberValue display(String value) { return value==null?null:new NumberValue(value,"0.0"); }
    private static String rounded(String value) { return new BigDecimal(value).setScale(1,RoundingMode.HALF_UP).toPlainString(); }
    private static String text(Object value) { return value==null?"":value.toString(); }
    public static List<String> chunks(String value) {
        if(value.isEmpty()) return List.of("");var result=new ArrayList<String>();
        for(int start=0;start<value.length();) {
            int end=start,encoded=0;
            while(end<value.length()) {
                int scalar=value.codePointAt(end),units=Character.charCount(scalar);
                int cost=scalar<32 && scalar!=9 && scalar!=10 && scalar!=13?7:escapePrefix(value,end)?7:units;
                if(encoded+cost>ReportBudget.CELL_UNITS) break;encoded+=cost;end+=units;
            }
            result.add(value.substring(start,end));start=end;
        } return List.copyOf(result);
    }
    private static boolean escapePrefix(String value,int index) {
        if(value.charAt(index)!='_' || index+6>=value.length() || value.charAt(index+1)!='x' || value.charAt(index+6)!='_') return false;
        for(int n=index+2;n<index+6;n++) if(Character.digit(value.charAt(n),16)<0) return false;return true;
    }
    private static String ooxmlText(String value) {
        var encoded=new StringBuilder();for(int index=0;index<value.length();index++) {
            char ch=value.charAt(index);
            if(ch<32 && ch!=9 && ch!=10 && ch!=13) encoded.append(String.format(Locale.ROOT,"_x%04X_",(int)ch));
            else if(escapePrefix(value,index)) encoded.append("_x005F_");else encoded.append(ch);
        }return encoded.toString();
    }
    static final class RowBudget {
        private int rows;
        void add() { if(++rows>ReportBudget.ROWS) throw ReportBudget.tooLarge(); }
    }
    private static final class Streaming extends SXSSFWorkbook {
        Streaming() { super(null,100,true,true);setZip64Mode(org.apache.commons.compress.archivers.zip.Zip64Mode.AsNeeded); }
        @Override protected SheetDataWriter createSheetDataWriter() throws IOException {
            return new GZIPSheetDataWriter(getSharedStringSource()) {
                @Override protected OutputStream decorateOutputStream(FileOutputStream stream) throws IOException {
                    return new GZIPOutputStream(ReportWorkspace.current().bounded(stream,ReportBudget.WORKSPACE));
                }
            };
        }
        @Override public void write(OutputStream output) throws IOException {
            flushSheets();var workspace=ReportWorkspace.current();Path template=workspace.file("template-",".xlsx");
            try {
                try(var stream=workspace.output(template,ReportBudget.WORKSPACE)) { getXSSFWorkbook().write(stream); }
                // This archive was generated here; no untrusted workbook is imported or parsed.
                try(var zip=ZipFile.builder().setPath(template).get();var entries=new ZipFileZipEntrySource(zip)) { injectData(entries,output); }
            } finally { Files.deleteIfExists(template); }
        }
    }
    private enum SheetSpec {
        PROJECT("Project summary", new int[]{30, 91, 15, 10}),
        CONTRIBUTIONS("Member contributions", new int[]{35, 18, 26, 45, 39, 10}, 4),
        TASKS("Task details", new int[]{36, 17, 14, 16, 47, 21, 59, 15, 58, 14, 25, 46, 25, 25, 39, 17, 10}, 14, 15),
        WORKLOAD("Workload report", new int[]{36, 19, 17, 17, 20, 45, 18, 39, 10}, 7),
        HISTORY("Activity history", new int[]{25, 23, 26, 39, 66, 27, 39, 39, 39, 10}, 6, 7, 8),
        RULES("Project rules", new int[]{30, 38, 62, 10});

        final String label;
        final int[] widths, auditColumns;

        SheetSpec(String label, int[] widths, int... auditColumns) {
            this.label=label;
            this.widths=widths;
            this.auditColumns=auditColumns;
        }
    }

    private enum Tone { TITLE, SUBTITLE, HEADER, KEY, EVEN, ODD, EVEN_ONE, ODD_ONE, EVEN_TWO, ODD_TWO, EVEN_INTEGER, ODD_INTEGER }

    private static final class Styles {
        final EnumMap<Tone,CellStyle> cells=new EnumMap<>(Tone.class);

        Styles(Streaming book) {
            var dark=font(book, "172F4D", 11, false);
            var muted=font(book, "3F5876", 10, false);
            var strong=font(book, "172F4D", 11, true);
            var white=font(book, "FFFFFF", 11, true);
            var title=font(book, "FFFFFF", 19, true);
            put(book,Tone.TITLE,title,"142B49",null);
            put(book,Tone.SUBTITLE,muted,"E9F1F9",null);
            put(book,Tone.HEADER,white,"265580",null);
            put(book,Tone.KEY,strong,"E9F1F9",null);
            put(book,Tone.EVEN,dark,"FFFFFF",null);
            put(book,Tone.ODD,dark,"F2F6FB",null);
            put(book,Tone.EVEN_ONE,dark,"FFFFFF","0.0");
            put(book,Tone.ODD_ONE,dark,"F2F6FB","0.0");
            put(book,Tone.EVEN_TWO,dark,"FFFFFF","0.00");
            put(book,Tone.ODD_TWO,dark,"F2F6FB","0.00");
            put(book,Tone.EVEN_INTEGER,dark,"FFFFFF","0");
            put(book,Tone.ODD_INTEGER,dark,"F2F6FB","0");
        }

        private static XSSFColor color(String rgb) { return new XSSFColor(HexFormat.of().parseHex(rgb)); }

        private static org.apache.poi.xssf.usermodel.XSSFFont font(Streaming book,String rgb,int size,boolean bold) {
            var font=book.getXSSFWorkbook().createFont();
            font.setFontName("Calibri");
            font.setFontHeightInPoints((short)size);
            font.setColor(color(rgb));
            font.setBold(bold);
            return font;
        }

        private void put(Streaming book,Tone tone,Font font,String rgb,String numberFormat) {
            var style=(XSSFCellStyle)book.createCellStyle();
            style.setFont(font);
            style.setFillForegroundColor(color(rgb));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setWrapText(true);
            style.setVerticalAlignment(tone==Tone.TITLE || tone==Tone.HEADER ? VerticalAlignment.CENTER : VerticalAlignment.TOP);
            if(numberFormat!=null) {
                style.setDataFormat(book.createDataFormat().getFormat(numberFormat));
                style.setAlignment(HorizontalAlignment.RIGHT);
            }
            cells.put(tone,style);
        }
    }

    private static final class Table {
        final SXSSFSheet sheet;
        final SheetSpec spec;
        final ReportBudget budget;
        final Styles styles;
        final RowBudget rowBudget=new RowBudget();
        int row, stripe;

        Table(Streaming book,SheetSpec spec,ReportBudget budget,Styles styles) {
            this.spec=spec;
            this.budget=budget;
            this.styles=styles;
            sheet=book.createSheet(spec.label);
            sheet.setDisplayGridlines(false);
            sheet.setTabColor(Styles.color(spec==SheetSpec.PROJECT?"142B49":"265580"));
            for(int col=0;col<spec.widths.length;col++) sheet.setColumnWidth(col,spec.widths[col]*256);
            for(int col:spec.auditColumns) sheet.setColumnHidden(col,true);
        }

        void row(Object... values) { rows(false,values); }
        int header(String... values) {
            int index=row;
            rows(true,(Object[])values);
            stripe=0;
            if(sheet.getPaneInformation()==null) sheet.createFreezePane(0,row);
            return index;
        }
        void filter(int first,int last,int columns) {
            sheet.setAutoFilter(new CellRangeAddress(first,Math.max(first,last),0,columns-1));
        }
        void band(String text,Tone tone,int height) {
            budget.check();
            int last=Math.min(spec.widths.length-2,4);
            var line=sheet.createRow(row++);
            line.setHeightInPoints(height);
            for(int col=0;col<=last;col++) line.createCell(col).setCellStyle(styles.cells.get(tone));
            line.getCell(0).setCellValue(text);
            sheet.addMergedRegion(new CellRangeAddress(line.getRowNum(),line.getRowNum(),0,last));
        }
        void metadata(String key,String value) { labeledRow(key,value,18); }
        void note(String key,String value) {
            rowBudget.add();
            labeledRow(key,value,22);
        }

        private void labeledRow(String key,String value,int minimumHeight) {
            budget.check();
            int last=1, width=spec.widths[last];
            while(width<96 && last<spec.widths.length-2 && !sheet.isColumnHidden(last+1)) width+=spec.widths[++last];
            var line=sheet.createRow(row++);
            line.setHeightInPoints(Math.max(minimumHeight,4+14*wrappedLines(value,width)));
            var label=line.createCell(0);
            label.setCellStyle(styles.cells.get(Tone.KEY));
            label.setCellValue(key);
            for(int col=1;col<=last;col++) line.createCell(col).setCellStyle(styles.cells.get(Tone.EVEN));
            line.getCell(1).setCellValue(value);
            if(last>1) sheet.addMergedRegion(new CellRangeAddress(line.getRowNum(),line.getRowNum(),1,last));
        }

        private void rows(boolean heading,Object[] values) {
            budget.check();
            var parts=new ArrayList<List<String>>();
            int count=1;
            for(var value:values) {
                var split=chunks(value instanceof NumberValue || value instanceof Number ? "" : text(value));
                parts.add(split);
                count=Math.max(count,split.size());
            }
            boolean alternate=stripe++%2!=0;
            Tone plain=heading?Tone.HEADER:alternate?Tone.ODD:Tone.EVEN;
            for(int part=0;part<count;part++) {
                budget.check();
                if(!heading) rowBudget.add();
                var line=sheet.createRow(row++);
                int lines=1;
                for(int col=0;col<values.length;col++) {
                    var cell=line.createCell(col);
                    var value=values[col];
                    cell.setCellStyle(styles.cells.get(plain));
                    if(value instanceof NumberValue number) {
                        if(part==0) {
                            cell.setCellValue(new BigDecimal(number.value()).doubleValue());
                            Tone numeric=number.format().equals("0.0")
                                ? (alternate?Tone.ODD_ONE:Tone.EVEN_ONE) : (alternate?Tone.ODD_TWO:Tone.EVEN_TWO);
                            cell.setCellStyle(styles.cells.get(numeric));
                        }
                    } else if(value instanceof Number number) {
                        if(part==0) {
                            cell.setCellValue(number.doubleValue());
                            cell.setCellStyle(styles.cells.get(alternate?Tone.ODD_INTEGER:Tone.EVEN_INTEGER));
                        }
                    } else if(value!=null) {
                        // Keep short context on continuation rows. Split fields concatenate in Part order.
                        var split=parts.get(col);
                        String content=split.size()==1?split.getFirst():part<split.size()?split.get(part):"";
                        cell.setCellValue(ooxmlText(content));
                        if(!sheet.isColumnHidden(col)) lines=Math.max(lines,wrappedLines(content,spec.widths[col]));
                    }
                }
                line.setHeightInPoints(heading?32:Math.max(22,Math.min(120,8+14*lines)));
                if(!heading) {
                    var cell=line.createCell(values.length);
                    cell.setCellStyle(styles.cells.get(plain));
                    cell.setCellValue((part+1)+"/"+count);
                }
            }
        }

        private static int wrappedLines(String text,int width) {
            int limit=Math.max(8,width-4), lines=1, chars=0;
            for(int i=0;i<text.length() && lines<8;i++) {
                char ch=text.charAt(i);
                if(ch=='\n' || ++chars>limit) {
                    lines++;
                    chars=ch=='\n'?0:1;
                }
            }
            return lines;
        }
    }

    private static Table sheet(Streaming book,SheetSpec spec,String note,ProjectSnapshot s,ReportBudget budget,Styles styles) {
        var table=new Table(book,spec,budget,styles);
        table.band(spec==SheetSpec.PROJECT?"Owlibi  |  Professor report":spec.label,Tone.TITLE,36);
        table.band(note,Tone.SUBTITLE,34);
        table.metadata("Snapshot UTC",text(s.snapshot().snapshotAtUtc()));
        table.metadata("Project zone / date",s.settings().projectZone()+" / "+s.projectToday());
        table.metadata("State token",s.snapshot().stateToken());
        table.metadata("Effective settings version",s.settings().settingsVersion());
        return table;
    }

    private static String identity(ProjectSnapshot s,UUID id) {
        return s.members().stream().filter(m -> m.id().equals(id)).findFirst().map(m -> m.displayName()+" ("+id+")").orElseThrow(ReportBudget::unavailable);
    }
    private static String assignees(ProjectSnapshot s,List<UUID> ids) { return String.join("\n",ids.stream().map(id -> identity(s,id)).toList()); }
    public void verifyComplete(Path complete,ReportBudget budget) throws IOException {
        budget.check();
        var expected=new HashSet<>(Set.of("[Content_Types].xml","_rels/.rels","docProps/app.xml","docProps/core.xml",
            "xl/sharedStrings.xml","xl/styles.xml","xl/workbook.xml","xl/_rels/workbook.xml.rels"));
        for(int sheet=1;sheet<=SheetSpec.values().length;sheet++) expected.add("xl/worksheets/sheet"+sheet+".xml");
        var seen=new HashSet<String>();byte[] buffer=new byte[8192];
        // ZipInputStream alone accepts local records without a central directory/end record.
        // ZipFile requires that structure; retain streaming CRC checks and compare both views.
        try(var directory=new java.util.zip.ZipFile(complete.toFile());
            var stream=Files.newInputStream(complete);var zip=new java.util.zip.ZipInputStream(stream)) {
            if(directory.size()!=expected.size()) throw ReportBudget.unavailable();
            var indexed=new HashMap<String,java.util.zip.ZipEntry>();
            var entries=directory.entries();
            while(entries.hasMoreElements()) {
                budget.check();var entry=entries.nextElement();
                if(entry.isDirectory() || !expected.contains(entry.getName()) || indexed.put(entry.getName(),entry)!=null)
                    throw ReportBudget.unavailable();
            }
            java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null) {
                budget.check();if(!expected.contains(entry.getName()) || !seen.add(entry.getName())) throw ReportBudget.unavailable();
                while(zip.read(buffer)!=-1) budget.check();zip.closeEntry();
                var index=indexed.get(entry.getName());
                if(index==null || index.getSize()!=entry.getSize() || index.getCompressedSize()!=entry.getCompressedSize()
                    || index.getCrc()!=entry.getCrc() || index.getMethod()!=entry.getMethod()) throw ReportBudget.unavailable();
            }
        }
        if(!seen.equals(expected)) throw ReportBudget.unavailable();budget.check();
    }
    public Path generate(ProjectSnapshot s,ReportWorkspace workspace,ReportBudget budget) throws IOException {
        if(s.history()==null || s.history().count()!=s.history().events().size() || !s.settings().configured()) throw ReportBudget.unavailable();
        SummaryDtos.Contribution contribution;
        SummaryDtos.Workload workload;
        try {
            contribution=SummaryCalculator.contribution(s);
            workload=SummaryCalculator.workload(s);
        } catch(dev.owlibi.reference.web.ApiFailure invalid) {
            throw ReportBudget.unavailable();
        }
        Path finalFile=workspace.file("complete-",".xlsx");
        var book=new Streaming();
        try {
            var styles=new Styles(book);
            var properties=book.getXSSFWorkbook().getProperties().getCoreProperties();
            properties.setTitle("Owlibi Professor Report");
            properties.setCreator("Owlibi");
            properties.setLastModifiedByUser("Owlibi");
            projectSummary(book,s,contribution,budget,styles);
            memberContributions(book,s,contribution,budget,styles);
            taskDetails(book,s,budget,styles);
            workloadReport(book,s,workload,budget,styles);
            activityHistory(book,s,budget,styles);
            projectRules(book,s,budget,styles);
            try(var stream=workspace.output(finalFile,ReportBudget.OUTPUT)) { book.write(stream); }
            budget.check();workspace.measure();return finalFile;
        } finally {
            // POI 5.5.1 close closes sheet writers/disposes sheets; explicit dispose is also safe/idempotent.
            try { book.close(); } finally { try { book.dispose(); } finally { book.getXSSFWorkbook().close(); } }
        }
    }

    private static void projectSummary(Streaming book,ProjectSnapshot s,SummaryDtos.Contribution contribution,ReportBudget budget,Styles styles) {
        var summary=sheet(book,SheetSpec.PROJECT,"Project overview, approved completed points, and the scope of this snapshot.",s,budget,styles);
        int header=summary.header("Project information","Value","Part");
        summary.row("Project name",s.project().name());
        summary.row("Project description",s.project().description());
        var leader=s.members().stream().filter(m -> m.role().equals("LEADER")).findFirst().orElseThrow(ReportBudget::unavailable);
        summary.row("Leader",leader.displayName());
        summary.row("Completed total",display(contribution.totalCompletedPointsDisplay()));
        summary.row("Contribution status",contribution.empty()?"No completed tasks yet":"Based on approved Done tasks");
        for(String status:List.of("TODO","IN_PROGRESS","REVIEW","DONE","CANCELLED")) {
            summary.row("Task count "+taskStatus(status),s.tasks().stream().filter(t -> t.status().equals(status)).count());
        }
        summary.row("Created UTC",s.project().createdAtUtc());
        summary.row("Project UUID",s.project().id());
        summary.row("Leader UUID",leader.id());
        var stored=JsonMapper.shared().readTree(s.templateJson());
        var template=stored.has("template")?stored.get("template"):stored;
        summary.row("Template ID",template.get("id").asText());
        summary.row("Template version",template.get("version").asText());
        summary.row("History count",s.history().count());
        summary.row("History hash",s.history().hash());
        summary.row("Scope","All tasks and all safe activity visible in one read-only PostgreSQL REPEATABLE_READ snapshot. Snapshot UTC is an observation time, not a commit timestamp cutoff.");
        summary.row("Limits","1000 tasks; 10000 events; 100000 data rows per sheet; 32 MiB captured JSON; 16 MiB XLSX; 64 MiB live workspace; 10 seconds capture; 20 seconds generation. Complete history or explicit refusal.");
        summary.row("Formula",contribution.formula());
        for(var limit:contribution.limitations()) summary.row("Limitation",limit);
        summary.row("Effective project rules","See the Project rules worksheet for the scale, examples and thresholds.");
        summary.row("Audit columns","Unhide the columns before Part on Member contributions, Task details, Workload report, or Activity history to see the original UUIDs. For split records, read the parts in order.");
        summary.filter(header,summary.row-1,3);
        summary.header("Member","Member UUID","Role","Part");
        for(var member:s.members()) summary.row(member.displayName(),member.id(),member.role());
    }

    private static void memberContributions(Streaming book,ProjectSnapshot s,SummaryDtos.Contribution c,ReportBudget budget,Styles styles) {
        var members=sheet(book,SheetSpec.CONTRIBUTIONS,"Approved Done tasks only. Blank shares mean the project has no completed points.",s,budget,styles);
        int header=members.header("Member","Completed points","Completed effort-points share (%)","State","Member UUID","Part");
        for(var member:c.members()) members.row(member.displayName(),display(member.completedPointsDisplay()),display(member.sharePercentDisplay()),
            c.empty()?"No completed tasks yet":"Completed effort-points share",member.userId());
        members.filter(header,members.row-1,6);
        members.note("Formula",c.formula());
        for(var limit:c.limitations()) members.note("Limitation",limit);
        members.note("Allocation display values","Rounded allocation displays may not sum to the authoritative total, which uses unrounded service allocations.");
        members.header("Completed task","Assigned effort points","Per-assignee completed points","Assignees","Task UUID","Part");
        for(var allocation:c.allocations()) members.row(allocation.title(),estimate(allocation.effortPoints()),
            display(rounded(allocation.perAssigneePoints())),assignees(s,allocation.assigneeIds()),allocation.taskId());
    }

    private static void taskDetails(Streaming book,ProjectSnapshot s,ReportBudget budget,Styles styles) {
        var tasks=sheet(book,SheetSpec.TASKS,s.tasks().isEmpty()?"No tasks in this snapshot."
            :"All statuses. Due dates use the project calendar. Approval records are in Activity history.",s,budget,styles);
        int header=tasks.header("Title","Status","Due date","Assigned effort points","Assignees","Category","Description",
            "Agreement version","Current acceptance","Task version","First started UTC","Created by","Created UTC","Updated UTC","Task UUID","Status code","Part");
        for(var task:s.tasks()) {
            String acceptance=String.join("\n",task.assignees().stream().map(assignment -> {
                boolean accepted=Objects.equals(assignment.acceptedAgreementVersion(),task.agreementVersion());
                return identity(s,assignment.userId())+"; accepted="+accepted+"; accepted UTC="+text(accepted?assignment.acceptedAtUtc():null);
            }).toList());
            tasks.row(task.title(),taskStatus(task.status()),task.dueDate(),estimate(task.effortPoints()),
                assignees(s,task.assignees().stream().map(ProjectSnapshot.Assignment::userId).toList()),task.category(),task.description(),
                Long.toString(task.agreementVersion()),acceptance,Long.toString(task.version()),task.firstStartedAtUtc(),
                identity(s,task.createdBy()),task.createdAtUtc(),task.updatedAtUtc(),task.id(),task.status());
        }
        tasks.filter(header,tasks.row-1,17);
    }

    private static void workloadReport(Streaming book,ProjectSnapshot s,SummaryDtos.Workload workload,ReportBudget budget,Styles styles) {
        var table=sheet(book,SheetSpec.WORKLOAD,"Remaining points are separate from approved contributions. Review tasks still count toward date warnings.",s,budget,styles);
        int header=table.header("Member","Remaining points","Open tasks","Overdue tasks","Near-due tasks","Workload warning","Role","Member UUID","Part");
        for(var member:workload.members()) table.row(member.displayName(),display(member.remainingPointsDisplay()),member.openTaskIds().size(),
            member.overdueCount(),member.nearDueCount(),member.overloaded()?"Over threshold":"None",
            s.members().stream().filter(person -> person.id().equals(member.userId())).findFirst().orElseThrow().role(),member.userId());
        table.filter(header,table.row-1,9);
        var counts=workload.projectCounts();
        table.header("Project totals","Open tasks","In review","Overdue","Near due","Part");
        table.row("Tasks",counts.openTasks(),counts.reviewTasks(),counts.overdueTasks(),counts.nearDueTasks());
        table.row("Overload threshold",estimate(s.settings().overloadThreshold()));
        table.row("Near-due window (days)",s.settings().nearDueDays());
        table.note("Formula",workload.formula());
        for(var limit:workload.limitations()) table.note("Limitation",limit);
        table.header("Task","Status","Due date","Assigned effort points","Remaining per assignee","Assignees","Due status","Task UUID","Part");
        for(var task:workload.tasks()) table.row(task.title(),taskStatus(task.status()),task.dueDate(),estimate(task.effortPoints()),
            display(rounded(task.perAssigneeRemainingPoints())),assignees(s,task.assigneeIds()),dueStatus(task.dateState()),task.taskId());
    }

    private static void activityHistory(Streaming book,ProjectSnapshot s,ReportBudget budget,Styles styles) {
        var history=sheet(book,SheetSpec.HISTORY,"Complete captured history, oldest first. Read split payloads in Part order.",s,budget,styles);
        int header=history.header("Occurred UTC","Action","Actor","Reason","Safe change summary","Event type","Task UUID","Actor UUID","Event UUID","Part");
        for(var event:s.history().events()) history.row(event.occurredAtUtc(),eventAction(event.eventType()),event.actorName(),event.reason(),
            event.changeSummary(),event.eventType(),event.taskId(),event.actorId(),event.id());
        history.filter(header,history.row-1,10);
    }

    private static void projectRules(Streaming book,ProjectSnapshot s,ReportBudget budget,Styles styles) {
        var table=sheet(book,SheetSpec.RULES,"Effective project settings in this snapshot. Effort points describe relative task size.",s,budget,styles);
        int header=table.header("Rule","Effective value","Meaning","Part");
        table.row("Effort scale",s.settings().effortScaleName(),"The project's scale for estimating task size.");
        table.row("Overload threshold",estimate(s.settings().overloadThreshold()),"Warning when unrounded remaining points exceed this value.");
        table.row("Near-due days",s.settings().nearDueDays(),"Includes today through today plus this many days.");
        table.row("Time zone",s.settings().projectZone(),"Used to determine the project date and due-date warnings.");
        table.filter(header,table.row-1,4);
        table.header("Effort points","Task size example","Part");
        for(var example:s.settings().effortScaleExamples()) table.row(estimate(example.points()),example.label());
    }

    private static String dueStatus(String status) {
        return switch(status) {
            case "OVERDUE" -> "Overdue";
            case "NEAR_DUE" -> "Near due";
            case "LATER" -> "Due later";
            case "NONE" -> "No due date";
            default -> status;
        };
    }

    private static String taskStatus(String status) {
        return switch(status) {
            case "TODO" -> "To do";
            case "IN_PROGRESS" -> "In progress";
            case "REVIEW" -> "In review";
            case "DONE" -> "Done";
            case "CANCELLED" -> "Cancelled";
            default -> status;
        };
    }

    private static String eventAction(String type) {
        return switch(type) {
            case "START" -> "Task started";
            case "SUBMIT_REVIEW" -> "Review requested";
            case "APPROVE" -> "Task approved";
            case "RETURN" -> "Returned for changes";
            case "REOPEN" -> "Task reopened";
            case "CANCEL" -> "Task cancelled";
            default -> {
                String label=type.toLowerCase(Locale.ROOT).replace('_',' ');
                yield label.isEmpty()?label:Character.toUpperCase(label.charAt(0))+label.substring(1);
            }
        };
    }

}
