package dev.owlibi.reference.tasks;

import static org.junit.jupiter.api.Assertions.*;
import static dev.owlibi.reference.support.ApiClient.*;
import static dev.owlibi.reference.support.PostgresFixture.*;
import dev.owlibi.reference.support.*;
import dev.owlibi.reference.web.Cursor;
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
class TaskBoundaryIT {
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
        assertEquals(200,accept(a,task,1).statusCode()); var response=accept(b,task,1); assertEquals(200,response.statusCode()); return tree(response);
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

    @Test void creationDtoExactTermsOrderingMembershipAndSecretFreeResponses() throws Exception {
        var values=fields(); values.put("title","  🚀".stripLeading()+" ' ; SELECT 1  "); values.put("description","\nSynthetic <script> text\n");
        values.put("category"," Category "); values.put("dueDate","2024-02-29"); values.put("effortPoints","00000000012.3");
        JsonNode task=create(values);
        assertEquals(Set.of("id","projectId","title","description","category","status","dueDate","effortPoints","agreementVersion","assigneeAcceptances",
            "currentUserAcceptedAgreement","assigneeIds","version","firstStartedAtUtc","createdBy","createdAtUtc","updatedAtUtc"),task.propertyNames());
        assertEquals("🚀 ' ; SELECT 1",task.get("title").asText()); assertEquals(values.get("description"),task.get("description").asText());
        assertEquals(" Category ",task.get("category").asText()); assertEquals("12.30",task.get("effortPoints").asText()); assertEquals("2024-02-29",task.get("dueDate").asText());
        assertEquals("TODO",task.get("status").asText()); assertEquals(1,task.get("agreementVersion").asLong()); assertEquals(0,task.get("version").asLong());
        assertTrue(task.get("firstStartedAtUtc").isNull()); assertEquals(leader.id.toString(),task.get("createdBy").asText()); assertFalse(task.get("currentUserAcceptedAgreement").asBoolean());
        for(var item:task.get("assigneeAcceptances")) { assertEquals(Set.of("userId","displayName","accepted","acceptedAtUtc"),item.propertyNames()); assertFalse(item.get("accepted").asBoolean()); assertTrue(item.get("acceptedAtUtc").isNull()); }
        JsonNode second=create(); second=move(leader,second,"CANCEL","No longer needed");
        var snapshot=board(a); assertEquals(Set.of("project","members","tasks","complete","totalTasks","snapshot"),snapshot.propertyNames());
        assertTrue(snapshot.get("complete").asBoolean()); assertEquals(2,snapshot.get("totalTasks").asInt()); assertEquals(3,snapshot.get("members").size());
        assertEquals("MEMBER",snapshot.get("project").get("role").asText());
        String previous=""; for(var item:snapshot.get("tasks")) { assertTrue(item.get("id").asText().compareTo(previous)>0); previous=item.get("id").asText(); }
        assertTrue(snapshot.toString().contains("CANCELLED")); assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM tasks",Integer.class));
        var history=a.send("GET",root()+"/activity",null); assertEquals(200,history.statusCode());
        for(String secret:List.of(PASSWORD,"@example.invalid","password_hash","token_hash","JSESSIONID","jdbc:",leader.sessionCookie(),leader.csrf)) {
            assertFalse(task.toString().contains(secret)); assertFalse(snapshot.toString().contains(secret)); assertFalse(history.body().contains(secret));
        }
        assertEquals(1,taskEvents(task));
        var single=fields(); single.put("assigneeIds",List.of(leader.id)); JsonNode solo=create(single);
        solo=tree(accept(leader,solo,1)); assertTrue(solo.get("currentUserAcceptedAgreement").asBoolean());
        assertEquals("IN_PROGRESS",move(leader,solo,"START",null).get("status").asText());
        var all=new ArrayList<ApiClient>(List.of(leader,a,b));
        for(int i=0;i<3;i++) { var member=account("six-"+i+"@example.invalid"); assertEquals(200,member.accept(leader.invitation(project)).statusCode()); all.add(member); }
        var shared=fields(); shared.put("assigneeIds",all.stream().map(member -> member.id).toList()); JsonNode six=create(shared);
        for(int i=0;i<5;i++) { six=tree(accept(all.get(i),six,1)); error(action(leader,six,"START",six.get("version").asLong(),null),422,"ACCEPTANCE_REQUIRED"); }
        six=tree(accept(all.getLast(),six,1)); assertEquals(6,six.get("version").asLong()); assertEquals(6,six.get("assigneeAcceptances").size());
        assertEquals("IN_PROGRESS",move(leader,six,"START",null).get("status").asText()); assertEquals(8,taskEvents(six));
    }

