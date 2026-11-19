package dev.owlibi.reference.reports;

import dev.owlibi.reference.security.*;
import dev.owlibi.reference.snapshots.*;
import dev.owlibi.reference.web.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1/projects/{project}")
public class ReportController {
    public static final String MIME="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Semaphore SLOT=new Semaphore(1);
    private static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'",Locale.ROOT).withZone(ZoneOffset.UTC);
    private final SnapshotService snapshots;
    private final ReportWorkbook workbook;
    private final JsonBody bodies;
    private final Clock clock;
    public ReportController(SnapshotService snapshots,ReportWorkbook workbook,JsonBody bodies,Clock clock) throws IOException {
        this.snapshots=snapshots;this.workbook=workbook;this.bodies=bodies;this.clock=clock;
        ReportWorkspace.cleanupAbandoned(ReportWorkspace.defaultParent());
    }
    @RequestMapping(value="/professor-report",method=RequestMethod.OPTIONS)
    public ResponseEntity<Void> methods() {
        return ResponseEntity.ok().allow(HttpMethod.GET,HttpMethod.OPTIONS).header("Cache-Control","no-store").build();
    }
    @GetMapping("/professor-report")
    public void report(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable("project") UUID project,HttpServletRequest request,HttpServletResponse response) throws IOException {
        var captureDeadline=new ReportBudget(Duration.ofSeconds(10));
        try { snapshots.authorizeReport(actor.id(),project,new CaptureBudget(captureDeadline)); }
        catch(RuntimeException unavailable) { if(unavailable instanceof ApiFailure expected) throw expected;if(captureDeadline.remainingNanos()==0 || causedTimeout(unavailable)) throw ReportBudget.timeout();throw ReportBudget.unavailable(); }
        if(!request.getMethod().equals("GET")) {
            response.setHeader("Allow","GET, OPTIONS");
            throw new ApiFailure(405,"MALFORMED_REQUEST","Method unavailable");
        }
        if(request.getQueryString()!=null) throw ApiFailure.malformed();bodies.empty(request);
        var state=(SessionState)request.getAttribute(SessionState.ATTRIBUTE);UUID epoch=(UUID)request.getAttribute(SessionState.ATTRIBUTE+".epoch");
        recheck(request,state,actor,epoch);
        if(!SLOT.tryAcquire()) throw new ApiFailure(429,"REPORT_BUSY","Another report is still being generated",Map.of(),5);
        ReportWorkspace ownedWorkspace=null;boolean constructorCleanupIncomplete=false;
        try {
            ProjectSnapshot captured;
            try { captured=snapshots.captureReport(actor.id(),project,new CaptureBudget(captureDeadline)); }
            catch(RuntimeException unavailable) { if(unavailable instanceof ApiFailure expected) throw expected;if(captureDeadline.remainingNanos()==0 || causedTimeout(unavailable)) throw ReportBudget.timeout();throw ReportBudget.unavailable(); }
            captureDeadline.check();recheck(request,state,actor,epoch);var generation=new ReportBudget(Duration.ofSeconds(20));
            try(var workspace=new ReportWorkspace(generation)) {
                ownedWorkspace=workspace;
                Path complete=workbook.generate(captured,workspace,generation);generation.check();workbook.verifyComplete(complete,generation);
                // The capture transaction is already released. Do not send successful headers before these checks.
                recheck(request,state,actor,epoch);snapshots.authorizeReport(actor.id(),project,new CaptureBudget(generation));recheck(request,state,actor,epoch);generation.check();
                long length=Files.size(complete);if(length==0 || length>ReportBudget.OUTPUT) throw ReportBudget.tooLarge();
                try(var stream=Files.newInputStream(complete)) {
                    response.setContentType(MIME);response.setHeader("Content-Disposition","attachment; filename=\"owlibi-"+project+"-"+STAMP.format(captured.snapshot().snapshotAtUtc())+".xlsx\"");
                    response.setContentLengthLong(length);response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Cache-Control","no-store");response.setHeader("X-Session-Epoch",epoch.toString());
                    response.setHeader("X-Owlibi-Snapshot-At",captured.snapshot().snapshotAtUtc().toString());response.setHeader("X-Owlibi-State-Token",captured.snapshot().stateToken());
                    byte[] buffer=new byte[8192];int count;while((count=stream.read(buffer))!=-1) response.getOutputStream().write(buffer,0,count);
                    response.getOutputStream().flush();
                }
            }
        } catch(ApiFailure expected) { throw expected; }
        catch(Exception unavailable) {
            constructorCleanupIncomplete=unavailable instanceof ReportWorkspace.CleanupIncomplete;
            if(response.isCommitted()) { if(unavailable instanceof IOException io) throw io;throw new IOException("Report delivery ended",unavailable); }
            response.resetBuffer();response.setHeader("Content-Disposition",null);response.setHeader("Content-Length",null);response.setHeader("X-Owlibi-State-Token",null);response.setHeader("X-Owlibi-Snapshot-At",null);response.setContentType("application/json");if(ownedWorkspace!=null && ownedWorkspace.failure()!=null) throw ownedWorkspace.failure();var nested=budgetFailure(unavailable);if(nested!=null) throw nested;if(causedTimeout(unavailable)) throw ReportBudget.timeout();throw ReportBudget.unavailable();
        } finally { if(!constructorCleanupIncomplete && (ownedWorkspace==null || ownedWorkspace.closed())) SLOT.release(); }
    }
    private void recheck(HttpServletRequest request,SessionState state,AccountPrincipal actor,UUID epoch) {
        synchronized(state) {
            if(state.revoked() || state.expired(clock.instant()) || request.getSession(false)==null) {
                state.revoke();try { if(request.getSession(false)!=null) request.getSession(false).invalidate(); } catch(IllegalStateException ended) { /* Already invalid. */ }
                throw new ApiFailure(401,"UNAUTHENTICATED","Sign in is required");
            }
            if(!actor.equals(state.account()) || !epoch.equals(state.epoch())) throw new ApiFailure(409,"SESSION_CHANGED","Session has changed; revalidate sign in context");
        }
    }
    private static ApiFailure budgetFailure(Throwable error) {
        var pending=new ArrayDeque<Throwable>();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());pending.add(error);
        while(!pending.isEmpty()) {
            var e=pending.removeFirst();if(!seen.add(e)) continue;
            if(e instanceof ApiFailure api && Set.of("REPORT_TOO_LARGE","REPORT_TIMEOUT").contains(api.error().code())) return api;
            if(e.getCause()!=null) pending.add(e.getCause());Collections.addAll(pending,e.getSuppressed());
        }return null;
    }
    private static boolean causedTimeout(Throwable error) {
        var pending=new ArrayDeque<Throwable>();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());pending.add(error);
        while(!pending.isEmpty()) {
            var e=pending.removeFirst();if(!seen.add(e)) continue;
            if(e instanceof java.net.SocketTimeoutException || e instanceof org.springframework.transaction.TransactionTimedOutException || e instanceof java.sql.SQLException sql && Set.of("57014","55P03").contains(sql.getSQLState())) return true;
            if(e.getCause()!=null) pending.add(e.getCause());Collections.addAll(pending,e.getSuppressed());
            if(e instanceof org.springframework.transaction.TransactionSystemException transaction && transaction.getApplicationException()!=null) pending.add(transaction.getApplicationException());
        }return false;
    }
}
