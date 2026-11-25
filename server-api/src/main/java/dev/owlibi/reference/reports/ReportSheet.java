package dev.owlibi.reference.reports;

import dev.owlibi.reference.reports.ReportStyles.Tone;
import dev.owlibi.reference.snapshots.ProjectSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFRow;
import org.apache.poi.xssf.streaming.SXSSFSheet;

/**
 * One worksheet of the professor report.
 *
 * <p>The abstract class holds what every sheet shares: the tab, column widths and hidden audit columns,
 * the title band, the subtitle, the snapshot metadata rows, header rows, alternating row colours, splitting
 * long text into parts, and the per-sheet row limit. Each subclass only says what is different about its
 * sheet: its name, widths, subtitle and the rows it writes.
 *
 * <p>{@link #write} is a <em>template method</em>: it is {@code final}, so every sheet starts the same way,
 * and it then calls the {@link #writeRows()} hook that each subclass overrides.
 *
 * <p>The class is {@code sealed}: only the six sheets of the report can extend it.
 */
abstract sealed class ReportSheet implements ReportSection
    permits ProjectSummarySheet, MemberContributionsSheet, TaskDetailsSheet, WorkloadSheet,
        ActivityHistorySheet, ProjectRulesSheet {

    private static final String DEFAULT_TAB_COLOR = "265580";

    /** A decimal written as an Excel number with a fixed display format, {@code "0.0"} or {@code "0.00"}. */
    protected record NumberValue(String value, String format) {}

    private final String label;
    private final ProjectSnapshot snapshot;
    private final int[] columnWidths;
    private final int[] auditColumns;

    // Set when write starts. One sheet object writes exactly one worksheet.
    private SXSSFSheet sheet;
    private ReportStyles styles;
    private ReportBudget budget;
    private ReportWorkbook.RowBudget rowBudget;
    private int nextRow;
    private int stripe;

    /**
     * @param label the tab name
     * @param snapshot the project snapshot this sheet reads
     * @param columnWidths each column's width in characters
     * @param auditColumns the columns hidden by default because they hold raw UUIDs and codes
     */
    protected ReportSheet(String label, ProjectSnapshot snapshot, int[] columnWidths, int... auditColumns) {
        this.label = label;
        this.snapshot = snapshot;
        this.columnWidths = columnWidths.clone();
        this.auditColumns = auditColumns.clone();
    }

    @Override
    public final String label() {
        return label;
    }

    /** Creates the worksheet, writes the shared top rows, then lets the subclass write its own rows. */
    @Override
    public final void write(StreamingWorkbook book, ReportStyles styles, ReportBudget budget) {
        if (sheet != null) {
            throw new IllegalStateException("A report sheet is written only once");
        }
        this.styles = styles;
        this.budget = budget;
        this.rowBudget = new ReportWorkbook.RowBudget();
        sheet = book.createSheet(label);
        sheet.setDisplayGridlines(false);
        sheet.setTabColor(ReportStyles.color(tabColor()));
        for (int column = 0; column < columnWidths.length; column++) {
            sheet.setColumnWidth(column, columnWidths[column] * 256);
        }
        for (int column : auditColumns) {
            sheet.setColumnHidden(column, true);
        }
        band(title(), Tone.TITLE, 36);
        band(subtitle(), Tone.SUBTITLE, 34);
        metadata("Snapshot UTC", text(snapshot.snapshot().snapshotAtUtc()));
        metadata("Project zone / date", snapshot.settings().projectZone() + " / " + snapshot.projectToday());
        metadata("State token", snapshot.snapshot().stateToken());
        metadata("Effective settings version", snapshot.settings().settingsVersion());
        writeRows();
    }

    /** The short explanation under the title. */
    protected abstract String subtitle();

    /** Writes this sheet's tables using {@link #header}, {@link #row}, {@link #note} and {@link #filter}. */
    protected abstract void writeRows();

    /** The large title at the top of the sheet. By default it is the tab name. */
    protected String title() {
        return label;
    }

    /** The tab colour as a hex code. */
    protected String tabColor() {
        return DEFAULT_TAB_COLOR;
    }

    protected final ProjectSnapshot snapshot() {
        return snapshot;
    }

    /** Writes a header row, restarts the row colours, and freezes the panes below the first header. */
    protected final int header(String... values) {
        int headerRow = nextRow;
        writeLines(true, values);
        stripe = 0;
        if (sheet.getPaneInformation() == null) {
            sheet.createFreezePane(0, nextRow);
        }
        return headerRow;
    }

    /** Writes one data record. Long text is split over several rows, numbered in the last column. */
    protected final void row(Object... values) {
        writeLines(false, values);
    }

    /** Adds an Excel filter from the given header row down to the last row written so far. */
    protected final void filter(int headerRow, int columns) {
        int lastRow = nextRow - 1;
        sheet.setAutoFilter(new CellRangeAddress(headerRow, Math.max(headerRow, lastRow), 0, columns - 1));
    }

    /** Writes a key and a wide explanation, such as a formula or a limitation. */
    protected final void note(String key, String value) {
        rowBudget.add();
        labeledRow(key, value, 22);
    }

    /** A display value that may itself be missing. */
    protected static NumberValue display(String value) {
        return value == null ? null : new NumberValue(value, "0.0");
    }

    /** Effort points, shown with two decimals. */
    protected static NumberValue estimate(String value) {
        return new NumberValue(value, "0.00");
    }

    /** Rounds an exact service number to one decimal for display. */
    protected static String rounded(String value) {
        return new BigDecimal(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    protected static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    /** The readable name of a task status code. */
    protected static String taskStatus(String status) {
        return switch (status) {
            case "TODO" -> "To do";
            case "IN_PROGRESS" -> "In progress";
            case "REVIEW" -> "In review";
            case "DONE" -> "Done";
            case "CANCELLED" -> "Cancelled";
            default -> status;
        };
    }

    /** A member's name with their UUID, for example {@code "Ana (<member UUID>)"}. */
    protected final String identity(UUID memberId) {
        return snapshot.members().stream()
            .filter(member -> member.id().equals(memberId))
            .findFirst()
            .map(member -> member.displayName() + " (" + memberId + ")")
            .orElseThrow(ReportBudget::unavailable);
    }

    /** Several members, one per line. */
    protected final String assignees(List<UUID> memberIds) {
        return String.join("\n", memberIds.stream().map(this::identity).toList());
    }

    private void band(String content, Tone tone, int height) {
        budget.check();
        int lastColumn = Math.min(columnWidths.length - 2, 4);
        SXSSFRow line = sheet.createRow(nextRow++);
        line.setHeightInPoints(height);
        for (int column = 0; column <= lastColumn; column++) {
            line.createCell(column).setCellStyle(styles.get(tone));
        }
        line.getCell(0).setCellValue(content);
        sheet.addMergedRegion(new CellRangeAddress(line.getRowNum(), line.getRowNum(), 0, lastColumn));
    }

    private void metadata(String key, String value) {
        labeledRow(key, value, 18);
    }

    private void labeledRow(String key, String value, int minimumHeight) {
        budget.check();
        int lastColumn = 1;
        int width = columnWidths[lastColumn];
        while (width < 96 && lastColumn < columnWidths.length - 2 && !sheet.isColumnHidden(lastColumn + 1)) {
            lastColumn++;
            width += columnWidths[lastColumn];
        }
        SXSSFRow line = sheet.createRow(nextRow++);
        line.setHeightInPoints(Math.max(minimumHeight, 4 + 14 * wrappedLines(value, width)));
        Cell keyCell = line.createCell(0);
        keyCell.setCellStyle(styles.get(Tone.KEY));
        keyCell.setCellValue(key);
        for (int column = 1; column <= lastColumn; column++) {
            line.createCell(column).setCellStyle(styles.get(Tone.EVEN));
        }
        line.getCell(1).setCellValue(value);
        if (lastColumn > 1) {
            sheet.addMergedRegion(new CellRangeAddress(line.getRowNum(), line.getRowNum(), 1, lastColumn));
        }
    }

    private void writeLines(boolean heading, Object[] values) {
        budget.check();
        List<List<String>> parts = new ArrayList<>();
        int partCount = 1;
        for (Object value : values) {
            boolean numeric = value instanceof NumberValue || value instanceof Number;
            List<String> split = ReportWorkbook.chunks(numeric ? "" : text(value));
            parts.add(split);
            partCount = Math.max(partCount, split.size());
        }
        boolean alternate = stripe++ % 2 != 0;
        Tone plain = heading ? Tone.HEADER : alternate ? Tone.ODD : Tone.EVEN;
        for (int part = 0; part < partCount; part++) {
            budget.check();
            if (!heading) {
                rowBudget.add();
            }
            SXSSFRow line = sheet.createRow(nextRow++);
            int lines = 1;
            for (int column = 0; column < values.length; column++) {
                Cell cell = line.createCell(column);
                Object value = values[column];
                cell.setCellStyle(styles.get(plain));
                if (value instanceof NumberValue number) {
                    if (part == 0) {
                        cell.setCellValue(new BigDecimal(number.value()).doubleValue());
                        Tone numberTone = number.format().equals("0.0")
                            ? (alternate ? Tone.ODD_ONE : Tone.EVEN_ONE)
                            : (alternate ? Tone.ODD_TWO : Tone.EVEN_TWO);
                        cell.setCellStyle(styles.get(numberTone));
                    }
                } else if (value instanceof Number number) {
                    if (part == 0) {
                        cell.setCellValue(number.doubleValue());
                        cell.setCellStyle(styles.get(alternate ? Tone.ODD_INTEGER : Tone.EVEN_INTEGER));
                    }
                } else if (value != null) {
                    // Keep short context on continuation rows. Split fields concatenate in Part order.
                    List<String> split = parts.get(column);
                    String content = split.size() == 1 ? split.getFirst() : part < split.size() ? split.get(part) : "";
                    cell.setCellValue(ReportWorkbook.ooxmlText(content));
                    if (!sheet.isColumnHidden(column)) {
                        lines = Math.max(lines, wrappedLines(content, columnWidths[column]));
                    }
                }
            }
            line.setHeightInPoints(heading ? 32 : Math.max(22, Math.min(120, 8 + 14 * lines)));
            if (!heading) {
                Cell partCell = line.createCell(values.length);
                partCell.setCellStyle(styles.get(plain));
                partCell.setCellValue((part + 1) + "/" + partCount);
            }
        }
    }

    private static int wrappedLines(String content, int width) {
        int limit = Math.max(8, width - 4);
        int lines = 1;
        int chars = 0;
        for (int index = 0; index < content.length() && lines < 8; index++) {
            char character = content.charAt(index);
            if (character == '\n' || ++chars > limit) {
                lines++;
                chars = character == '\n' ? 0 : 1;
            }
        }
        return lines;
    }
}
