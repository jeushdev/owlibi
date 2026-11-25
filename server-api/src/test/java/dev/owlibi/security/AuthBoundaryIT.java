package dev.owlibi.security;

import static org.junit.jupiter.api.Assertions.*;
import static dev.owlibi.support.ApiClient.*;
import static dev.owlibi.support.PostgresFixture.*;
import dev.owlibi.support.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

class AuthBoundaryIT {
    private PostgresFixture db;
    private ServletWebServerApplicationContext app;
    private ApiClient api;
    private final MutableClock clock=MutableClock.INSTANCE;
    @BeforeEach void start() { clock.reset(); db=new PostgresFixture(); app=db.startApp(MutableClock.SOURCE); api=new ApiClient(app.getWebServer().getPort()); }
    @AfterEach void stop() { if (app!=null) app.close(); if (db!=null) db.close(); }
    @Test void springRotationCsrfEpochAccountSwitchAndLogout() throws Exception {
        error(api.send("POST","/projects","{}"),401,"UNAUTHENTICATED");
        error(api.send("POST","/auth/accounts","{}"),403,"CSRF_INVALID");
        var boot=api.bootstrap(); String before=api.sessionCookie(),preToken=api.csrf;
        String cookie=boot.headers().firstValue("set-cookie").orElse("");
        // The earlier CSRF rejection may already have supplied the pre-auth cookie.
        if (cookie.isEmpty()) { var fresh=new ApiClient(app.getWebServer().getPort()); cookie=fresh.bootstrap().headers().firstValue("set-cookie").orElseThrow(); }
        assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("SameSite=Lax")); assertTrue(cookie.contains("Path=/"));
        assertFalse(cookie.toLowerCase(Locale.ROOT).contains("domain=")); assertFalse(cookie.contains("Max-Age")); assertFalse(cookie.contains("Expires=")); assertFalse(cookie.contains("Secure"));
        var account=api.register("  LEADER@example.invalid  "," Synthetic 🦉 ",PASSWORD); assertEquals(201,account.statusCode());
        assertEquals(Set.of("id","displayName"),tree(account).propertyNames()); assertEquals("Synthetic 🦉",tree(account).get("displayName").asText());
        error(api.send("GET","/auth/sessions/current",null),401,"UNAUTHENTICATED");
        error(api.register("leader@example.invalid","Other",PASSWORD),409,"ACCOUNT_UNAVAILABLE");
        assertEquals(201,api.register("other@example.invalid","Other",PASSWORD).statusCode());
        assertEquals(200,api.login("LEADER@example.invalid",PASSWORD).statusCode()); assertFalse(before.equals(api.sessionCookie()),"Session ID must rotate");
        String oldEpoch=api.epoch; UUID leader=api.id;
        error(api.send("POST","/projects","{\"name\":\"X\"}"),403,"CSRF_INVALID");
        api.bootstrap(); assertFalse(preToken.equals(api.csrf),"CSRF must rotate");
        var current=api.send("GET","/auth/sessions/current",null); assertEquals(200,current.statusCode());
        assertEquals(oldEpoch,current.headers().firstValue("X-Session-Epoch").orElseThrow());
        assertEquals(clock.instant().plusSeconds(1800).toString(),tree(current).get("expiresAtUtc").asText());
        String headerToken=api.csrf; api.csrf=null; api.epoch=null;
        error(api.send("POST","/projects?_csrf="+headerToken,"{}"),403,"CSRF_INVALID");
        error(api.send("POST","/projects","{}"),403,"CSRF_INVALID");
        api.bootstrap(); error(api.send("POST","/projects","{}"),400,"SESSION_CONTEXT_REQUIRED");
        api.epoch=UUID.randomUUID().toString(); error(api.send("POST","/projects","{}"),409,"SESSION_CHANGED");
        api.epoch=oldEpoch; assertEquals(200,api.login("other@example.invalid",PASSWORD).statusCode());
        assertNotEquals(leader,api.id); assertNotEquals(oldEpoch,api.epoch); String newEpoch=api.epoch;
        api.bootstrap(); api.epoch=oldEpoch;
        error(api.send("POST","/auth/sessions/current/activity","{}"),409,"SESSION_CHANGED");
        error(api.send("DELETE","/auth/sessions/current",null),409,"SESSION_CHANGED");
        api.epoch=newEpoch; String revoked=api.sessionCookie();
        var logout=api.send("DELETE","/auth/sessions/current",null); assertEquals(204,logout.statusCode()); assertTrue(logout.body().isEmpty());
        assertTrue(logout.headers().allValues("set-cookie").stream().anyMatch(c -> c.contains("JSESSIONID=") && (c.contains("Max-Age=0") || c.contains("Expires=Thu, 01 Jan 1970")) && c.contains("HttpOnly") && c.contains("SameSite=Lax")), () -> logout.headers().allValues("set-cookie").toString().replaceAll("JSESSIONID=[^;]*","JSESSIONID=<redacted>"));
        var replay=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(api.origin+"/api/v1/auth/sessions/current")).header("Cookie","JSESSIONID="+revoked).build(),HttpResponse.BodyHandlers.ofString());
        error(replay,401,"UNAUTHENTICATED");
        try (var c=db.runtime()) { assertEquals("leader@example.invalid",query(c,"SELECT normalized_email FROM users WHERE display_name='Synthetic 🦉'")); assertTrue(query(c,"SELECT password_hash FROM users LIMIT 1").startsWith("$2a$12$")); }
    }
    @Test void pollingBootstrapAndRestorationNeverExtendHumanDeadline() throws Exception {
        api.account("idle@example.invalid"); String expiry=tree(api.send("GET","/auth/sessions/current",null)).get("expiresAtUtc").asText();
        for (int i=0;i<5;i++) { clock.advance(300); assertEquals(200,api.send("GET","/health",null).statusCode()); assertEquals(200,api.send("GET","/me/projects",null).statusCode()); api.bootstrap(); assertEquals(expiry,tree(api.send("GET","/auth/sessions/current",null)).get("expiresAtUtc").asText()); }
        clock.advance(299); assertEquals(200,api.send("GET","/auth/sessions/current",null).statusCode());
        clock.advance(1); assertEquals(200,api.send("GET","/health",null).statusCode()); api.csrf=null; api.epoch=null;
        error(api.send("POST","/projects","{}"),401,"UNAUTHENTICATED");
        error(api.send("DELETE","/auth/sessions/current",null),401,"UNAUTHENTICATED");
        error(api.send("POST","/auth/sessions/current/activity","{}"),401,"UNAUTHENTICATED");
        api.bootstrap(); assertEquals(200,api.login("idle@example.invalid",PASSWORD).statusCode()); api.bootstrap();
        clock.advance(1799); var activity=api.send("POST","/auth/sessions/current/activity","{}"); assertEquals(200,activity.statusCode());
        assertEquals(clock.instant().plusSeconds(1800).toString(),tree(activity).get("expiresAtUtc").asText());
        clock.advance(1800); error(api.send("POST","/auth/sessions/current/activity","{}"),401,"UNAUTHENTICATED");
    }
    @Test void finalAtomicExpiryRecheckAndApiRestartDiscardSessions() throws Exception {
        api.account("recheck@example.invalid");
        clock.advanceOnCall(2,1800);
        error(api.send("POST","/projects",json(Map.of("name","Must not commit"))),401,"UNAUTHENTICATED");
        try(var c=db.runtime()) { assertEquals("0",query(c,"SELECT count(*) FROM projects")); assertEquals("0",query(c,"SELECT count(*) FROM activity_events")); }
        api.bootstrap(); assertEquals(200,api.login("recheck@example.invalid",PASSWORD).statusCode()); api.bootstrap();
        String oldCookie=api.sessionCookie();
        app.close(); app=db.startApp(MutableClock.SOURCE);
        var restored=new ApiClient(app.getWebServer().getPort());
        var replay=restored.raw("GET","/auth/sessions/current",new byte[0],null,null,null,Map.of("Cookie","JSESSIONID="+oldCookie));
        error(replay,401,"UNAUTHENTICATED");
        restored.bootstrap(); assertEquals(200,restored.login("recheck@example.invalid",PASSWORD).statusCode());
        assertNotEquals(api.epoch,restored.epoch);
    }
    @Test void activityRollingRateDoesNotExtendOnRejectedReports() throws Exception {
        api.account("activity@example.invalid");
        for (int i=0;i<30;i++) assertEquals(200,api.send("POST","/auth/sessions/current/activity","{}").statusCode());
        clock.advanceNanos(999900000);
        var fractional=api.send("POST","/auth/sessions/current/activity","{}"); error(fractional,429,"RATE_LIMITED");
        assertEquals("60",fractional.headers().firstValue("retry-after").orElseThrow());
        clock.advanceNanos(100000); var blocked=api.send("POST","/auth/sessions/current/activity","{}"); error(blocked,429,"RATE_LIMITED");
        assertEquals("59",blocked.headers().firstValue("retry-after").orElseThrow());
        assertEquals(Instant.parse("2026-10-10T10:30:00Z").toString(),tree(api.send("GET","/auth/sessions/current",null)).get("expiresAtUtc").asText());
        clock.advance(59); assertEquals(200,api.send("POST","/auth/sessions/current/activity","{}").statusCode());
        String epoch=api.epoch; api.epoch="stale"; clock.advance(10); error(api.send("POST","/auth/sessions/current/activity","{}"),409,"SESSION_CHANGED");
        api.epoch=epoch; assertEquals(Instant.parse("2026-10-10T10:31:00Z").toString(),tree(api.send("GET","/auth/sessions/current",null)).get("expiresAtUtc").asText());
    }
    @Test void strictJsonUtf8BodyStreamLimitsAndNoTypeCoercion() throws Exception {
        api.account("parser@example.invalid");
        for (String bad:List.of("{\"name\":\"X\",\"name\":\"Y\"}","{\"name\":\"X\",\"actorId\":\"spoof\"}","{\"name\":1}","{\"name\":true}","[]","null","{} {}","{", "{\"name\":\"\\ud800\"}","{\"name\":"+"[".repeat(17)+"0"+"]".repeat(17)+"}",json(Map.of("name","x".repeat(8001)))))
            { clock.advance(60); error(api.send("POST","/projects",bad),400,"MALFORMED_REQUEST"); }
        clock.advance(60);
        error(api.raw("POST","/projects",new byte[]{'{','"','n','a','m','e','"',':','"',(byte)0xc3,0x28,'"','}'},"application/json",api.csrf,api.epoch,Map.of()),400,"MALFORMED_REQUEST");
        for (String type:List.of("text/plain","application/json; charset=ISO-8859-1","application/problem+json"))
            { clock.advance(60); error(api.raw("POST","/projects","{}".getBytes(StandardCharsets.UTF_8),type,api.csrf,api.epoch,Map.of()),415,"UNSUPPORTED_MEDIA_TYPE"); }
        clock.advance(60);
        error(api.raw("POST","/projects","{}".getBytes(StandardCharsets.UTF_8),"application/json",api.csrf,api.epoch,Map.of("Content-Encoding","gzip")),400,"MALFORMED_REQUEST");
        String valid="{\"name\":\"Stream bound\"}"; byte[] exact=(valid+" ".repeat(65536-valid.length())).getBytes(StandardCharsets.UTF_8);
        assertEquals(201,api.raw("POST","/projects",exact,"application/json",api.csrf,api.epoch,Map.of()).statusCode());
        byte[] oversized=Arrays.copyOf(exact,65537); oversized[65536]=' ';
        clock.advance(60);
        error(api.raw("POST","/projects",oversized,"application/json",api.csrf,api.epoch,Map.of()),400,"REQUEST_TOO_LARGE");
        clock.advance(60);
        var chunked=HttpRequest.newBuilder(URI.create(api.origin+"/api/v1/projects")).header("Content-Type","application/json").header("X-CSRF-TOKEN",api.csrf).header("X-Session-Epoch",api.epoch)
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(oversized))).build();
        error(api.client.send(chunked,HttpResponse.BodyHandlers.ofString()),400,"REQUEST_TOO_LARGE");
        for(String read:List.of("/me/projects","/auth/sessions/current","/auth/sessions")) {
            error(api.send("GET",read,"{}"),400,"MALFORMED_REQUEST");
            error(api.raw("GET",read,oversized,"application/json",api.csrf,api.epoch,Map.of()),400,"REQUEST_TOO_LARGE");
        }
        error(api.send("POST","/auth/sessions/current/activity","{\"extra\":\"x\"}"),400,"MALFORMED_REQUEST");
        error(api.send("DELETE","/auth/sessions/current","{}"),400,"MALFORMED_REQUEST");
    }
    @Test void UnicodeScalarTextAndExactUtf8PasswordBounds() throws Exception {
        api.bootstrap();
        assertEquals(201,api.register("unicode@example.invalid","🦉".repeat(100),"é".repeat(6)).statusCode());
        assertEquals(201,api.register("a".repeat(250)+"@a.b","Synthetic",PASSWORD).statusCode());
        error(api.register("a".repeat(251)+"@a.b","Synthetic",PASSWORD),422,"VALIDATION_FAILED");
        error(api.register("longname@example.invalid","🦉".repeat(101),PASSWORD),422,"VALIDATION_FAILED");
        error(api.register("nul@example.invalid","N\0ul",PASSWORD),422,"VALIDATION_FAILED");
        error(api.register("short@example.invalid","Synthetic","é".repeat(5)),422,"VALIDATION_FAILED");
        assertEquals(201,api.register("maxpass@example.invalid","Synthetic","é".repeat(36)).statusCode());
        error(api.register("overpass@example.invalid","Synthetic","é".repeat(36)+"x"),422,"VALIDATION_FAILED");
        assertEquals(200,api.login("unicode@example.invalid","é".repeat(6)).statusCode()); api.bootstrap();
        assertEquals(201,api.send("POST","/projects",json(Map.of("name"," 🦉".repeat(100).strip(),"description","🦉".repeat(4000)))).statusCode());
        error(api.send("POST","/projects",json(Map.of("name","🦉".repeat(201)))),422,"VALIDATION_FAILED");
        error(api.send("POST","/projects",json(Map.of("name","X","description","x".repeat(4001)))),422,"VALIDATION_FAILED");
        var spaced=new ApiClient(app.getWebServer().getPort()); spaced.account("spacepass@example.invalid");
        error(spaced.login("spacepass@example.invalid",PASSWORD.strip()),401,"UNAUTHENTICATED");
        assertEquals(200,spaced.login("spacepass@example.invalid",PASSWORD).statusCode());
    }
    @Test void authAttemptWindowsGenericFailuresAndDirectPeerIp() throws Exception {
        api.bootstrap(); assertEquals(201,api.register("rate@example.invalid","Synthetic",PASSWORD).statusCode());
        for (int i=0;i<5;i++) error(api.login(" RATE@example.invalid ","wrong-password"),401,"UNAUTHENTICATED");
        var blocked=api.login("rate@example.invalid",PASSWORD); error(blocked,429,"RATE_LIMITED"); assertEquals("60",blocked.headers().firstValue("retry-after").orElseThrow());
        clock.advance(60); assertEquals(200,api.login("rate@example.invalid",PASSWORD).statusCode()); api.bootstrap();
        error(api.login("rate@example.invalid","wrong-password"),401,"UNAUTHENTICATED"); assertEquals(200,api.login("rate@example.invalid",PASSWORD).statusCode()); api.bootstrap();
        for (int i=0;i<5;i++) error(api.login("rate@example.invalid","wrong-password"),401,"UNAUTHENTICATED");
        error(api.login("rate@example.invalid","wrong-password"),429,"RATE_LIMITED");
        var missing=new ApiClient(app.getWebServer().getPort()); missing.bootstrap();
        var absent=missing.login("absent@example.invalid","wrong-password"); error(absent,401,"UNAUTHENTICATED"); assertEquals("Sign in could not be completed",tree(absent).get("message").asText());
        clock.advance(60);
        for (int i=0;i<20;i++) error(missing.raw("POST","/auth/accounts",json(Map.of("email","invalid@example.invalid","displayName","","password",PASSWORD)).getBytes(StandardCharsets.UTF_8),"application/json",missing.csrf,null,Map.of("X-Forwarded-For","192.0.2."+i)),422,"VALIDATION_FAILED");
        error(missing.register("different@example.invalid","Synthetic",PASSWORD),429,"RATE_LIMITED");
        clock.advance(60); assertEquals(201,missing.register("different@example.invalid","Synthetic",PASSWORD).statusCode());
        for(int i=0;i<19;i++) error(missing.send("POST","/auth/accounts","{"),400,"MALFORMED_REQUEST");
        error(missing.send("POST","/auth/accounts","{"),429,"RATE_LIMITED");
    }
}
