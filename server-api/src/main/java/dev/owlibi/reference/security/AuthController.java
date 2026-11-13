package dev.owlibi.reference.security;

import dev.owlibi.reference.config.WebBoundaryConfiguration.LocalUser;
import dev.owlibi.reference.web.JsonBody;
import jakarta.servlet.http.*;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.*;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/v1/auth",produces="application/json")
public class AuthController {
    private final AccountService accounts;
    private final JsonBody bodies;
    private final Clock clock;
    private final SessionAuthenticationStrategy strategy;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository tokens;
    public AuthController(AccountService accounts,JsonBody bodies,Clock clock,SessionAuthenticationStrategy strategy,
                          HttpSessionSecurityContextRepository contexts,HttpSessionCsrfTokenRepository tokens) {
        this.accounts=accounts; this.bodies=bodies; this.clock=clock; this.strategy=strategy; this.contexts=contexts; this.tokens=tokens;
    }
    @GetMapping("/sessions") public Map<String,String> csrf(CsrfToken token,HttpServletRequest request) { bodies.empty(request); return Map.of("token",token.getToken(),"headerName",token.getHeaderName()); }
    @PostMapping("/accounts") public ResponseEntity<AccountPrincipal> register(HttpServletRequest request) {
        accounts.registrationAttempt(request.getRemoteAddr());
        var body=bodies.read(request,"email","displayName","password");
        return ResponseEntity.status(201).body(accounts.register(JsonBody.string(body,"email"),JsonBody.string(body,"displayName"),JsonBody.string(body,"password")));
    }
    @PostMapping("/sessions") public Map<String,Object> login(HttpServletRequest request,HttpServletResponse response) {
        var body=bodies.read(request,"email","password");
        var authenticated=accounts.login(JsonBody.string(body,"email"),JsonBody.string(body,"password"),request.getRemoteAddr());
        AccountPrincipal account=((LocalUser)authenticated.getPrincipal()).account();
        var safe=UsernamePasswordAuthenticationToken.authenticated(account,null,authenticated.getAuthorities());
        strategy.onAuthentication(safe,request,response);
        var state=state(request); state.login(account,clock.instant());
        var context=SecurityContextHolder.createEmptyContext(); context.setAuthentication(safe); SecurityContextHolder.setContext(context);
        contexts.saveContext(context,request,response);
        response.setHeader("X-Session-Epoch",state.epoch().toString());
        return Map.of("id",account.id(),"displayName",account.displayName(),"sessionEpoch",state.epoch());
    }
    @GetMapping("/sessions/current") public Map<String,Object> current(HttpServletRequest request) {
        bodies.empty(request); var state=state(request); return Map.of("id",state.account().id(),"displayName",state.account().displayName(),"sessionEpoch",state.epoch(),
            "serverTimeUtc",clock.instant(),"expiresAtUtc",state.expires());
    }
    @PostMapping("/sessions/current/activity") public Map<String,Object> activity(HttpServletRequest request) {
        bodies.read(request); var state=state(request); var now=clock.instant(); state.activity(now);
        return Map.of("serverTimeUtc",now,"expiresAtUtc",state.expires());
    }
    @DeleteMapping("/sessions/current") public ResponseEntity<Void> logout(HttpServletRequest request,HttpServletResponse response) {
        bodies.empty(request); state(request).revoke();
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        new CsrfLogoutHandler(tokens).logout(request,response,authentication);
        new SecurityContextLogoutHandler().logout(request,response,authentication);
        Cookie cookie=new Cookie("JSESSIONID",""); cookie.setPath("/"); cookie.setHttpOnly(true); cookie.setSecure(request.isSecure() || request.getServletContext().getSessionCookieConfig().isSecure()); cookie.setMaxAge(0); cookie.setAttribute("SameSite","Lax");
        new CookieClearingLogoutHandler(cookie).logout(request,response,authentication);
        return ResponseEntity.noContent().build();
    }
    private static SessionState state(HttpServletRequest request) { return (SessionState)request.getAttribute(SessionState.ATTRIBUTE); }
}
