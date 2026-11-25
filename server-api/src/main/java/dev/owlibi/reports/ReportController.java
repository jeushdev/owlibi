package dev.owlibi.reports;

import dev.owlibi.security.AccountPrincipal;
import dev.owlibi.security.SessionState;
import dev.owlibi.snapshots.CaptureBudget;
import dev.owlibi.snapshots.ProjectSnapshot;
import dev.owlibi.snapshots.SnapshotService;
import dev.owlibi.web.ApiFailure;
import dev.owlibi.web.JsonBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the professor report: an Excel workbook built from one consistent snapshot of a project.
 *
 * <p>Only one report is generated at a time. The session is re-checked before and after the slow work,
 * so a user who signs out mid-export never receives the file.
 */
@RestController
@RequestMapping("/api/v1/projects/{project}")
public class ReportController {
    /** The content type of an .xlsx workbook. */
    public static final String MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final Semaphore SLOT = new Semaphore(1);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
        .withZone(ZoneOffset.UTC);
    private static final Set<String> BUDGET_FAILURE_CODES = Set.of("REPORT_TOO_LARGE", "REPORT_TIMEOUT");
    // PostgreSQL states: 57014 is a cancelled (timed out) statement, 55P03 is a lock that was not available in time.
    private static final Set<String> TIMEOUT_SQL_STATES = Set.of("57014", "55P03");

    private final SnapshotService snapshots;
    private final ReportWorkbook workbook;
    private final JsonBody bodies;
    private final Clock clock;

    /** Wires the services and removes export folders left behind by a previous crash. */
    public ReportController(SnapshotService snapshots, ReportWorkbook workbook, JsonBody bodies, Clock clock) throws IOException {
        this.snapshots = snapshots;
        this.workbook = workbook;
        this.bodies = bodies;
        this.clock = clock;
        ReportWorkspace.cleanupAbandoned(ReportWorkspace.defaultParent());
    }

    /** Answers an OPTIONS request with the methods the report endpoint allows. */
    @RequestMapping(value = "/professor-report", method = RequestMethod.OPTIONS)
    public ResponseEntity<Void> methods() {
        return ResponseEntity.ok()
            .allow(HttpMethod.GET, HttpMethod.OPTIONS)
            .header("Cache-Control", "no-store")
            .build();
    }

