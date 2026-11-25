package dev.owlibi.web;

import static org.junit.jupiter.api.Assertions.*;
import static dev.owlibi.support.ApiClient.*;
import dev.owlibi.support.*;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

/** Real HTTP negotiation/method boundaries over an owned synthetic PostgreSQL fixture. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoutingBoundaryIT {
    private PostgresFixture db;
    private ServletWebServerApplicationContext app;
    private ApiClient leader,member,outsider,anonymous;
    private UUID project;
    private final List<Map<String,Object>> observations=new ArrayList<>();
    @BeforeAll void start() throws Exception {
        db=new PostgresFixture();app=db.startApp();int port=app.getWebServer().getPort();
        leader=new ApiClient(port);leader.account("routing-leader@example.invalid");
        member=new ApiClient(port);member.account("routing-member@example.invalid");
        outsider=new ApiClient(port);outsider.account("routing-outsider@example.invalid");anonymous=new ApiClient(port);
        project=leader.project("Synthetic routing regression");assertEquals(200,member.accept(leader.invitation(project)).statusCode());
        var settings=JSON.convertValue(tree(leader.send("GET","/templates/blank",null)).get("suggestedSettings"),LinkedHashMap.class);
        settings.put("settingsVersion","0");assertEquals(200,leader.send("PATCH",root()+"/settings",json(settings)).statusCode());
    }
    @AfterAll void stop() throws Exception {
        try {
            Path out=Path.of("target/backend-fix-captures");Files.createDirectories(out);
            Files.writeString(out.resolve("routing.json"),json(Map.of("scope","Synthetic real HTTP; no credentials or session tokens retained","observations",observations)));
        } finally { try { if(app!=null) app.close(); } finally { if(db!=null) db.close(); } }
    }
    private String root() { return "/projects/"+project; }
    private HttpResponse<String> call(ApiClient api,String method,String path,String body,String accept) throws Exception {
        var response=api.raw(method,path,body==null?new byte[0]:body.getBytes(java.nio.charset.StandardCharsets.UTF_8),"application/json",api.csrf,api.epoch,
            accept==null?Map.of():Map.of("Accept",accept));
        observations.add(Map.of("method",method,"path",path,"accept",accept==null?"*/*":accept,"status",response.statusCode(),
            "contentType",response.headers().firstValue("content-type").orElse(""),"allow",response.headers().firstValue("allow").orElse("")));
        assertFalse(response.headers().firstValue("access-control-allow-origin").isPresent());return response;
    }
    private static Set<String> allowed(HttpResponse<?> response) {
        var result=new HashSet<String>();for(String method:response.headers().firstValue("allow").orElseThrow().split(",")) result.add(method.strip());return result;
    }
    @Test void routing_01UnsupportedFormatsStaySafeWithoutOpeningErrorOrProtectedRoutes() throws Exception {
        for(String accept:List.of("application/xml","text/plain")) {
            error(call(anonymous,"GET","/health",null,accept),406,"NOT_ACCEPTABLE");
            for(String path:List.of("/templates","/auth/sessions/current",root()+"/settings",root()+"/contribution-summary"))
                error(call(leader,"GET",path,null,accept),406,"NOT_ACCEPTABLE");
            error(call(anonymous,"GET",root()+"/settings",null,accept),401,"UNAUTHENTICATED");
        }
        assertEquals(200,call(anonymous,"GET","/health",null,"text/html,*/*;q=0.8").statusCode());
        error(call(member,"PATCH",root()+"/settings","{\"settingsVersion\":\"1\"}","application/json"),403,"FORBIDDEN");
        error(call(outsider,"GET",root()+"/settings",null,"application/json"),404,"NOT_FOUND");
        error(call(leader,"GET","/missing",null,"application/xml"),404,"NOT_FOUND");
        var direct=leader.client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(leader.origin+"/error")).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        error(direct,403,"FORBIDDEN");assertEquals(200,leader.send("GET","/auth/sessions/current",null).statusCode());
    }
    @Test void routing_02AccurateAllowHeadersPreserveReportHeadRefusalAndUnsafeGuards() throws Exception {
        var options=call(leader,"OPTIONS",root()+"/professor-report",null,null);assertEquals(200,options.statusCode());assertEquals(Set.of("GET","OPTIONS"),allowed(options));
        for(String method:List.of("HEAD","POST","PUT","PATCH","DELETE")) {
            var refused=call(leader,method,root()+"/professor-report",null,null);assertEquals(405,refused.statusCode());
            assertEquals(Set.of("GET","OPTIONS"),allowed(refused));assertFalse(refused.headers().firstValue("content-disposition").isPresent());
            if(!method.equals("HEAD")) error(refused,405,"MALFORMED_REQUEST");else assertTrue(refused.body().isEmpty());
        }
        var settings=call(leader,"POST",root()+"/settings","{}",null);error(settings,405,"MALFORMED_REQUEST");
        assertEquals(Set.of("GET","HEAD","PATCH","OPTIONS"),allowed(settings));
        assertEquals(200,call(leader,"HEAD",root()+"/settings",null,null).statusCode());
        assertEquals(200,call(leader,"PATCH",root()+"/settings","{\"settingsVersion\":\"1\"}",null).statusCode());
        var health=call(leader,"POST","/health","{}",null);error(health,405,"MALFORMED_REQUEST");assertEquals(Set.of("GET","HEAD","OPTIONS"),allowed(health));
        error(leader.raw("POST",root()+"/professor-report",new byte[0],null,null,leader.epoch,Map.of()),403,"CSRF_INVALID");
        error(call(anonymous,"OPTIONS",root()+"/professor-report",null,null),401,"UNAUTHENTICATED");
    }
    @Test void routing_03UnacceptableUnsafeResponseFormatCannotCommitHiddenWrites() throws Exception {
        String before;try(var c=db.runtime()) { before=PostgresFixture.query(c,"SELECT (SELECT count(*) FROM projects)||':'||(SELECT count(*) FROM tasks)||':'||(SELECT count(*) FROM project_invitations)||':'||(SELECT count(*) FROM users)||':'||(SELECT count(*) FROM activity_events)"); }
        var current=tree(leader.send("GET","/auth/sessions/current",null));String epoch=leader.epoch,cookie=leader.sessionCookie();
        error(call(leader,"POST","/projects","{\"name\":\"Must not commit\"}","application/xml"),406,"NOT_ACCEPTABLE");
        error(call(leader,"PATCH",root()+"/settings","{\"settingsVersion\":\"1\",\"nearDueDays\":8}","application/xml"),406,"NOT_ACCEPTABLE");
        error(call(leader,"POST",root()+"/tasks",json(Map.of("title","Must not commit","effortPoints","1.00","assigneeIds",List.of(leader.id))),"application/xml"),406,"NOT_ACCEPTABLE");
        error(call(leader,"POST",root()+"/invitations",null,"application/xml"),406,"NOT_ACCEPTABLE");
        error(call(leader,"POST","/auth/sessions/current/activity","{}","application/xml"),406,"NOT_ACCEPTABLE");
        error(call(leader,"DELETE","/auth/sessions/current",null,"application/xml"),406,"NOT_ACCEPTABLE");
        anonymous.bootstrap();error(call(anonymous,"POST","/auth/accounts",json(Map.of("email","must-not-exist@example.invalid","displayName","Synthetic","password",PASSWORD)),"application/xml"),406,"NOT_ACCEPTABLE");
        try(var c=db.runtime()) { assertEquals(before,PostgresFixture.query(c,"SELECT (SELECT count(*) FROM projects)||':'||(SELECT count(*) FROM tasks)||':'||(SELECT count(*) FROM project_invitations)||':'||(SELECT count(*) FROM users)||':'||(SELECT count(*) FROM activity_events)")); }
        assertEquals("1",tree(leader.send("GET",root()+"/settings",null)).get("settingsVersion").asText());
        assertEquals(current.get("expiresAtUtc"),tree(leader.send("GET","/auth/sessions/current",null)).get("expiresAtUtc"));
        assertTrue(epoch.equals(leader.epoch));assertTrue(cookie.equals(leader.sessionCookie()));
    }
    @Test void routing_04PrototypeStyleExceptionNeverWeakensWorkingAppOrApi() throws Exception {
        for(String path:List.of("/","/styles.css","/api/v1/health","/api/v1/auth/sessions/current")) {
            var response=leader.client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(leader.origin+path)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());String policy=response.headers().firstValue("content-security-policy").orElseThrow();
            assertTrue(policy.contains("style-src 'self';"));assertFalse(policy.contains("unsafe-inline"));assertFalse(policy.contains("unsafe-eval"));
        }
        for(String path:List.of("/mockup/","/mockup/index.html","/mockup/mockup.css","/mockup/mockup.js")) {
            var response=anonymous.client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(anonymous.origin+path)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());String policy=response.headers().firstValue("content-security-policy").orElseThrow();
            assertTrue(policy.contains("script-src 'self';"));assertTrue(policy.contains("style-src 'self' 'unsafe-inline';"));
            if(path.endsWith("/") || path.endsWith(".html")) assertTrue(response.body().contains("design prototype · fictional data"));
        }
        var outside=anonymous.client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(anonymous.origin+"/mockup-other")).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(401,outside.statusCode());assertFalse(outside.headers().firstValue("content-security-policy").orElseThrow().contains("unsafe-inline"));
    }

}
