package dev.owlibi.reference.projects;

import static org.junit.jupiter.api.Assertions.*;
import static dev.owlibi.reference.support.ApiClient.*;
import static dev.owlibi.reference.support.PostgresFixture.*;
import dev.owlibi.reference.support.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class ProjectBoundaryIT {
    private PostgresFixture db;
    private ServletWebServerApplicationContext app;
    private ApiClient leader;
    private JdbcTemplate jdbc;
    private final MutableClock clock=MutableClock.INSTANCE;
    @BeforeEach void start() throws Exception {
        clock.reset(); db=new PostgresFixture(); app=db.startApp(MutableClock.SOURCE); jdbc=app.getBean(JdbcTemplate.class);
        leader=account("leader@example.invalid");
    }
    @AfterEach void stop() { if(app!=null) app.close(); if(db!=null) db.close(); }
    private ApiClient account(String email) throws Exception { var api=new ApiClient(app.getWebServer().getPort()); api.account(email); return api; }
    private int count(String table,UUID project) { return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Integer.class,project); }
    @Test void atomicBlankProjectsKeysetPaginationIsolationAndCreationRate() throws Exception {
        var outsider=account("outside@example.invalid"); List<UUID> ids=new ArrayList<>();
        for(int i=0;i<5;i++) ids.add(leader.project("Synthetic "+i+" ' ; SELECT 1"));
        var limited=leader.send("POST","/projects",json(Map.of("name","Sixth"))); error(limited,429,"RATE_LIMITED"); assertEquals("60",limited.headers().firstValue("retry-after").orElseThrow());
        assertEquals(0,tree(outsider.send("GET","/me/projects",null)).get("items").size());
        UUID outsiderProject=outsider.project("Outside");
        Set<UUID> seen=new HashSet<>(); String cursor=null; UUID previous=null;
        do {
            var response=leader.send("GET","/me/projects?limit=2"+(cursor==null?"":"&cursor="+cursor),null); assertEquals(200,response.statusCode()); var body=tree(response);
            assertEquals(Set.of("items","nextCursor"),body.propertyNames());
            for(var item:body.get("items")) {
                UUID id=UUID.fromString(item.get("id").asText()); assertTrue(seen.add(id)); assertNotEquals(outsiderProject,id);
                assertEquals("LEADER",item.get("role").asText()); assertEquals("",item.get("description").asText());
                assertEquals(Instant.parse("2026-10-10T10:00:00Z").toString(),item.get("createdAtUtc").asText());
                if(previous!=null) assertTrue(previous.toString().compareTo(id.toString())>0); previous=id;
                assertEquals(Set.of("id","name","description","role","createdAtUtc"),item.propertyNames());
            }
            cursor=body.get("nextCursor").isNull()?null:body.get("nextCursor").asText();
        } while(cursor!=null);
        assertEquals(Set.copyOf(ids),seen);
        for(String query:List.of("limit=0","limit=101","limit=-1","limit=1.0","limit=","cursor=","cursor=bad","cursor="+"a".repeat(513),"cursor="+Base64.getUrlEncoder().withoutPadding().encodeToString("2|2026-10-10T10:00:00Z|00000000-0000-0000-0000-000000000000".getBytes())))
            error(leader.send("GET","/me/projects?"+query,null),400,"MALFORMED_REQUEST");
        for(String extreme:List.of("+999999999-12-31T23:59:59Z","+999999-12-31T23:59:59Z")) {
            String value=Base64.getUrlEncoder().withoutPadding().encodeToString(("1|"+extreme+"|"+UUID.randomUUID()).getBytes());
            error(leader.send("GET","/me/projects?cursor="+value,null),400,"MALFORMED_REQUEST");
        }
        var first=tree(leader.send("GET","/me/projects?limit=1",null)); String foreign=first.get("nextCursor").asText();
        var outsiderPage=tree(outsider.send("GET","/me/projects?cursor="+foreign,null));
        for(var item:outsiderPage.get("items")) assertEquals(outsiderProject.toString(),item.get("id").asText());
        clock.advance(60); leader.project("Window released");
        assertEquals(6,jdbc.queryForObject("SELECT count(*) FROM projects WHERE leader_id=?",Integer.class,leader.id));
        for(UUID project:ids) {
            assertEquals(1,count("project_memberships",project)); assertEquals(1,count("activity_events",project));
            assertEquals("blank",jdbc.queryForObject("SELECT template_snapshot->'template'->>'id' FROM projects WHERE id=?",String.class,project));
            assertEquals("2",jdbc.queryForObject("SELECT template_snapshot->'template'->>'version' FROM projects WHERE id=?",String.class,project));
            assertEquals(0,jdbc.queryForObject("SELECT settings_version FROM projects WHERE id=?",Long.class,project));
            assertEquals("Asia/Manila",jdbc.queryForObject("SELECT project_zone FROM projects WHERE id=?",String.class,project));
        }
    }
    @Test void scopedInvitationOneTimeSecretHashExpiryAndAbsentWorkflowRoutes() throws Exception {
        UUID project=leader.project("Private"); var outsider=account("outside@example.invalid");
        error(outsider.send("POST","/invitations/accept","{}"),422,"VALIDATION_FAILED");
        error(outsider.send("POST","/invitations/accept","{\"token\":null}"),422,"VALIDATION_FAILED");
        error(outsider.send("POST","/projects/"+project+"/invitations",null),404,"NOT_FOUND");
        error(leader.send("POST","/projects/"+UUID.randomUUID()+"/invitations",null),404,"NOT_FOUND");
        error(leader.send("POST","/projects/not-a-uuid/invitations",null),400,"MALFORMED_REQUEST");
        var invitation=leader.send("POST","/projects/"+project+"/invitations",null); assertEquals(201,invitation.statusCode());
        var body=tree(invitation); assertEquals(Set.of("token","expiresAtUtc"),body.propertyNames()); String token=body.get("token").asText();
        byte[] entropy=Base64.getUrlDecoder().decode(token); assertEquals(32,entropy.length); assertEquals(43,token.length());
        assertEquals(clock.instant().plusSeconds(86400).toString(),body.get("expiresAtUtc").asText());
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(entropy),jdbc.queryForObject("SELECT token_hash FROM project_invitations WHERE project_id=?",byte[].class,project));
        assertEquals(200,outsider.accept(token).statusCode());
        error(outsider.send("POST","/projects/"+project+"/invitations",null),403,"FORBIDDEN");
        error(outsider.accept(token),409,"INVITATION_UNAVAILABLE"); error(leader.accept(token),409,"INVITATION_UNAVAILABLE");
        String existing=leader.invitation(project); error(outsider.accept(existing),409,"INVITATION_UNAVAILABLE");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM project_invitations WHERE project_id=? AND accepted_at_utc IS NULL",Integer.class,project));
        String expiring=leader.invitation(project);
        clock.advance(86400); error(outsider.send("GET","/auth/sessions/current",null),401,"UNAUTHENTICATED");
        outsider.bootstrap(); assertEquals(200,outsider.login("outside@example.invalid",PASSWORD).statusCode()); outsider.bootstrap();
        var expired=outsider.accept(expiring); var unknown=outsider.accept("A".repeat(43)); error(expired,409,"INVITATION_UNAVAILABLE"); error(unknown,409,"INVITATION_UNAVAILABLE"); assertEquals(expired.body(),unknown.body());
        assertEquals(2,count("project_memberships",project)); assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM activity_events WHERE project_id=? AND event_type='MEMBER_JOINED'",Integer.class,project));
        assertEquals(1,tree(outsider.send("GET","/me/projects",null)).get("items").size());
        error(outsider.send("POST","/projects/"+project+"/tasks","{}"),403,"FORBIDDEN");
        assertEquals(200,outsider.send("GET","/projects/"+project+"/board",null).statusCode());
        String events=jdbc.queryForObject("SELECT jsonb_agg(change_summary)::text FROM activity_events WHERE project_id=?",String.class,project);
        for(String secret:List.of(token,existing,expiring,PASSWORD,"@example.invalid","token_hash","password_hash")) assertFalse(events.contains(secret));
    }
    @Test void concurrentSameTokenHasExactlyOneWinner() throws Exception {
        UUID project=leader.project("One use"); String token=leader.invitation(project);
        var a=account("race-a@example.invalid"); var b=account("race-b@example.invalid");
        var results=race(() -> a.accept(token),() -> b.accept(token));
        assertEquals(List.of(200,409),results.stream().map(java.net.http.HttpResponse::statusCode).sorted().toList());
        results.stream().filter(r -> r.statusCode()==409).forEach(r -> error(r,409,"INVITATION_UNAVAILABLE"));
        assertEquals(2,count("project_memberships",project)); assertEquals(3,count("activity_events",project));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM project_invitations WHERE project_id=? AND accepted_at_utc IS NOT NULL AND accepted_by IS NOT NULL",Integer.class,project));
    }
    @Test void concurrentDifferentInvitationsRespectSixIncludingLeaderWithoutConsumingLoser() throws Exception {
        UUID project=leader.project("Capacity");
        for(int i=0;i<4;i++) { String token=leader.invitation(project); assertEquals(200,account("member-"+i+"@example.invalid").accept(token).statusCode()); }
        assertEquals(5,count("project_memberships",project));
        var a=account("cap-a@example.invalid"); var b=account("cap-b@example.invalid"); String ta=leader.invitation(project),tb=leader.invitation(project);
        int before=count("activity_events",project);
        var results=race(() -> a.accept(ta),() -> b.accept(tb));
        assertEquals(List.of(200,409),results.stream().map(java.net.http.HttpResponse::statusCode).sorted().toList());
        results.stream().filter(r -> r.statusCode()==409).forEach(r -> error(r,409,"PROJECT_FULL"));
        assertEquals(6,count("project_memberships",project)); assertEquals(before+1,count("activity_events",project));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM project_invitations WHERE project_id=? AND accepted_at_utc IS NULL",Integer.class,project));
        error(leader.send("POST","/projects/"+project+"/invitations",null),409,"PROJECT_FULL");
        var loser=results.get(0).statusCode()==409?a:b; String remaining=results.get(0).statusCode()==409?ta:tb;
        error(loser.accept(remaining),409,"PROJECT_FULL"); assertEquals(before+1,count("activity_events",project));
        assertEquals(0,tree(loser.send("GET","/me/projects",null)).get("items").size());
    }
    @Test void eventFailureRollsBackProjectInvitationAndRedemptionThenRetryIsDeliberate() throws Exception {
        UUID project=leader.project("Rollback"); String token=leader.invitation(project); var member=account("rollback@example.invalid");
        try(var c=db.migrator()) {
            sql(c,"CREATE FUNCTION synthetic_reject_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic fixture failure'; END $$");
            sql(c,"CREATE TRIGGER synthetic_event_failure BEFORE INSERT ON activity_events FOR EACH ROW EXECUTE FUNCTION synthetic_reject_event()");
        }
        int projects=jdbc.queryForObject("SELECT count(*) FROM projects",Integer.class),events=count("activity_events",project);
        error(leader.send("POST","/projects",json(Map.of("name","Must roll back"))),503,"SERVICE_UNAVAILABLE");
        assertEquals(projects,jdbc.queryForObject("SELECT count(*) FROM projects",Integer.class)); assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM project_memberships",Integer.class));
        error(leader.send("POST","/projects/"+project+"/invitations",null),503,"SERVICE_UNAVAILABLE");
        assertEquals(1,count("project_invitations",project));
        error(member.accept(token),503,"SERVICE_UNAVAILABLE");
        assertEquals(1,count("project_memberships",project)); assertEquals(events,count("activity_events",project));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM project_invitations WHERE accepted_at_utc IS NOT NULL",Integer.class));
        try(var c=db.migrator()) { sql(c,"DROP TRIGGER synthetic_event_failure ON activity_events"); sql(c,"DROP FUNCTION synthetic_reject_event()"); }
        assertEquals(200,member.accept(token).statusCode()); assertEquals(2,count("project_memberships",project)); assertEquals(events+1,count("activity_events",project));
    }
    @Test void invitationCreationOutstandingAndRedemptionRollingLimits() throws Exception {
        UUID project=leader.project("Bounded invitations");
        for(int batch=0;batch<5;batch++) {
            for(int i=0;i<20;i++) leader.invitation(project);
            error(leader.send("POST","/projects/"+project+"/invitations",null),429,"RATE_LIMITED"); clock.advance(60);
        }
        assertEquals(100,count("project_invitations",project));
        error(leader.send("POST","/projects/"+project+"/invitations",null),422,"INVITATION_LIMIT");
        for(int i=0;i<20;i++) error(leader.accept("unknown"),409,"INVITATION_UNAVAILABLE");
        var blocked=leader.accept("unknown"); error(blocked,429,"RATE_LIMITED"); assertEquals("60",blocked.headers().firstValue("retry-after").orElseThrow());
        clock.advance(60); error(leader.accept("unknown"),409,"INVITATION_UNAVAILABLE");
        for(int i=0;i<19;i++) error(leader.send("POST","/invitations/accept","{"),400,"MALFORMED_REQUEST");
        error(leader.accept("unknown"),429,"RATE_LIMITED");
        clock.advance(86400); error(leader.send("GET","/auth/sessions/current",null),401,"UNAUTHENTICATED");
        leader.bootstrap(); assertEquals(200,leader.login("leader@example.invalid",PASSWORD).statusCode()); leader.bootstrap(); leader.invitation(project);
        assertEquals(101,count("project_invitations",project)); // Expired rows preserved, only outstanding ones are bounded.
        assertThrows(RuntimeException.class,() -> { try(var invalid=db.startApp("--owlibi.invitation-ttl=PT0S")) { fail("Invalid TTL started"); } });
        app.close(); app=db.startApp(MutableClock.SOURCE,"--owlibi.invitation-ttl=PT0.000000001S");
        leader=new ApiClient(app.getWebServer().getPort()); leader.bootstrap();
        assertEquals(200,leader.login("leader@example.invalid",PASSWORD).statusCode()); leader.bootstrap();
        var tiny=account("tiny-ttl@example.invalid");
        var created=leader.send("POST","/projects/"+project+"/invitations",null); assertEquals(201,created.statusCode());
        assertEquals(clock.instant().plusNanos(1000).toString(),tree(created).get("expiresAtUtc").asText());
        clock.advanceNanos(1000); error(tiny.accept(tree(created).get("token").asText()),409,"INVITATION_UNAVAILABLE");
    }
    private static List<java.net.http.HttpResponse<String>> race(Callable<java.net.http.HttpResponse<String>> a,Callable<java.net.http.HttpResponse<String>> b) throws Exception {
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(() -> { barrier.await(5,TimeUnit.SECONDS); return a.call(); });
            var two=pool.submit(() -> { barrier.await(5,TimeUnit.SECONDS); return b.call(); });
            return List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));
        }
    }
}