    /**
     * Captures the project, builds the workbook in a private temporary folder and streams it to the client.
     *
     * <p>Capture has a 10 second budget and generation a 20 second budget. Any failure before the first byte
     * is sent becomes a JSON error; a failure after that can only end the connection.
     */
    @GetMapping("/professor-report")
    public void report(
        @AuthenticationPrincipal AccountPrincipal actor,
        @PathVariable("project") UUID project,
        HttpServletRequest request,
        HttpServletResponse response
    ) throws IOException {
        var captureDeadline = new ReportBudget(Duration.ofSeconds(10));
        try {
            snapshots.authorizeReport(actor.id(), project, new CaptureBudget(captureDeadline));
        } catch (RuntimeException unavailable) {
            throw captureFailure(unavailable, captureDeadline);
        }
        if (!request.getMethod().equals("GET")) {
            response.setHeader("Allow", "GET, OPTIONS");
            throw new ApiFailure(405, "MALFORMED_REQUEST", "Method unavailable");
        }
        if (request.getQueryString() != null) throw ApiFailure.malformed();
        bodies.empty(request);
        var state = (SessionState) request.getAttribute(SessionState.ATTRIBUTE);
        UUID epoch = (UUID) request.getAttribute(SessionState.ATTRIBUTE + ".epoch");
        recheck(request, state, actor, epoch);
        if (!SLOT.tryAcquire()) {
            throw new ApiFailure(429, "REPORT_BUSY", "Another report is still being generated", Map.of(), 5);
        }
        ReportWorkspace ownedWorkspace = null;
        boolean constructorCleanupIncomplete = false;
        try {
            ProjectSnapshot captured;
            try {
                captured = snapshots.captureReport(actor.id(), project, new CaptureBudget(captureDeadline));
            } catch (RuntimeException unavailable) {
                throw captureFailure(unavailable, captureDeadline);
            }
            captureDeadline.check();
            recheck(request, state, actor, epoch);
            var generation = new ReportBudget(Duration.ofSeconds(20));
            try (var workspace = new ReportWorkspace(generation)) {
                ownedWorkspace = workspace;
                Path complete = workbook.generate(captured, workspace, generation);
                generation.check();
                workbook.verifyComplete(complete, generation);
                // The capture transaction is already released. Do not send successful headers before these checks.
                recheck(request, state, actor, epoch);
                snapshots.authorizeReport(actor.id(), project, new CaptureBudget(generation));
                recheck(request, state, actor, epoch);
                generation.check();
                long length = Files.size(complete);
                if (length == 0 || length > ReportBudget.OUTPUT) throw ReportBudget.tooLarge();
                try (var stream = Files.newInputStream(complete)) {
                    response.setContentType(MIME);
                    response.setHeader(
                        "Content-Disposition",
                        "attachment; filename=\"owlibi-" + project + "-" + STAMP.format(captured.snapshot().snapshotAtUtc()) + ".xlsx\""
                    );
                    response.setContentLengthLong(length);
                    response.setHeader("X-Content-Type-Options", "nosniff");
                    response.setHeader("Cache-Control", "no-store");
                    response.setHeader("X-Session-Epoch", epoch.toString());
                    response.setHeader("X-Owlibi-Snapshot-At", captured.snapshot().snapshotAtUtc().toString());
                    response.setHeader("X-Owlibi-State-Token", captured.snapshot().stateToken());
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        response.getOutputStream().write(buffer, 0, count);
                    }
                    response.getOutputStream().flush();
                }
            }
        } catch (ApiFailure expected) {
            throw expected;
        } catch (Exception unavailable) {
            constructorCleanupIncomplete = unavailable instanceof ReportWorkspace.CleanupIncomplete;
            if (response.isCommitted()) {
                if (unavailable instanceof IOException io) throw io;
                throw new IOException("Report delivery ended", unavailable);
            }
            response.resetBuffer();
            response.setHeader("Content-Disposition", null);
            response.setHeader("Content-Length", null);
            response.setHeader("X-Owlibi-State-Token", null);
            response.setHeader("X-Owlibi-Snapshot-At", null);
            response.setContentType("application/json");
            if (ownedWorkspace != null && ownedWorkspace.failure() != null) throw ownedWorkspace.failure();
            var nested = budgetFailure(unavailable);
            if (nested != null) throw nested;
            if (causedTimeout(unavailable)) throw ReportBudget.timeout();
            throw ReportBudget.unavailable();
        } finally {
            if (!constructorCleanupIncomplete && (ownedWorkspace == null || ownedWorkspace.closed())) {
                SLOT.release();
            }
        }
    }

    /**
     * Chooses the error for a failed snapshot authorization or capture: an expected API failure passes through,
     * a spent budget or database timeout becomes REPORT_TIMEOUT, anything else becomes REPORT_UNAVAILABLE.
     */
    private static ApiFailure captureFailure(RuntimeException unavailable, ReportBudget captureDeadline) {
        if (unavailable instanceof ApiFailure expected) return expected;
        if (captureDeadline.remainingNanos() == 0 || causedTimeout(unavailable)) return ReportBudget.timeout();
        return ReportBudget.unavailable();
    }

    private void recheck(HttpServletRequest request, SessionState state, AccountPrincipal actor, UUID epoch) {
        synchronized (state) {
            if (state.revoked() || state.expired(clock.instant()) || request.getSession(false) == null) {
                state.revoke();
                try {
                    if (request.getSession(false) != null) request.getSession(false).invalidate();
                } catch (IllegalStateException ended) {
                    /* Already invalid. */
                }
                throw new ApiFailure(401, "UNAUTHENTICATED", "Sign in is required");
            }
            if (!actor.equals(state.account()) || !epoch.equals(state.epoch())) {
                throw new ApiFailure(409, "SESSION_CHANGED", "Session has changed; revalidate sign in context");
            }
        }
    }

    /** Finds a too-large or timeout report failure anywhere among the error's causes and suppressed errors. */
    private static ApiFailure budgetFailure(Throwable error) {
        return (ApiFailure) firstInCauseGraph(
            error,
            candidate -> candidate instanceof ApiFailure api && BUDGET_FAILURE_CODES.contains(api.error().code()),
            false
        );
    }

    /** Whether a socket, transaction or database timeout appears anywhere in the error's cause graph. */
    private static boolean causedTimeout(Throwable error) {
        return firstInCauseGraph(error, ReportController::isTimeout, true) != null;
    }

    private static boolean isTimeout(Throwable candidate) {
        return candidate instanceof SocketTimeoutException
            || candidate instanceof TransactionTimedOutException
            || (candidate instanceof SQLException sql && sql.getSQLState() != null && TIMEOUT_SQL_STATES.contains(sql.getSQLState()));
    }

    /**
     * Visits the error, then its causes and suppressed errors breadth-first, each object at most once,
     * and returns the first one that matches, or {@code null}.
     *
     * @param followApplicationExceptions also visit the original exception a failed transaction rollback hid
     */
    private static Throwable firstInCauseGraph(Throwable error, Predicate<Throwable> matches, boolean followApplicationExceptions) {
        var pending = new ArrayDeque<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        pending.add(error);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (matches.test(current)) return current;
            if (current.getCause() != null) pending.add(current.getCause());
            Collections.addAll(pending, current.getSuppressed());
            if (followApplicationExceptions
                && current instanceof TransactionSystemException transaction
                && transaction.getApplicationException() != null) {
                pending.add(transaction.getApplicationException());
            }
        }
        return null;
    }
}
