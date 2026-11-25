package dev.owlibi.reference.reports;

import java.util.EnumMap;
import java.util.HexFormat;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;

/** The fixed set of cell styles that every sheet in one workbook shares. */
final class ReportStyles {
    /** The kinds of cell the report uses. EVEN and ODD rows alternate so long tables are easier to read. */
    enum Tone { TITLE, SUBTITLE, HEADER, KEY, EVEN, ODD, EVEN_ONE, ODD_ONE, EVEN_TWO, ODD_TWO, EVEN_INTEGER, ODD_INTEGER }

    private final EnumMap<Tone, CellStyle> cells = new EnumMap<>(Tone.class);

    ReportStyles(StreamingWorkbook book) {
        XSSFFont dark = font(book, "172F4D", 11, false);
        XSSFFont muted = font(book, "3F5876", 10, false);
        XSSFFont strong = font(book, "172F4D", 11, true);
        XSSFFont white = font(book, "FFFFFF", 11, true);
        XSSFFont title = font(book, "FFFFFF", 19, true);
        put(book, Tone.TITLE, title, "142B49", null);
        put(book, Tone.SUBTITLE, muted, "E9F1F9", null);
        put(book, Tone.HEADER, white, "265580", null);
        put(book, Tone.KEY, strong, "E9F1F9", null);
        put(book, Tone.EVEN, dark, "FFFFFF", null);
        put(book, Tone.ODD, dark, "F2F6FB", null);
        put(book, Tone.EVEN_ONE, dark, "FFFFFF", "0.0");
        put(book, Tone.ODD_ONE, dark, "F2F6FB", "0.0");
        put(book, Tone.EVEN_TWO, dark, "FFFFFF", "0.00");
        put(book, Tone.ODD_TWO, dark, "F2F6FB", "0.00");
        put(book, Tone.EVEN_INTEGER, dark, "FFFFFF", "0");
        put(book, Tone.ODD_INTEGER, dark, "F2F6FB", "0");
    }

    /** The shared style for one kind of cell. */
    CellStyle get(Tone tone) {
        return cells.get(tone);
    }

    /** A colour from a six-digit hex code such as {@code "142B49"}. */
    static XSSFColor color(String rgb) {
        return new XSSFColor(HexFormat.of().parseHex(rgb));
    }

    private static XSSFFont font(StreamingWorkbook book, String rgb, int size, boolean bold) {
        XSSFFont font = book.getXSSFWorkbook().createFont();
        font.setFontName("Calibri");
        font.setFontHeightInPoints((short) size);
        font.setColor(color(rgb));
        font.setBold(bold);
        return font;
    }

    private void put(StreamingWorkbook book, Tone tone, Font font, String rgb, String numberFormat) {
        XSSFCellStyle style = (XSSFCellStyle) book.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(color(rgb));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setWrapText(true);
        boolean centered = tone == Tone.TITLE || tone == Tone.HEADER;
        style.setVerticalAlignment(centered ? VerticalAlignment.CENTER : VerticalAlignment.TOP);
        if (numberFormat != null) {
            style.setDataFormat(book.createDataFormat().getFormat(numberFormat));
            style.setAlignment(HorizontalAlignment.RIGHT);
        }
        cells.put(tone, style);
    }
}
