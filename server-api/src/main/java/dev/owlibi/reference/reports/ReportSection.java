package dev.owlibi.reference.reports;

/**
 * One part of the professor report that can write itself into the workbook.
 *
 * <p>{@link ReportWorkbook} only knows this interface. It keeps a list of sections and asks each one to
 * write itself, without checking which kind of section it is. Which sheet appears is decided by the
 * object's class at run time.
 */
interface ReportSection {
    /** The worksheet tab name, for example {@code "Task details"}. */
    String label();

    /** Adds this section's worksheet to the workbook, using the shared styles and time budget. */
    void write(StreamingWorkbook book, ReportStyles styles, ReportBudget budget);
}