    @Test void semanticValidationUnicodeCalendarAndExactDecimalBounds() throws Exception {
        for(var entry:Map.<String,List<Object>>of(
            "title",List.of(""," \n\t ","🚀".repeat(201),"a\0b"),
            "description",List.of("x".repeat(4001),"a\0b"),
            "category",List.of("🚀".repeat(101),"a\0b"),
            "dueDate",List.of("2026-02-29","2024-02-30","2026-13-01","2026-00-01","2026-1-01"," 2026-01-01","10000-01-01","2026-01-01T00:00:00Z"),
            "effortPoints",List.of("0","0.00","-1","+1",".01","1.","1.001","0.009","99999999.999","100000000","1e2","NaN"," 1","1 "),
            "assigneeIds",List.of(List.of(),List.of(a.id,a.id),List.of(UUID.randomUUID()),List.of("1-1-1-1-1"),List.of("no"),List.of(a.id,b.id,leader.id,a.id,b.id,leader.id,a.id))).entrySet()) {
            for(Object value:entry.getValue()) {
                var fields=fields(); fields.put(entry.getKey(),value); int before=events();
                var response=leader.send("POST",root()+"/tasks",json(fields)); error(response,422,"VALIDATION_FAILED");
                assertTrue(tree(response).get("fieldErrors").has(entry.getKey())); assertEquals(before,events());
            }
        }
        for(String required:List.of("title","effortPoints","assigneeIds")) for(boolean explicitNull:List.of(false,true)) {
            var fields=fields(); if(explicitNull) fields.put(required,null); else fields.remove(required);
            error(leader.send("POST",root()+"/tasks",json(fields)),422,"VALIDATION_FAILED");
        }
        var max=fields(); max.put("title","🚀".repeat(200)); max.put("description","🚀".repeat(4000)); max.put("category","🚀".repeat(100)); max.put("effortPoints","99999999.99"); max.put("dueDate","9999-12-31");
        assertEquals("99999999.99",create(max).get("effortPoints").asText());
        var min=fields(); min.put("effortPoints","0.01"); min.put("dueDate","0000-01-01"); min.put("description",null); min.put("category",null);
        var result=create(min); assertEquals("0.01",result.get("effortPoints").asText()); assertEquals("0000-01-01",result.get("dueDate").asText());
        assertEquals("",result.get("description").asText()); assertEquals("",result.get("category").asText());
    }

