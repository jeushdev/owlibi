package dev.owlibi.reports;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.poi.openxml4j.util.ZipFileZipEntrySource;
import org.apache.poi.xssf.streaming.GZIPSheetDataWriter;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.streaming.SheetDataWriter;

/**
 * A streaming Excel workbook that keeps only a few rows in memory.
 *
 * <p>Rows are flushed to compressed temporary files inside the current {@link ReportWorkspace}, so the
 * workspace size limit also covers them.
 */
final class StreamingWorkbook extends SXSSFWorkbook {
    private static final int ROWS_IN_MEMORY = 100;

    StreamingWorkbook() {
        super(null, ROWS_IN_MEMORY, true, true);
        setZip64Mode(Zip64Mode.AsNeeded);
    }

    @Override
    protected SheetDataWriter createSheetDataWriter() throws IOException {
        return new GZIPSheetDataWriter(getSharedStringSource()) {
            @Override
            protected OutputStream decorateOutputStream(FileOutputStream stream) throws IOException {
                return new GZIPOutputStream(ReportWorkspace.current().bounded(stream, ReportBudget.WORKSPACE));
            }
        };
    }

    @Override
    public void write(OutputStream output) throws IOException {
        flushSheets();
        ReportWorkspace workspace = ReportWorkspace.current();
        Path template = workspace.file("template-", ".xlsx");
        try {
            try (OutputStream stream = workspace.output(template, ReportBudget.WORKSPACE)) {
                getXSSFWorkbook().write(stream);
            }
            // This archive was generated here; no untrusted workbook is imported or parsed.
            try (ZipFile zip = ZipFile.builder().setPath(template).get();
                 ZipFileZipEntrySource entries = new ZipFileZipEntrySource(zip)) {
                injectData(entries, output);
            }
        } finally {
            Files.deleteIfExists(template);
        }
    }
}
