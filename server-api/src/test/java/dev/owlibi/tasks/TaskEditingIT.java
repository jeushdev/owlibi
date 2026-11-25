package dev.owlibi.tasks;

import static org.junit.jupiter.api.Assertions.*;
import static dev.owlibi.support.ApiClient.*;
import static dev.owlibi.support.PostgresFixture.*;
import dev.owlibi.support.*;
import dev.owlibi.web.Cursor;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/** Real HTTP through the unchanged Security boundary and restricted PostgreSQL runtime. */
class TaskEditingIT {
    private PostgresFixture db;
    private ServletWebServerApplicationContext app;
    private JdbcTemplate jdbc;
    private ApiClient leader,a,b;
    private UUID project;
    private final MutableClock clock=MutableClock.INSTANCE;
    @BeforeEach void start() throws Exception {
        clock.reset(); db=new PostgresFixture(); app=db.startApp(BoardSnapshotProbe.SOURCE); jdbc=app.getBean(JdbcTemplate.class);
        leader=account("leader@example.invalid"); a=account("a@example.invalid"); b=account("b@example.invalid");
        project=leader.project("Synthetic task project");
        assertEquals(200,a.accept(leader.invitation(project)).statusCode()); assertEquals(200,b.accept(leader.invitation(project)).statusCode());
    }
    @AfterEach void stop() { if(app!=null) app.close(); if(db!=null) db.close(); }
    private ApiClient account(String email) throws Exception { var api=new ApiClient(app.getWebServer().getPort()); api.account(email); return api; }
    private String root() { return "/projects/"+project; }
    private Map<String,Object> fields() {
        return new HashMap<>(Map.of("title"," Synthetic task ","effortPoints","12.30","assigneeIds",List.of(a.id,b.id)));
    }
    private JsonNode create(Map<String,Object> fields) throws Exception {
        var response=leader.send("POST",root()+"/tasks",json(fields)); assertEquals(201,response.statusCode(),response.body()); return tree(response);
    }
    private JsonNode create() throws Exception { return create(fields()); }
    private HttpResponse<String> accept(ApiClient actor,JsonNode task,long agreement) throws Exception {
        return actor.send("POST",root()+"/tasks/"+task.get("id").asText()+"/acceptance",json(Map.of("agreementVersion",agreement)));
    }
    private JsonNode accepted(JsonNode task) throws Exception {
        assertEquals(200,accept(a,task,1).statusCode()); var response=accept(b,task,1); assertEquals(200,response.statusCode()); return current(task);
    }
    private HttpResponse<String> action(ApiClient actor,JsonNode task,String action,long version,String reason) throws Exception {
        var body=new HashMap<String,Object>(); body.put("action",action); body.put("version",version); if(reason!=null) body.put("reason",reason);
        return actor.send("POST",root()+"/tasks/"+task.get("id").asText()+"/transitions",json(body));
    }
    private JsonNode move(ApiClient actor,JsonNode task,String action,String reason) throws Exception {
        var response=action(actor,task,action,task.get("version").asLong(),reason); assertEquals(200,response.statusCode(),response.body()); return tree(response);
    }
    private JsonNode inState(String state) throws Exception {
        JsonNode task=accepted(create());
        if(state.equals("TODO")) return task;
        task=move(a,task,"START",null);
        if(state.equals("IN_PROGRESS")) return task;
        if(state.equals("CANCELLED")) return move(leader,task,"CANCEL","Synthetic cancellation");
        task=move(b,task,"SUBMIT_REVIEW",null);
        return state.equals("REVIEW")?task:move(leader,task,"APPROVE",null);
    }
    private int events() { return jdbc.queryForObject("SELECT count(*) FROM activity_events WHERE project_id=?",Integer.class,project); }
    private int taskEvents(JsonNode task) { return jdbc.queryForObject("SELECT count(*) FROM activity_events WHERE task_id=?",Integer.class,UUID.fromString(task.get("id").asText())); }
    private JsonNode board(ApiClient actor) throws Exception { var response=actor.send("GET",root()+"/board",null); assertEquals(200,response.statusCode()); return tree(response); }
    private JsonNode current(JsonNode task) throws Exception {
        for(JsonNode item:board(leader).get("tasks")) if(item.get("id").equals(task.get("id"))) return item;
        throw new AssertionError("Missing task");
    }