    @Test void typedParserKeepsBodyTypeDuplicateUtf8UnknownAndSecurityOrderingStrict() throws Exception {
        String valid=json(fields());
        for(String body:List.of(valid.replace("\"12.30\"","12.30"),valid.replace(json(List.of(a.id,b.id)),"[1]"),valid.replace(json(List.of(a.id,b.id)),"[null]"),
            valid.replace(json(List.of(a.id,b.id)),"{}"),valid.substring(0,valid.length()-1)+",\"title\":\"duplicate\"}",valid+" {}",
            valid.substring(0,valid.length()-1)+",\"actorId\":\"fake\"}","[]","{\"title\":\"\\ud800\"}","{\"title\":true}"))
            error(leader.send("POST",root()+"/tasks",body),400,"MALFORMED_REQUEST");
        JsonNode task=create(); String acceptance=root()+"/tasks/"+task.get("id").asText()+"/acceptance";
        for(String value:List.of("\"1\"","1.0","1e0","true","[]","9223372036854775808")) error(a.send("POST",acceptance,"{\"agreementVersion\":"+value+"}"),400,"MALFORMED_REQUEST");
        for(String value:List.of("null","-1","0")) error(a.send("POST",acceptance,"{\"agreementVersion\":"+value+"}"),422,"VALIDATION_FAILED");
        error(a.send("POST",acceptance,"{}"),422,"VALIDATION_FAILED");
        String transitions=root()+"/tasks/"+task.get("id").asText()+"/transitions";
        for(String value:List.of("\"0\"","0.0","0e0","true","{}","9223372036854775808")) error(leader.send("POST",transitions,"{\"action\":\"START\",\"version\":"+value+"}"),400,"MALFORMED_REQUEST");
        error(leader.send("POST",transitions,"{\"action\":\"START\",\"version\":null}"),422,"VALIDATION_FAILED");
        error(leader.send("POST",transitions,"{\"action\":\"START\",\"version\":-1}"),422,"VALIDATION_FAILED");
        byte[] malformed={(byte)0xc3,(byte)0x28};
        error(leader.raw("POST",root()+"/tasks",malformed,"application/json",leader.csrf,leader.epoch,Map.of()),400,"MALFORMED_REQUEST");
        error(leader.raw("POST",root()+"/tasks",valid.getBytes(java.nio.charset.StandardCharsets.UTF_8),"text/plain",leader.csrf,leader.epoch,Map.of()),415,"UNSUPPORTED_MEDIA_TYPE");
        error(leader.raw("POST",root()+"/tasks",valid.getBytes(java.nio.charset.StandardCharsets.UTF_8),"application/json",leader.csrf,leader.epoch,Map.of("Content-Encoding","gzip")),400,"MALFORMED_REQUEST");
        error(leader.send("POST",root()+"/tasks"," ".repeat(65537)),400,"REQUEST_TOO_LARGE");
        error(leader.send("GET",root()+"/board","{}"),400,"MALFORMED_REQUEST"); error(leader.send("GET",root()+"/activity","{}"),400,"MALFORMED_REQUEST");
        var anonymous=new ApiClient(app.getWebServer().getPort()); error(anonymous.send("POST",root()+"/tasks","{"),401,"UNAUTHENTICATED");
        error(a.raw("POST",acceptance,"{".getBytes(),"application/json",null,null,Map.of()),403,"CSRF_INVALID");
        error(a.raw("POST",acceptance,"{".getBytes(),"application/json",a.csrf,null,Map.of()),400,"SESSION_CONTEXT_REQUIRED");
        error(a.raw("POST",acceptance,"{".getBytes(),"application/json",a.csrf,UUID.randomUUID().toString(),Map.of()),409,"SESSION_CHANGED");
        clock.advance(1800); error(a.raw("POST",acceptance,"{".getBytes(),"application/json",null,null,Map.of()),401,"UNAUTHENTICATED");
    }

    @Test void permissionsProjectIsolationAndRoleBeforeProtectedStateOrVersion() throws Exception {
        JsonNode task=create(); var outsider=account("outsider@example.invalid"); UUID other=outsider.project("Other project");
        var unassigned=account("unassigned@example.invalid"); assertEquals(200,unassigned.accept(leader.invitation(project)).statusCode());
        for(ApiClient actor:List.of(a,unassigned)) error(actor.send("POST",root()+"/tasks","{"),403,"FORBIDDEN");
        for(String path:List.of(root()+"/board",root()+"/activity?limit=0")) error(outsider.send("GET",path,null),404,"NOT_FOUND");
        error(outsider.send("POST",root()+"/tasks","{"),404,"NOT_FOUND");
        for(String suffix:List.of("acceptance","transitions")) {
            error(outsider.send("POST",root()+"/tasks/"+task.get("id").asText()+"/"+suffix,"{"),404,"NOT_FOUND");
            error(outsider.send("POST","/projects/"+other+"/tasks/"+task.get("id").asText()+"/"+suffix,json(suffix.equals("acceptance")?Map.of("agreementVersion",1):Map.of("action","START","version",0))),404,"NOT_FOUND");
            error(a.send("POST",root()+"/tasks/"+UUID.randomUUID()+"/"+suffix,"{}"),404,"NOT_FOUND");
        }
        error(accept(leader,task,999),403,"FORBIDDEN"); error(accept(unassigned,task,999),403,"FORBIDDEN");
        for(String action:List.of("START","SUBMIT_REVIEW","APPROVE","RETURN","CANCEL","REOPEN")) error(action(unassigned,task,action,999,null),403,"FORBIDDEN");
        for(String action:List.of("APPROVE","RETURN","CANCEL","REOPEN")) error(action(a,task,action,999,null),403,"FORBIDDEN");
        var invalid=fields(); invalid.put("assigneeIds",List.of(outsider.id)); error(leader.send("POST",root()+"/tasks",json(invalid)),422,"VALIDATION_FAILED");
        error(leader.send("PATCH",root()+"/tasks/"+task.get("id").asText(),"{}"),422,"VALIDATION_FAILED");
        assertEquals(1,taskEvents(task)); assertEquals(0,tree(outsider.send("GET","/projects/"+other+"/board",null)).get("tasks").size());
    }

