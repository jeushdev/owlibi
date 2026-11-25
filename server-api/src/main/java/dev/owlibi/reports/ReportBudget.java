package dev.owlibi.reports;

import dev.owlibi.web.ApiFailure;
import java.time.Duration;
import java.util.function.LongSupplier;

/** Cooperative monotonic deadlines; the synchronous worker retains ownership until it returns. */
public final class ReportBudget {
    public static final int TASKS=1000, EVENTS=10000, ROWS=100000, CELL_UNITS=30000;
    public static final long TEXT=32L*1024*1024, OUTPUT=16L*1024*1024, WORKSPACE=64L*1024*1024;
    private final LongSupplier ticker;
    private final long started, duration;
    public ReportBudget(Duration duration) { this(duration,System::nanoTime); }
    public ReportBudget(Duration duration,LongSupplier ticker) { this.ticker=ticker;this.started=ticker.getAsLong();this.duration=duration.toNanos(); }
    public long remainingNanos() { return Math.max(0,duration-(ticker.getAsLong()-started)); }
    public void check() { if(Thread.currentThread().isInterrupted() || remainingNanos()==0) throw timeout(); }
    public int remainingMillis() { check();return (int)Math.max(1,Math.min(Integer.MAX_VALUE,remainingNanos()/1_000_000)); }
    public static ApiFailure tooLarge() { return new ApiFailure(422,"REPORT_TOO_LARGE","The complete report exceeds local export limits"); }
    public static ApiFailure timeout() { return new ApiFailure(503,"REPORT_TIMEOUT","Report time budget exceeded"); }
    public static ApiFailure unavailable() { return new ApiFailure(503,"REPORT_UNAVAILABLE","Report temporarily unavailable"); }
}
