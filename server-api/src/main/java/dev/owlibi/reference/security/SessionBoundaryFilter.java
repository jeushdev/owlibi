package dev.owlibi.reference.security;

import dev.owlibi.reference.web.ApiFailure;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/** Expiry/authentication before CSRF; serializes session changes and unsafe intents. */
public final class SessionBoundaryFilter extends OncePerRequestFilter {
    private final Clock clock;
    public SessionBoundaryFilter(Clock clock) { this.clock=clock; }
    static boolean unsafe(HttpServletRequest r) { return !List.of("GET","HEAD","OPTIONS","TRACE").contains(r.getMethod()); }
    private static boolean publicApi(HttpServletRequest r) {
        String p=r.getServletPath(),m=r.getMethod();
        return (m.equals("GET") && (p.equals("/api/v1/health") || p.equals("/api/v1/auth/sessions"))) ||
            (m.equals("POST") && (p.equals("/api/v1/auth/accounts") || p.equals("/api/v1/auth/sessions")));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if (!request.getServletPath().startsWith("/api/")) { chain.doFilter(request,response); return; }
        response.setHeader("Cache-Control","no-store"); boolean publicRoute=publicApi(request);
        HttpSession session=request.getSession(false);
        if (session==null && publicRoute && !request.getServletPath().equals("/api/v1/health")) session=request.getSession(true);
        if (session==null) {
            if (publicRoute) chain.doFilter(request,response); else write(response,new ApiFailure(401,"UNAUTHENTICATED","Sign in is required"));
            return;
        }
        SessionState state;
        // Request-specific session facades may differ; protect attribute installation only.
        try { synchronized (request.getServletContext()) {
            state=(SessionState)session.getAttribute(SessionState.ATTRIBUTE);
            if (state==null) { state=new SessionState(); session.setAttribute(SessionState.ATTRIBUTE,state); }
        } } catch (IllegalStateException ended) {
            SecurityContextHolder.clearContext(); write(response,new ApiFailure(401,"UNAUTHENTICATED","Sign in is required")); return;
        }
        boolean reportRead=request.getMethod().equals("GET") && request.getServletPath().matches("/api/v1/projects/[^/]+/professor-report");
        synchronized (state) {
            if (state.revoked() || state.expired(clock.instant())) {
                state.revoke();
                try { session.invalidate(); } catch (IllegalStateException alreadyEnded) { /* Ended by another request. */ }
                SecurityContextHolder.clearContext();
                if (request.getMethod().equals("GET") && request.getServletPath().equals("/api/v1/health")) chain.doFilter(request,response);
                else write(response,new ApiFailure(401,"UNAUTHENTICATED","Sign in is required"));
                return;
            }
            // Reload under the monitor; a login may have replaced a context loaded earlier.
            var context=SecurityContextHolder.createEmptyContext();
            if (state.account()!=null) {
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(state.account(),null,List.of()));
                response.setHeader("X-Session-Epoch",state.epoch().toString());
            }
            SecurityContextHolder.setContext(context);
            if (!publicRoute && state.account()==null) { write(response,new ApiFailure(401,"UNAUTHENTICATED","Sign in is required")); return; }
            request.setAttribute(SessionState.ATTRIBUTE,state);
            if(reportRead) request.setAttribute(SessionState.ATTRIBUTE+".epoch",state.epoch());
            else { try { chain.doFilter(request,response); } catch (ApiFailure failure) { write(response,failure); }return; }
        }
        // Only the long report GET releases the monitor. It rechecks this captured identity/epoch before delivery.
        try { chain.doFilter(request,response); } catch (ApiFailure failure) { write(response,failure); }
    }
    public static void write(HttpServletResponse response,ApiFailure failure) throws IOException {
        response.setStatus(failure.status()); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8"); response.setHeader("Cache-Control","no-store");
        if (failure.retryAfter()!=null) response.setHeader("Retry-After",failure.retryAfter().toString());
        response.getWriter().write(JsonMapper.shared().writeValueAsString(failure.error()));
    }
}