    @Test void firstAcceptanceIdempotenceStaleAgreementAndAllCurrentGate() throws Exception {
        JsonNode task=create(); int before=events();
        error(action(leader,task,"START",0,null),422,"ACCEPTANCE_REQUIRED"); error(accept(a,task,2),409,"STALE_AGREEMENT"); assertEquals(before,events());
        clock.advance(1); var first=accept(a,task,1); assertEquals(200,first.statusCode()); task=tree(first); assertEquals(1,task.get("version").asLong());
        assertTrue(task.get("currentUserAcceptedAgreement").asBoolean()); assertEquals(before+1,events());
        String firstTime=null; for(var item:task.get("assigneeAcceptances")) if(item.get("userId").asText().equals(a.id.toString())) firstTime=item.get("acceptedAtUtc").asText();
        assertEquals(clock.instant().toString(),firstTime);
        clock.advance(1); var repeated=accept(a,task,1); assertEquals(first.body(),repeated.body()); assertEquals(before+1,events());
        error(action(leader,task,"START",1,null),422,"ACCEPTANCE_REQUIRED");
        task=tree(accept(b,task,1)); assertEquals(2,task.get("version").asLong()); assertEquals(before+2,events());
        error(action(a,task,"START",1,null),409,"STALE_VERSION");
        task=move(leader,task,"START",null); assertEquals(3,task.get("version").asLong()); assertEquals(before+3,events());
        var cancelled=create(); cancelled=move(leader,cancelled,"CANCEL","Cancelled before acceptance");
        error(accept(a,cancelled,1),422,"INVALID_TRANSITION"); assertEquals(2,taskEvents(cancelled));
    }

    @Test void everyLeaderStateActionPairAndEveryMemberStateActionPair() throws Exception {
        Map<String,Set<String>> legal=Map.of("TODO",Set.of("START","CANCEL"),"IN_PROGRESS",Set.of("SUBMIT_REVIEW","CANCEL"),
            "REVIEW",Set.of("APPROVE","RETURN","CANCEL"),"DONE",Set.of("REOPEN"),"CANCELLED",Set.of());
        Map<String,String> targets=Map.of("START","IN_PROGRESS","SUBMIT_REVIEW","REVIEW","APPROVE","DONE","RETURN","IN_PROGRESS","CANCEL","CANCELLED","REOPEN","IN_PROGRESS");
        for(String state:List.of("TODO","IN_PROGRESS","REVIEW","DONE","CANCELLED")) for(String action:targets.keySet()) for(ApiClient actor:List.of(leader,a)) {
            JsonNode task=current(inState(state)); int before=taskEvents(task); long version=task.get("version").asLong();
            var response=action(actor,task,action,version,"Synthetic reason");
            if(actor==a && !Set.of("START","SUBMIT_REVIEW").contains(action)) error(response,403,"FORBIDDEN");
            else if(!legal.get(state).contains(action)) error(response,422,"INVALID_TRANSITION");
            else { assertEquals(200,response.statusCode(),response.body()); assertEquals(targets.get(action),tree(response).get("status").asText()); assertEquals(version+1,tree(response).get("version").asLong()); }
            assertEquals(before+(response.statusCode()==200?1:0),taskEvents(task));
            var current=current(task); assertEquals(task.get("title"),current.get("title")); assertEquals(task.get("assigneeIds"),current.get("assigneeIds")); assertEquals(task.get("effortPoints"),current.get("effortPoints")); assertEquals(1,current.get("agreementVersion").asLong());
            if(response.statusCode()!=200) assertEquals(task,current);
        }
        JsonNode task=create(); error(action(leader,task,"UNKNOWN",0,null),422,"VALIDATION_FAILED");
    }

