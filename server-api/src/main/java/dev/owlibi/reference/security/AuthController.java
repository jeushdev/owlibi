package dev.owlibi.reference.security;

import dev.owlibi.reference.config.WebBoundaryConfiguration.LocalUser;
import dev.owlibi.reference.web.JsonBody;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoints for registering, signing in and out, and keeping a session alive. */
@RestController
@RequestMapping(value = "/api/v1/auth", produces = "application/json")
public class AuthController {
    private final AccountService accounts;
    private final JsonBody bodies;
    private final Clock clock;
    private final SessionAuthenticationStrategy strategy;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository tokens;

    public AuthController(AccountService accounts, JsonBody bodies, Clock clock, SessionAuthenticationStrategy strategy,
            HttpSessionSecurityContextRepository contexts, HttpSessionCsrfTokenRepository tokens) {
        this.accounts = accounts;
        this.bodies = bodies;
        this.clock = clock;
        this.strategy = strategy;
        this.contexts = contexts;
        this.tokens = tokens;
    }

    /** The CSRF token the browser must send with every request that changes data. */
    @GetMapping("/sessions")
    public Map<String, String> csrf(CsrfToken token, HttpServletRequest request) {
        bodies.empty(request);
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    /** Creates an account. It does not sign in. */
    @PostMapping("/accounts")
    public ResponseEntity<AccountPrincipal> register(HttpServletRequest request) {
        accounts.registrationAttempt(request.getRemoteAddr());
        var body = bodies.read(request, "email", "displayName", "password");
        var account = accounts.register(
            JsonBody.string(body, "email"),
            JsonBody.string(body, "displayName"),
            JsonBody.string(body, "password"));
        return ResponseEntity.status(201).body(account);
    }

    /** Signs in, starting a fresh session with a new epoch. */
    @PostMapping("/sessions")
    public Map<String, Object> login(HttpServletRequest request, HttpServletResponse response) {
        var body = bodies.read(request, "email", "password");
        var authenticated = accounts.login(JsonBody.string(body, "email"), JsonBody.string(body, "password"), request.getRemoteAddr());
        AccountPrincipal account = ((LocalUser) authenticated.getPrincipal()).account();
        // Keep only the safe principal in the session, never the password-bearing user details.
        var safe = UsernamePasswordAuthenticationToken.authenticated(account, null, authenticated.getAuthorities());
        strategy.onAuthentication(safe, request, response);
        var state = state(request);
        state.login(account, clock.instant());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(safe);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        response.setHeader("X-Session-Epoch", state.epoch().toString());
        return Map.of("id", account.id(), "displayName", account.displayName(), "sessionEpoch", state.epoch());
    }

    /** The signed-in account and when the session expires. */
    @GetMapping("/sessions/current")
    public Map<String, Object> current(HttpServletRequest request) {
        bodies.empty(request);
        var state = state(request);
        return Map.of(
            "id", state.account().id(),
            "displayName", state.account().displayName(),
            "sessionEpoch", state.epoch(),
            "serverTimeUtc", clock.instant(),
            "expiresAtUtc", state.expires());
    }

    /** Records that the person is still active, extending the session. */
    @PostMapping("/sessions/current/activity")
    public Map<String, Object> activity(HttpServletRequest request) {
        bodies.read(request);
        var state = state(request);
        var now = clock.instant();
        state.activity(now);
        return Map.of("serverTimeUtc", now, "expiresAtUtc", state.expires());
    }

    /** Signs out: revokes the session, clears its CSRF token and security context, and expires the cookie. */
    @DeleteMapping("/sessions/current")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        bodies.empty(request);
        state(request).revoke();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        new CsrfLogoutHandler(tokens).logout(request, response, authentication);
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        new CookieClearingLogoutHandler(expiredSessionCookie(request)).logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }

    private static Cookie expiredSessionCookie(HttpServletRequest request) {
        Cookie cookie = new Cookie("JSESSIONID", "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure() || request.getServletContext().getSessionCookieConfig().isSecure());
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }

    private static SessionState state(HttpServletRequest request) {
        return (SessionState) request.getAttribute(SessionState.ATTRIBUTE);
    }
}
