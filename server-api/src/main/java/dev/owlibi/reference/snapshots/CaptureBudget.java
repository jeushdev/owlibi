package dev.owlibi.reference.snapshots;

import dev.owlibi.reference.reports.ReportBudget;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Keeps a report capture within its time and size limits.
 *
 * <p>Before each SQL statement it sets PostgreSQL timeouts to the time left. Before each row is kept it
 * adds that row's compact sorted-key JSON size, so an oversized project fails early instead of after
 * loading everything.
 */
public final class CaptureBudget {
    private static final Set<String> LIST_SECTIONS = Set.of("members", "tasks", "events");

    private final ReportBudget deadline;
    // [project,members,settings,tasks,events]: brackets, four commas, three empty array pairs.
    private long bytes = 12;
    private final Set<String> nonempty = new HashSet<>();

    public CaptureBudget(ReportBudget deadline) {
        this.deadline = deadline;
    }

    /** Limits the next statement's run and lock wait to the time remaining. */
    public void statement(JdbcTemplate jdbc) {
        deadline.check();
        int remaining = deadline.remainingMillis();
        int network = jdbc.execute((ConnectionCallback<Integer>) connection -> connection.getNetworkTimeout());
        // Leave time for PostgreSQL cancellation before an earlier driver socket timeout closes the connection.
        int milliseconds = network > 0 ? Math.min(remaining, Math.max(1, network - 500)) : remaining;
        jdbc.execute("SET LOCAL statement_timeout = '" + milliseconds + "ms'");
        jdbc.execute("SET LOCAL lock_timeout = '" + milliseconds + "ms'");
    }

    /** Counts one row of {@code section}, failing when the captured text would exceed the limit. */
    public void add(String section, Object row) {
        deadline.check();
        long size = CanonicalState.json(row).getBytes(StandardCharsets.UTF_8).length;
        // Every row after the first in a list needs a separating comma.
        if (LIST_SECTIONS.contains(section) && !nonempty.add(section)) {
            size++;
        }
        if (size > ReportBudget.TEXT - bytes) {
            throw ReportBudget.tooLarge();
        }
        bytes += size;
    }

    /** Fails when a raw row of {@code size} bytes could not fit in the remaining text budget. */
    public void rawRow(long size) {
        deadline.check();
        if (size > ReportBudget.TEXT - bytes) {
            throw ReportBudget.tooLarge();
        }
    }

    /** Fails when the deadline has passed. */
    public void check() {
        deadline.check();
    }

    /** The captured text size so far. */
    public long bytes() {
        return bytes;
    }
}