    @Test void reasonsBoundsAndFirstStartPreservedThroughReturnReopenAndCancellation() throws Exception {
        JsonNode task=accepted(create()); clock.advanceNanos(123456789);
        task=move(a,task,"START",null); String first=task.get("firstStartedAtUtc").asText(); assertEquals("2026-10-10T10:00:00.123456Z",first);
        task=move(b,task,"SUBMIT_REVIEW",null);
        for(String reason:new String[]{null,""," \n\t ","🚀".repeat(1001),"a\0b"}) error(action(leader,task,"RETURN",task.get("version").asLong(),reason),422,"VALIDATION_FAILED");
        clock.advance(1); task=move(leader,task,"RETURN","  Synthetic return  "); assertEquals(first,task.get("firstStartedAtUtc").asText());
        task=move(a,task,"SUBMIT_REVIEW",null); task=move(leader,task,"APPROVE",null);
        for(String reason:new String[]{null,""," \n "}) error(action(leader,task,"REOPEN",task.get("version").asLong(),reason),422,"VALIDATION_FAILED");
        clock.advance(1); task=move(leader,task,"REOPEN","🚀".repeat(1000)); assertEquals(first,task.get("firstStartedAtUtc").asText());
        for(String reason:new String[]{null,"","\t"}) error(action(leader,task,"CANCEL",task.get("version").asLong(),reason),422,"VALIDATION_FAILED");
        task=move(leader,task,"CANCEL","Synthetic cancel"); assertEquals(first,task.get("firstStartedAtUtc").asText());
        assertEquals("Synthetic return",jdbc.queryForObject("SELECT reason FROM activity_events WHERE task_id=? AND event_type='RETURN'",String.class,UUID.fromString(task.get("id").asText())));
        assertEquals(first,current(task).get("firstStartedAtUtc").asText());
    }

    @Test void staleAcceptanceBlocksSubmitApprovalAndReopenWithoutChangingState() throws Exception {
        for(String action:List.of("SUBMIT_REVIEW","APPROVE","REOPEN")) {
            JsonNode task=inState(Map.of("SUBMIT_REVIEW","IN_PROGRESS","APPROVE","REVIEW","REOPEN","DONE").get(action));
            UUID id=UUID.fromString(task.get("id").asText());
            // Test-owned fixture simulates a stale agreement; M1 deliberately has no editing route.
            jdbc.update("UPDATE task_assignees SET accepted_agreement_version=NULL,accepted_at_utc=NULL WHERE task_id=? AND user_id=?",id,b.id);
            int before=taskEvents(task); error(action(leader,task,action,task.get("version").asLong(),"Synthetic reason"),422,"ACCEPTANCE_REQUIRED");
            assertEquals(before,taskEvents(task)); assertEquals(task.get("status"),current(task).get("status"));
        }
    }

    @Test void concurrentAcceptancesAreSerializedAndRepeatedCurrentAcceptanceHasOneEvent() throws Exception {
        JsonNode task=create();
        var sameUser=new ApiClient(app.getWebServer().getPort()); sameUser.bootstrap(); assertEquals(200,sameUser.login("a@example.invalid",PASSWORD).statusCode()); sameUser.bootstrap();
        var results=race(() -> accept(a,task,1),() -> accept(sameUser,task,1)); assertEquals(List.of(200,200),statuses(results));
        assertEquals(1,current(task).get("version").asLong()); assertEquals(2,taskEvents(task)); assertEquals(results.get(0).body(),results.get(1).body());
        JsonNode shared=create(); results=race(() -> accept(a,shared,1),() -> accept(b,shared,1)); assertEquals(List.of(200,200),statuses(results));
        assertEquals(Set.of(1L,2L),Set.of(tree(results.get(0)).get("version").asLong(),tree(results.get(1)).get("version").asLong()));
        assertEquals(2,current(shared).get("version").asLong()); assertEquals(3,taskEvents(shared));
    }

