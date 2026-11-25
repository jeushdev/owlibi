package dev.owlibi.reference.reports;

import dev.owlibi.reference.snapshots.ProjectSnapshot;
import dev.owlibi.reference.summaries.SummaryCalculator;
import dev.owlibi.reference.summaries.SummaryDtos;
import dev.owlibi.reference.web.ApiFailure;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.apache.poi.ooxml.POIXMLProperties;
import org.springframework.stereotype.Component;

/**
 * Builds the professor's Excel report from one detached project snapshot.
 *
 * <p>The workbook is a list of {@link ReportSection}s. {@link #generate} asks each section to write itself
 * and does not know which sheet it is talking to. Display values do not feed authoritative arithmetic.
 */
@Component
public class ReportWorkbook {
    /** The six worksheets in {@link #sections}; the archive check expects exactly this many. */
    private static final int SHEET_COUNT = 6;

    /** Counts data rows on one sheet and refuses the report once a sheet passes the row limit. */
    static final class RowBudget {
        private int rows;

        void add() {
            rows++;
            if (rows > ReportBudget.ROWS) {
                throw ReportBudget.tooLarge();
            }
        }
    }

    /** Writes the complete report into the workspace and returns the finished file. */
    public Path generate(ProjectSnapshot snapshot, ReportWorkspace workspace, ReportBudget budget) throws IOException {
        boolean historyComplete = snapshot.history() != null
            && snapshot.history().count() == snapshot.history().events().size();
        if (!historyComplete || !snapshot.settings().configured()) {
            throw ReportBudget.unavailable();
        }
        SummaryDtos.Contribution contribution;
        SummaryDtos.Workload workload;
        try {
            contribution = SummaryCalculator.contribution(snapshot);
            workload = SummaryCalculator.workload(snapshot);
        } catch (ApiFailure invalid) {
            throw ReportBudget.unavailable();
        }
        Path finalFile = workspace.file("complete-", ".xlsx");
        StreamingWorkbook book = new StreamingWorkbook();
        try {
            ReportStyles styles = new ReportStyles(book);
            POIXMLProperties.CoreProperties properties = book.getXSSFWorkbook().getProperties().getCoreProperties();
            properties.setTitle("Owlibi Professor Report");
            properties.setCreator("Owlibi");
            properties.setLastModifiedByUser("Owlibi");
            for (ReportSection section : sections(snapshot, contribution, workload)) {
                // Polymorphic call: each sheet class supplies its own rows.
                section.write(book, styles, budget);
            }
            try (OutputStream stream = workspace.output(finalFile, ReportBudget.OUTPUT)) {
                book.write(stream);
            }
            budget.check();
            workspace.measure();
            return finalFile;
        } finally {
            // POI 5.5.1 close closes sheet writers/disposes sheets; explicit dispose is also safe/idempotent.
            try {
                book.close();
            } finally {
                try {
                    book.dispose();
                } finally {
                    book.getXSSFWorkbook().close();
                }
            }
        }
    }

    /** The report's worksheets, in tab order. */
    private static List<ReportSection> sections(ProjectSnapshot snapshot, SummaryDtos.Contribution contribution,
                                                SummaryDtos.Workload workload) {
        return List.of(
            new ProjectSummarySheet(snapshot, contribution),
            new MemberContributionsSheet(snapshot, contribution),
            new TaskDetailsSheet(snapshot),
            new WorkloadSheet(snapshot, workload),
            new ActivityHistorySheet(snapshot),
            new ProjectRulesSheet(snapshot));
    }

    /** Checks that a finished file is a complete archive holding exactly the expected workbook parts. */
    public void verifyComplete(Path complete, ReportBudget budget) throws IOException {
        budget.check();
        Set<String> expected = new HashSet<>(Set.of("[Content_Types].xml", "_rels/.rels", "docProps/app.xml",
            "docProps/core.xml", "xl/sharedStrings.xml", "xl/styles.xml", "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels"));
        for (int sheet = 1; sheet <= SHEET_COUNT; sheet++) {
            expected.add("xl/worksheets/sheet" + sheet + ".xml");
        }
        Set<String> seen = new HashSet<>();
        byte[] buffer = new byte[8192];
        // ZipInputStream alone accepts local records without a central directory/end record.
        // ZipFile requires that structure; retain streaming CRC checks and compare both views.
        try (ZipFile directory = new ZipFile(complete.toFile());
             InputStream stream = Files.newInputStream(complete);
             ZipInputStream zip = new ZipInputStream(stream)) {
            if (directory.size() != expected.size()) {
                throw ReportBudget.unavailable();
            }
            Map<String, ZipEntry> indexed = new HashMap<>();
            Enumeration<? extends ZipEntry> entries = directory.entries();
            while (entries.hasMoreElements()) {
                budget.check();
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !expected.contains(entry.getName())
                    || indexed.put(entry.getName(), entry) != null) {
                    throw ReportBudget.unavailable();
                }
            }
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                budget.check();
                if (!expected.contains(entry.getName()) || !seen.add(entry.getName())) {
                    throw ReportBudget.unavailable();
                }
                while (zip.read(buffer) != -1) {
                    budget.check();
                }
                zip.closeEntry();
                ZipEntry index = indexed.get(entry.getName());
                if (index == null || index.getSize() != entry.getSize()
                    || index.getCompressedSize() != entry.getCompressedSize()
                    || index.getCrc() != entry.getCrc() || index.getMethod() != entry.getMethod()) {
                    throw ReportBudget.unavailable();
                }
            }
        }
        if (!seen.equals(expected)) {
            throw ReportBudget.unavailable();
        }
        budget.check();
    }

    /**
     * Splits text into pieces that each fit in one Excel cell after OOXML escaping.
     * Control characters and text that looks like an escape take seven units each.
     */
    public static List<String> chunks(String value) {
        if (value.isEmpty()) {
            return List.of("");
        }
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            int end = start;
            int encoded = 0;
            while (end < value.length()) {
                int scalar = value.codePointAt(end);
                int units = Character.charCount(scalar);
                int cost = isEscapedControl(scalar) || escapePrefix(value, end) ? 7 : units;
                if (encoded + cost > ReportBudget.CELL_UNITS) {
                    break;
                }
                encoded += cost;
                end += units;
            }
            result.add(value.substring(start, end));
            start = end;
        }
        return List.copyOf(result);
    }

    /** Escapes control characters and literal {@code _xHHHH_} text so Excel shows the original value. */
    static String ooxmlText(String value) {
        StringBuilder encoded = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (isEscapedControl(character)) {
                encoded.append(String.format(Locale.ROOT, "_x%04X_", (int) character));
            } else if (escapePrefix(value, index)) {
                encoded.append("_x005F_");
            } else {
                encoded.append(character);
            }
        }
        return encoded.toString();
    }

    /** Control characters other than tab, line feed and carriage return. */
    private static boolean isEscapedControl(int scalar) {
        return scalar < 32 && scalar != 9 && scalar != 10 && scalar != 13;
    }

    /** Whether the text at this index already looks like an OOXML escape such as {@code _x000D_}. */
    private static boolean escapePrefix(String value, int index) {
        if (value.charAt(index) != '_' || index + 6 >= value.length()
            || value.charAt(index + 1) != 'x' || value.charAt(index + 6) != '_') {
            return false;
        }
        for (int digit = index + 2; digit < index + 6; digit++) {
            if (Character.digit(value.charAt(digit), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