    private HttpResponse<String> patch(ApiClient actor,JsonNode task,Map<String,Object> fields) throws Exception {
        return actor.send("PATCH",root()+"/tasks/"+task.get("id").asText(),json(fields));
    }
    private JsonNode edit(JsonNode task,Map<String,Object> fields) throws Exception {
        var body=new HashMap<>(fields); body.put("version",task.get("version").asLong()); var response=patch(leader,task,body);
        assertEquals(200,response.statusCode(),response.body()); return tree(response);
    }
    @Test void sparseNullMetadataPreservesAcceptanceAndLogsSafeSummary() throws Exception {
        var values=fields(); values.put("category","Category"); values.put("dueDate","2026-10-31"); JsonNode old=accepted(create(values));
        var cleared=new HashMap<String,Object>(); cleared.put("category",null); cleared.put("dueDate",null);
        JsonNode task=edit(old,cleared); assertEquals("",task.get("category").asText()); assertTrue(task.get("dueDate").isNull());
        for(String field:List.of("title","description","status","agreementVersion","assigneeIds","assigneeAcceptances","firstStartedAtUtc","createdBy","createdAtUtc")) assertEquals(old.get(field),task.get(field),field);
        assertEquals(3,task.get("version").asLong()); assertEquals(4,taskEvents(task));
        var event=jdbc.queryForMap("SELECT actor_id,reason,change_summary::text AS summary FROM activity_events WHERE task_id=? AND event_type='TASK_EDITED'",UUID.fromString(task.get("id").asText()));
        assertEquals(leader.id,event.get("actor_id")); assertNull(event.get("reason")); var summary=JSON.readTree((String)event.get("summary"));
        assertEquals(Set.of("before","after","changedFields"),summary.propertyNames()); assertEquals(JSON.valueToTree(List.of("category","dueDate")),summary.get("changedFields"));
        for(String secret:List.of(PASSWORD,leader.csrf,leader.sessionCookie(),"jdbc:","token_hash")) assertFalse(summary.toString().contains(secret));
    }
    @Test void multipleTermsReviseOnceAndAllCurrentAssigneesReaccept() throws Exception {
        JsonNode old=accepted(create()); JsonNode task=edit(old,Map.of("title"," Revised 🦉 ","description","Full changed scope","effortPoints","15.3"));
        assertEquals("Revised 🦉",task.get("title").asText()); assertEquals("15.30",task.get("effortPoints").asText()); assertEquals(2,task.get("agreementVersion").asLong()); assertEquals(3,task.get("version").asLong());
        for(var item:task.get("assigneeAcceptances")) { assertFalse(item.get("accepted").asBoolean()); assertTrue(item.get("acceptedAtUtc").isNull()); }
        error(action(leader,task,"START",3,null),422,"ACCEPTANCE_REQUIRED"); error(accept(a,task,1),409,"STALE_AGREEMENT");
        task=tree(accept(a,task,2)); error(action(a,task,"START",task.get("version").asLong(),null),422,"ACCEPTANCE_REQUIRED");
        task=tree(accept(b,task,2)); assertEquals("IN_PROGRESS",move(a,task,"START",null).get("status").asText());
    }
    @Test void reassignmentDeletesRemovedAddsNewAndRetainsAssignmentTimestamp() throws Exception {
        JsonNode old=accepted(create()); var id=UUID.fromString(old.get("id").asText());
        var assigned=jdbc.queryForObject("SELECT assigned_at_utc FROM task_assignees WHERE task_id=? AND user_id=?",java.sql.Timestamp.class,id,b.id);
        clock.advance(10); JsonNode task=edit(old,Map.of("assigneeIds",List.of(b.id,leader.id)));
        assertEquals(2,task.get("agreementVersion").asLong()); assertEquals(assigned,jdbc.queryForObject("SELECT assigned_at_utc FROM task_assignees WHERE task_id=? AND user_id=?",java.sql.Timestamp.class,id,b.id));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM task_assignees WHERE task_id=? AND user_id=?",Integer.class,id,a.id));
        assertEquals(java.sql.Timestamp.from(clock.instant()),jdbc.queryForObject("SELECT assigned_at_utc FROM task_assignees WHERE task_id=? AND user_id=?",java.sql.Timestamp.class,id,leader.id));
        error(accept(a,task,2),403,"FORBIDDEN"); task=tree(accept(b,task,2)); task=tree(accept(leader,task,2)); assertTrue(task.get("currentUserAcceptedAgreement").asBoolean());
    }
    @Test void normalizationNoopsPreserveEntireDtoAndEveryPersistedValue() throws Exception {
        JsonNode task=accepted(create()); int count=events();
        JsonNode same=edit(task,Map.of("title","  Synthetic task  ","effortPoints","00012.3","assigneeIds",List.of(b.id.toString().toUpperCase(Locale.ROOT),a.id.toString())));
        assertEquals(task,same); assertEquals(task,edit(task,Map.of())); assertEquals(task,edit(task,Map.of("reason","unused explanation"))); assertEquals(count,events());
        JsonNode changed=edit(task,Map.of("description"," ")); assertEquals(2,changed.get("agreementVersion").asLong());
        var nullDescription=new HashMap<String,Object>(); nullDescription.put("description",null); assertEquals("",edit(changed,nullDescription).get("description").asText());
    }
    @Test void authorizationAndOwnershipBeforeProtectedValidation() throws Exception {
        JsonNode task=create(); int count=events();
        error(a.send("PATCH",root()+"/tasks/"+task.get("id").asText(),"not json"),403,"FORBIDDEN");
        var outsider=account("outsider@example.invalid"); error(patch(outsider,task,Map.of("version",0)),404,"NOT_FOUND");
        var another=leader.project("Other synthetic project"); error(leader.send("PATCH","/projects/"+another+"/tasks/"+task.get("id").asText(),"{\"version\":0}"),404,"NOT_FOUND");
        var anonymous=new ApiClient(app.getWebServer().getPort()); error(anonymous.send("PATCH",root()+"/tasks/"+task.get("id").asText(),"{}"),401,"UNAUTHENTICATED"); assertEquals(count,events());
    }
    @Test void strictTypesUnknownImmutableDuplicatesAndSemanticValidation() throws Exception {
        JsonNode task=create(); String path=root()+"/tasks/"+task.get("id").asText(); int count=events();
        for(String key:List.of("status","agreementVersion","id","projectId","actorId","createdAtUtc","firstStartedAtUtc","accepted","creditedPoints","unknown")) error(leader.send("PATCH",path,"{\"version\":0,\""+key+"\":null}"),400,"MALFORMED_REQUEST");
        for(String input:List.of("{\"version\":0,\"version\":0}","{\"version\":0}{}","{\"version\":\"0\"}","{\"version\":0.5}","{\"version\":0,\"effortPoints\":1}","{\"version\":0,\"assigneeIds\":[null]}")) error(leader.send("PATCH",path,input),400,"MALFORMED_REQUEST");
        for(String input:List.of("{}","{\"version\":null}","{\"version\":-1}","{\"version\":0,\"title\":null}","{\"version\":0,\"effortPoints\":null}","{\"version\":0,\"assigneeIds\":null}")) error(leader.send("PATCH",path,input),422,"VALIDATION_FAILED");
        var invalid=new LinkedHashMap<String,List<Object>>(); invalid.put("title",List.of(" ","🦉".repeat(201))); invalid.put("description",List.of("x".repeat(4001),"\0")); invalid.put("category",List.of("x".repeat(101))); invalid.put("reason",List.of("x".repeat(1001))); invalid.put("effortPoints",List.of("0","1.234","1e2","100000000"," 1")); invalid.put("dueDate",List.of("2026-02-30","2026-1-01","")); invalid.put("assigneeIds",List.of(List.of(),List.of(a.id,a.id),List.of(UUID.randomUUID())));
        for(var field:invalid.entrySet()) for(Object value:field.getValue()) error(patch(leader,task,Map.of("version",0,field.getKey(),value)),422,"VALIDATION_FAILED");
        assertEquals(task,current(task)); assertEquals(count,events());
    }
    @Test void afterFirstStartReasonPersistsAcrossReopenAndMetadataNeedsNone() throws Exception {
        JsonNode task=inState("DONE"); String first=task.get("firstStartedAtUtc").asText(); error(patch(leader,task,Map.of("version",task.get("version").asLong())),422,"INVALID_TRANSITION");
        task=move(leader,task,"REOPEN","Revisit scope"); JsonNode baseline=task;
        error(patch(leader,task,Map.of("version",task.get("version").asLong(),"title","Correction")),422,"VALIDATION_FAILED"); assertEquals(baseline,current(task));
        task=edit(task,Map.of("category","Reopened category")); assertEquals(baseline.get("agreementVersion"),task.get("agreementVersion"));
        task=edit(task,Map.of("title","Correction","reason","  Synthetic correction reason  ")); assertEquals(first,task.get("firstStartedAtUtc").asText()); assertEquals("IN_PROGRESS",task.get("status").asText());
        assertEquals("Synthetic correction reason",jdbc.queryForObject("SELECT reason FROM activity_events WHERE task_id=? AND event_type='TASK_EDITED' AND reason IS NOT NULL",String.class,UUID.fromString(task.get("id").asText())));
        error(action(leader,task,"SUBMIT_REVIEW",task.get("version").asLong(),null),422,"ACCEPTANCE_REQUIRED"); assertEquals(task,edit(task,Map.of()));
    }
    @Test void reviewRevisionBlocksDoneAndCancelledIsImmutable() throws Exception {
        JsonNode task=inState("REVIEW"); task=edit(task,Map.of("description","Review correction","reason","Clarify before approval")); assertEquals("REVIEW",task.get("status").asText());
        error(action(leader,task,"APPROVE",task.get("version").asLong(),null),422,"ACCEPTANCE_REQUIRED"); long agreement=task.get("agreementVersion").asLong(); task=tree(accept(a,task,agreement)); task=tree(accept(b,task,agreement)); assertEquals("DONE",move(leader,task,"APPROVE",null).get("status").asText());
        JsonNode cancelled=inState("CANCELLED"); error(patch(leader,cancelled,Map.of("version",cancelled.get("version").asLong(),"category","Changed")),422,"INVALID_TRANSITION"); assertEquals(cancelled,current(cancelled));
    }
    @Test void staleVersionEvenNoopAndConcurrentEditsCannotOverwrite() throws Exception {
        JsonNode old=create(); JsonNode task=edit(old,Map.of("category","Changed")); error(patch(leader,old,Map.of("version",0)),409,"STALE_VERSION"); assertEquals(task,current(task));
        ApiClient other=leaderSession(); var results=race(() -> patch(leader,task,Map.of("version",1,"title","One")),() -> patch(other,task,Map.of("version",1,"title","Two")));
        assertEquals(List.of(200,409),statuses(results)); assertEquals(2,current(task).get("version").asLong()); assertEquals(2,current(task).get("agreementVersion").asLong()); assertEquals(3,taskEvents(task));
    }
    @Test void editAcceptanceRaceHasCoherentCurrentAgreementOnly() throws Exception {
        JsonNode task=create(); var results=race(() -> patch(leader,task,Map.of("version",0,"title","Revision")),() -> accept(a,task,1));
        assertEquals(List.of(200,409),statuses(results)); JsonNode current=current(task);
        if(current.get("agreementVersion").asLong()==2) { for(var accepted:current.get("assigneeAcceptances")) assertFalse(accepted.get("accepted").asBoolean()); }
        else { assertEquals(1,current.get("version").asLong()); assertEquals("Synthetic task",current.get("title").asText()); }
        assertEquals(2,taskEvents(task));
    }
    @Test void editTransitionRaceSerializesAndPreservesFirstStart() throws Exception {
        JsonNode task=accepted(create()); var results=race(() -> patch(leader,task,Map.of("version",2,"title","Revision")),() -> action(a,task,"START",2,null));
        assertEquals(List.of(200,409),statuses(results)); JsonNode current=current(task); assertEquals(3,current.get("version").asLong());
        if(current.get("status").asText().equals("TODO")) { assertTrue(current.get("firstStartedAtUtc").isNull()); assertEquals(2,current.get("agreementVersion").asLong()); }
        else { assertEquals("IN_PROGRESS",current.get("status").asText()); assertFalse(current.get("firstStartedAtUtc").isNull()); assertEquals("Synthetic task",current.get("title").asText()); }
        assertEquals(4,taskEvents(task));
    }
    @Test void eventFailureRollsBackFieldsAssignmentsAcceptanceVersionsAndHistory() throws Exception {
        JsonNode task=accepted(create()); int count=events();
        try(var c=db.migrator()) { sql(c,"CREATE FUNCTION synthetic_m2_reject_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic fixture failure'; END $$"); sql(c,"CREATE TRIGGER synthetic_m2_failure BEFORE INSERT ON activity_events FOR EACH ROW EXECUTE FUNCTION synthetic_m2_reject_event()"); }
        try { error(patch(leader,task,Map.of("version",2,"title","Failed revision","assigneeIds",List.of(leader.id,b.id))),503,"SERVICE_UNAVAILABLE"); assertEquals(task,current(task)); assertEquals(count,events()); }
        finally { try(var c=db.migrator()) { sql(c,"DROP TRIGGER synthetic_m2_failure ON activity_events"); sql(c,"DROP FUNCTION synthetic_m2_reject_event()"); } }
        assertEquals(2,edit(task,Map.of("title","Recovered")).get("agreementVersion").asLong());
    }
    private ApiClient leaderSession() throws Exception { var other=new ApiClient(app.getWebServer().getPort()); other.bootstrap(); assertEquals(200,other.login("leader@example.invalid",PASSWORD).statusCode()); other.bootstrap(); return other; }
    private static List<Integer> statuses(List<HttpResponse<String>> results) { return results.stream().map(HttpResponse::statusCode).sorted().toList(); }
    private static List<HttpResponse<String>> race(Callable<HttpResponse<String>> a,Callable<HttpResponse<String>> b) throws Exception {
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(() -> { barrier.await(5,TimeUnit.SECONDS); return a.call(); }); var two=pool.submit(() -> { barrier.await(5,TimeUnit.SECONDS); return b.call(); });
            return List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));
        }
    }
}