    @Test void acceptanceTransitionAndTransitionTransitionRacesNeverLoseWrites() throws Exception {
        for(int i=0;i<6;i++) {
            JsonNode task=create(); assertEquals(200,accept(a,task,1).statusCode());
            var results=race(() -> accept(b,task,1),() -> action(leader,task,"START",1,null));
            assertEquals(200,results.get(0).statusCode());
            if(results.get(1).statusCode()==409) error(results.get(1),409,"STALE_VERSION"); else error(results.get(1),422,"ACCEPTANCE_REQUIRED");
            JsonNode accepted=current(task); assertEquals("TODO",accepted.get("status").asText()); assertEquals(2,accepted.get("version").asLong()); assertEquals(3,taskEvents(task));
            results=race(() -> action(a,accepted,"START",2,null),() -> action(b,accepted,"START",2,null));
            assertEquals(List.of(200,409),statuses(results)); results.stream().filter(r -> r.statusCode()==409).forEach(r -> error(r,409,"STALE_VERSION"));
            var started=current(task); assertEquals(3,started.get("version").asLong()); assertEquals("IN_PROGRESS",started.get("status").asText()); assertFalse(started.get("firstStartedAtUtc").isNull()); assertEquals(4,taskEvents(task));
        }
    }

    @Test void concurrentTaskCapacityOneWinnerOversizedBoardFailsAndDatabaseConstraintsHold() throws Exception {
        seedTasks(999); int before=events();
        var otherLeader=new ApiClient(app.getWebServer().getPort()); otherLeader.bootstrap(); assertEquals(200,otherLeader.login("leader@example.invalid",PASSWORD).statusCode()); otherLeader.bootstrap();
        var results=race(() -> leader.send("POST",root()+"/tasks",json(fields())),() -> otherLeader.send("POST",root()+"/tasks",json(fields())));
        assertEquals(List.of(201,422),statuses(results)); results.stream().filter(r -> r.statusCode()==422).forEach(r -> error(r,422,"TASK_LIMIT"));
        assertEquals(1000,board(a).get("tasks").size()); assertEquals(1000,board(a).get("totalTasks").asInt()); assertEquals(before+1,events());
        seedTasks(1); error(a.send("GET",root()+"/board",null),422,"TASK_LIMIT"); error(leader.send("POST",root()+"/tasks",json(fields())),422,"TASK_LIMIT"); assertEquals(before+1,events());
        try(var c=db.runtime()) {
            for(String statement:List.of("UPDATE tasks SET effort_points=0","UPDATE tasks SET status='OTHER'","UPDATE tasks SET version=-1","UPDATE tasks SET agreement_version=0",
                "UPDATE task_assignees SET accepted_agreement_version=1,accepted_at_utc=NULL","UPDATE task_assignees SET user_id=gen_random_uuid()")) assertThrows(java.sql.SQLException.class,() -> sql(c,statement));
            assertThrows(java.sql.SQLException.class,() -> sql(c,"UPDATE activity_events SET reason='forbidden'"));
        }
    }
    private void seedTasks(int count) {
        jdbc.update("INSERT INTO tasks (id,project_id,title,status,effort_points,agreement_version,version,created_by,created_at_utc,updated_at_utc) SELECT gen_random_uuid(),?,'Synthetic capacity','TODO',1,1,0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM generate_series(1,?)",project,leader.id,count);
        jdbc.update("INSERT INTO task_assignees (task_id,project_id,user_id,assigned_at_utc) SELECT t.id,t.project_id,?,t.created_at_utc FROM tasks t WHERE t.project_id=? AND NOT EXISTS (SELECT 1 FROM task_assignees a WHERE a.task_id=t.id)",a.id,project);
    }

    @Test void repeatableReadBoardDoesNotMixConcurrentMembershipTaskOrAcceptance() throws Exception {
        JsonNode task=create(); var outsider=account("snapshot@example.invalid"); String invitation=leader.invitation(project);
        var probe=app.getBean(BoardSnapshotProbe.class); probe.armed.set(true);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var future=pool.submit(() -> a.send("GET",root()+"/board",null));
            try {
                assertTrue(probe.read.await(5,TimeUnit.SECONDS)); assertEquals(Connection.TRANSACTION_REPEATABLE_READ,probe.isolation); assertTrue(probe.readOnly);
                assertEquals(200,outsider.accept(invitation).statusCode()); create(); assertEquals(200,accept(b,task,1).statusCode());
            } finally { probe.release.countDown(); }
            var response=future.get(10,TimeUnit.SECONDS); assertEquals(200,response.statusCode()); var old=tree(response);
            assertEquals(3,old.get("members").size()); assertEquals(1,old.get("tasks").size()); assertEquals(1,old.get("totalTasks").asInt());
            assertEquals(0,old.get("tasks").get(0).get("version").asLong());
            for(var acceptance:old.get("tasks").get(0).get("assigneeAcceptances")) assertFalse(acceptance.get("accepted").asBoolean());
        }
        var fresh=board(a); assertEquals(4,fresh.get("members").size()); assertEquals(2,fresh.get("tasks").size()); assertEquals(1,current(task).get("version").asLong());
    }

    @Test void activityDefaultBoundsTuplePaginationAttributionAndCurrentMembership() throws Exception {
        for(int i=0;i<55;i++) create(); // Same clock: ordering must use the UUID tie-breaker.
        var first=a.send("GET",root()+"/activity",null); assertEquals(200,first.statusCode()); assertEquals(50,tree(first).get("items").size()); assertFalse(tree(first).get("nextCursor").isNull());
        var all=tree(a.send("GET",root()+"/activity?limit=100",null)); assertEquals(events(),all.get("items").size()); assertTrue(all.get("nextCursor").isNull());
        Set<String> seen=new HashSet<>(); String cursor=null,previous=null;
        do {
            var response=a.send("GET",root()+"/activity?limit=7"+(cursor==null?"":"&cursor="+cursor),null); assertEquals(200,response.statusCode()); var page=tree(response);
            assertEquals(Set.of("items","nextCursor"),page.propertyNames());
            for(var item:page.get("items")) {
                assertEquals(Set.of("id","projectId","taskId","actorId","actorName","eventType","occurredAtUtc","reason","changeSummary"),item.propertyNames());
                String id=item.get("id").asText(); assertTrue(seen.add(id)); if(previous!=null) assertTrue(previous.compareTo(id)>0); previous=id;
                assertEquals(project.toString(),item.get("projectId").asText()); assertEquals("Synthetic account",item.get("actorName").asText()); assertEquals(clock.instant().toString(),item.get("occurredAtUtc").asText());
                assertTrue(item.get("changeSummary").isObject()); assertTrue(item.get("reason").isNull());
                if(item.get("eventType").asText().equals("TASK_CREATED")) { assertEquals(leader.id.toString(),item.get("actorId").asText()); assertFalse(item.get("taskId").isNull()); }
            }
            cursor=page.get("nextCursor").isNull()?null:page.get("nextCursor").asText();
        } while(cursor!=null);
        assertEquals(events(),seen.size());
        for(String query:List.of("limit=0","limit=101","limit=-1","limit=1.0","limit=","cursor=","cursor=bad","cursor="+"a".repeat(513),
            "cursor="+new Cursor(Instant.parse("+999999999-01-01T00:00:00Z"),UUID.randomUUID()).encode(),"cursor="+new Cursor(Instant.parse("+5000000-01-01T00:00:00Z"),UUID.randomUUID()).encode())) error(a.send("GET",root()+"/activity?"+query,null),400,"MALFORMED_REQUEST");
        var outsider=account("history-outside@example.invalid"); error(outsider.send("GET",root()+"/activity?cursor="+tree(first).get("nextCursor").asText(),null),404,"NOT_FOUND");
        // Test-only membership without task/event references permits a withdrawal fixture; no v1 route is exposed.
        jdbc.update("INSERT INTO project_memberships VALUES (?,?,'MEMBER',CURRENT_TIMESTAMP)",project,outsider.id);
        assertEquals(200,outsider.send("GET",root()+"/activity?limit=1",null).statusCode());
        jdbc.update("DELETE FROM project_memberships WHERE project_id=? AND user_id=?",project,outsider.id);
        error(outsider.send("GET",root()+"/activity?cursor="+tree(first).get("nextCursor").asText(),null),404,"NOT_FOUND");
    }

    @Test void eventFailureRollsBackCreateAcceptanceAndStartIncludingFirstTimestamp() throws Exception {
        JsonNode unaccepted=create(); JsonNode accepted=accepted(create()); int before=events();
        try(var c=db.migrator()) {
            sql(c,"CREATE FUNCTION synthetic_a3_reject_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic fixture failure'; END $$");
            sql(c,"CREATE TRIGGER synthetic_a3_failure BEFORE INSERT ON activity_events FOR EACH ROW EXECUTE FUNCTION synthetic_a3_reject_event()");
        }
        try {
            error(leader.send("POST",root()+"/tasks",json(fields())),503,"SERVICE_UNAVAILABLE"); assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM tasks WHERE project_id=?",Integer.class,project));
            error(accept(a,unaccepted,1),503,"SERVICE_UNAVAILABLE"); assertEquals(0,current(unaccepted).get("version").asLong());
            for(var item:current(unaccepted).get("assigneeAcceptances")) assertFalse(item.get("accepted").asBoolean());
            error(action(a,accepted,"START",2,null),503,"SERVICE_UNAVAILABLE"); var old=current(accepted); assertEquals("TODO",old.get("status").asText()); assertEquals(2,old.get("version").asLong()); assertTrue(old.get("firstStartedAtUtc").isNull()); assertEquals(before,events());
        } finally { try(var c=db.migrator()) { sql(c,"DROP TRIGGER synthetic_a3_failure ON activity_events"); sql(c,"DROP FUNCTION synthetic_a3_reject_event()"); } }
        assertEquals(200,accept(a,unaccepted,1).statusCode()); assertFalse(move(a,accepted,"START",null).get("firstStartedAtUtc").isNull()); assertEquals(before+2,events());
    }

    @Test void taskAcceptanceTransitionsAndEventsPersistAcrossDatabaseAndApiRestarts() throws Exception {
        JsonNode task=inState("DONE"); String first=task.get("firstStartedAtUtc").asText(); int before=events(); String revoked=leader.sessionCookie();
        app.close(); app=null; db.restart(); app=db.startApp(BoardSnapshotProbe.SOURCE); jdbc=app.getBean(JdbcTemplate.class);
        var replay=new ApiClient(app.getWebServer().getPort()); error(replay.raw("GET",root()+"/board",new byte[0],null,null,null,Map.of("Cookie","JSESSIONID="+revoked)),401,"UNAUTHENTICATED");
        leader=new ApiClient(app.getWebServer().getPort()); leader.bootstrap(); assertEquals(200,leader.login("leader@example.invalid",PASSWORD).statusCode()); leader.bootstrap();
        assertEquals(task,current(task)); assertEquals(before,events()); task=move(leader,task,"REOPEN","Synthetic restart reopen"); assertEquals(first,task.get("firstStartedAtUtc").asText()); assertEquals(before+1,events());
        try(var c=db.runtime()) { assertEquals("4",query(c,"SELECT max(version::integer) FROM flyway_schema_history WHERE success")); }
    }

    private static List<Integer> statuses(List<HttpResponse<String>> results) { return results.stream().map(HttpResponse::statusCode).sorted().toList(); }
    private static List<HttpResponse<String>> race(Callable<HttpResponse<String>> a,Callable<HttpResponse<String>> b) throws Exception {
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(() -> { barrier.await(5,TimeUnit.SECONDS); return a.call(); }); var two=pool.submit(() -> { barrier.await(5,TimeUnit.SECONDS); return b.call(); });
            return List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));
        }
    }
}
