package dev.owlibi.reference.projects;

import static org.junit.jupiter.api.Assertions.*;
import static dev.owlibi.reference.support.ApiClient.*;
import static dev.owlibi.reference.support.PostgresFixture.*;
import dev.owlibi.reference.support.*;
import dev.owlibi.reference.templates.TemplateCatalog;
import java.net.http.HttpResponse;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** R1 real HTTP/PostgreSQL regressions; every fixture is test-owned and fails if absent. */
class ProjectSetupIT {
    private PostgresFixture db;
    private ServletWebServerApplicationContext app;
    private ApiClient leader;
    private JdbcTemplate jdbc;
    private final MutableClock clock=MutableClock.INSTANCE;
    @BeforeEach void start() throws Exception {
        clock.reset(); db=new PostgresFixture(); app=db.startApp(MutableClock.SOURCE); jdbc=app.getBean(JdbcTemplate.class); leader=account("r1-leader@example.invalid");
    }
    @AfterEach void stop() { if(app!=null) app.close(); if(db!=null) db.close(); }
    private ApiClient account(String email) throws Exception { var c=new ApiClient(app.getWebServer().getPort()); c.account(email); return c; }
    private ApiClient otherLeader() throws Exception {
        var c=new ApiClient(app.getWebServer().getPort()); c.bootstrap(); assertEquals(200,c.login("r1-leader@example.invalid",PASSWORD).statusCode()); c.bootstrap(); return c;
    }
    private Map<String,Object> rules() {
        return new LinkedHashMap<>(Map.of("effortScaleName","Relative effort","effortScaleExamples",List.of(
            Map.of("points","1.00","label","Small scoped change"),Map.of("points","2.00","label","Several related steps"),Map.of("points","3.00","label","Moderate task"),
            Map.of("points","5.00","label","Substantial task"),Map.of("points","8.00","label","Large task to consider splitting")),"overloadThreshold","20.00","nearDueDays",3));
    }
    private Map<String,Object> createFields(String type) { return new LinkedHashMap<>(Map.of("name","Synthetic "+type,"template",Map.of("id",type,"version",type.equals("blank")?2:1),"settings",rules())); }
    private JsonNode create(String type) throws Exception { var r=leader.send("POST","/projects",json(createFields(type))); assertEquals(201,r.statusCode(),r.body()); return tree(r); }
    private UUID id(JsonNode node) { return UUID.fromString(node.get("id").asText()); }
    private String path(UUID project) { return "/projects/"+project+"/settings"; }
    private JsonNode settings(UUID project) throws Exception { var r=leader.send("GET",path(project),null); assertEquals(200,r.statusCode(),r.body()); return tree(r); }
    private JsonNode board(UUID project) throws Exception { var r=leader.send("GET","/projects/"+project+"/board",null); assertEquals(200,r.statusCode(),r.body()); return tree(r); }
    private HttpResponse<String> patch(ApiClient c,UUID project,String version,Map<String,Object> fields) throws Exception {
        var body=new LinkedHashMap<>(fields);body.put("settingsVersion",version);return c.send("PATCH",path(project),json(body));
    }
    private JsonNode change(UUID project,String version,Map<String,Object> fields) throws Exception { var r=patch(leader,project,version,fields);assertEquals(200,r.statusCode(),r.body());return tree(r); }
    private static void assertBoardContent(JsonNode before,JsonNode after) {
        assertEquals(before.propertyNames(),after.propertyNames());
        for(String field:List.of("project","members","tasks","complete","totalTasks")) assertEquals(before.get(field),after.get(field),field);
    }
    private int events(UUID project) { return jdbc.queryForObject("SELECT count(*) FROM activity_events WHERE project_id=?",Integer.class,project); }
    private String snapshot(UUID project) { return jdbc.queryForObject("SELECT template_snapshot::text FROM projects WHERE id=?",String.class,project); }
    private void nextAttempt() throws Exception { clock.advance(60); assertEquals(200,leader.send("POST","/auth/sessions/current/activity","{}").statusCode()); }
    private JsonNode taskCreate(UUID project) throws Exception {
        var r=leader.send("POST","/projects/"+project+"/tasks",json(Map.of("title","Synthetic ordinary task","effortPoints","1.00","assigneeIds",List.of(leader.id)))); assertEquals(201,r.statusCode());return tree(r);
    }
    private static List<HttpResponse<String>> race(Callable<HttpResponse<String>> one,Callable<HttpResponse<String>> two) throws Exception {
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> {barrier.await(5,TimeUnit.SECONDS);return one.call();});var b=pool.submit(() -> {barrier.await(5,TimeUnit.SECONDS);return two.call();});
            return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
    }

    @Test void r1_01CatalogueAuthenticationExactApprovedTypesAndStrictVersionQueries() throws Exception {
        var anon=new ApiClient(app.getWebServer().getPort()); error(anon.send("GET","/templates",null),401,"UNAUTHENTICATED");
        var response=leader.send("GET","/templates",null); assertEquals(200,response.statusCode()); assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));
        var root=tree(response); assertEquals(Set.of("items"),root.propertyNames()); assertEquals(5,root.get("items").size());
        List<String> types=List.of("blank","coding","written","reporting-presentation","filming-video");
        List<List<String>> titles=List.of(List.of(),List.of("Clarify requirements","Design the solution","Implement the core flow","Test and fix the core flow","Prepare the demo and notes"),
            List.of("Clarify the topic and outline","Gather and cite sources","Draft the sections","Revise and check citations","Format the final document"),
            List.of("Clarify the message and outline","Gather supporting material","Prepare slides and speaker notes","Rehearse and revise","Prepare the final presentation"),
            List.of("Clarify the brief and storyboard","Prepare assets and recording plan","Record the footage","Edit and review the video","Prepare the final video"));
        List<List<String>> points=List.of(List.of(),List.of("2.00","3.00","8.00","5.00","2.00"),List.of("2.00","3.00","5.00","3.00","2.00"),List.of("2.00","3.00","5.00","3.00","2.00"),List.of("3.00","2.00","5.00","8.00","2.00"));
        for(int i=0;i<5;i++) {
            JsonNode t=root.get("items").get(i); assertEquals(types.get(i),t.get("id").asText()); assertEquals(i==0?2:1,t.get("version").asInt());
            assertEquals(Set.of("id","name","version","categories","starterTasks","suggestedSettings","initialization"),t.propertyNames());
            assertEquals(JSON.valueToTree(rules()),t.get("suggestedSettings")); assertEquals("CREATOR_ONLY",t.get("initialization").get("assigneePolicy").asText()); assertEquals("PENDING",t.get("initialization").get("acceptancePolicy").asText());
            assertEquals(titles.get(i).size(),t.get("starterTasks").size());
            for(int j=0;j<titles.get(i).size();j++) { var row=t.get("starterTasks").get(j);assertEquals(titles.get(i).get(j),row.get("title").asText());assertEquals(points.get(i).get(j),row.get("effortPoints").asText());assertTrue(row.get("dueDate").isNull()); }
            assertEquals(t,tree(leader.send("GET","/templates/"+types.get(i),null))); assertEquals(t,tree(leader.send("GET","/templates/"+types.get(i)+"?version="+(i==0?2:1),null)));
        }
        for(String query:List.of("version=0","version=-1","version=1.0","version=01","version=2147483648","version=","version=1&version=1","unknown=1")) error(leader.send("GET","/templates/coding?"+query,null),400,"MALFORMED_REQUEST");
        error(leader.send("GET","/templates?version=1",null),400,"MALFORMED_REQUEST"); error(leader.send("GET","/templates/coding","{}"),400,"MALFORMED_REQUEST");
        error(leader.send("GET","/templates/missing",null),404,"NOT_FOUND"); error(leader.send("GET","/templates/coding?version=2",null),404,"NOT_FOUND"); error(leader.send("GET","/templates/blank?version=1",null),404,"NOT_FOUND");
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM projects",Integer.class));
    }
    @Test void r1_02AllFiveProjectTypesCopyExactSnapshotsAndPendingStartersAtomically() throws Exception {
        var catalogue=tree(leader.send("GET","/templates",null)).get("items");Set<String> taskIds=new HashSet<>();
        for(var t:catalogue) {
            var created=create(t.get("id").asText());UUID project=id(created);var b=board(project);int expected=t.get("id").asText().equals("blank")?0:5;
            assertEquals(Set.of("id","name","description","role","createdAtUtc","templateRef","settings","starterTaskIds"),created.propertyNames());
            assertEquals("LEADER",created.get("role").asText());assertEquals(expected,created.get("starterTaskIds").size());assertEquals(expected,b.get("totalTasks").asInt());assertEquals(1,b.get("members").size());assertEquals(expected+1,events(project));
            JsonNode saved=JSON.readTree(snapshot(project));assertEquals(t,saved.get("template"));assertEquals(1,saved.get("schemaVersion").asInt());assertEquals(JSON.valueToTree(rules()),saved.get("effectiveSettings"));
            JsonNode rules=settings(project);assertTrue(rules.get("configured").asBoolean());assertEquals("1",rules.get("settingsVersion").asText());assertEquals(created.get("settings"),rules);assertEquals(created.get("createdAtUtc"),rules.get("updatedAtUtc"));
            for(int i=0;i<expected;i++) {
                String tid=created.get("starterTaskIds").get(i).asText();assertTrue(taskIds.add(tid));JsonNode actual=null;for(var row:b.get("tasks")) if(row.get("id").asText().equals(tid)) actual=row;assertNotNull(actual);
                JsonNode starter=t.get("starterTasks").get(i);for(String field:List.of("title","description","category","dueDate","effortPoints")) assertEquals(starter.get(field),actual.get(field),field);
                assertEquals("TODO",actual.get("status").asText());assertEquals(0,actual.get("version").asLong());assertEquals(1,actual.get("agreementVersion").asLong());assertTrue(actual.get("firstStartedAtUtc").isNull());assertFalse(actual.get("currentUserAcceptedAgreement").asBoolean());
                assertEquals(JSON.valueToTree(List.of(leader.id.toString())),actual.get("assigneeIds"));assertFalse(actual.get("assigneeAcceptances").get(0).get("accepted").asBoolean());assertTrue(actual.get("assigneeAcceptances").get(0).get("acceptedAtUtc").isNull());
                assertEquals(created.get("createdAtUtc"),actual.get("createdAtUtc"));assertEquals(created.get("createdAtUtc"),actual.get("updatedAtUtc"));
                assertEquals(clock.instant(),jdbc.queryForObject("SELECT assigned_at_utc FROM task_assignees WHERE task_id=?",Timestamp.class,UUID.fromString(tid)).toInstant());
            }
            assertEquals(1,jdbc.queryForObject("SELECT count(DISTINCT occurred_at_utc) FROM activity_events WHERE project_id=?",Integer.class,project));
        }
        assertEquals(20,taskIds.size());
    }
    @Test void r1_03CreationRejectsForgedNestedFieldsNullsWrongTypesAndUnavailableVersions() throws Exception {
        for(String body:List.of("{\"name\":\"x\",\"leaderId\":null}","{\"name\":\"x\",\"template\":null}","{\"name\":\"x\",\"settings\":null}","{\"name\":\"x\",\"template\":{}}",
            "{\"name\":\"x\",\"template\":{\"id\":\"coding\",\"version\":0}}","{\"name\":\"x\",\"template\":{\"id\":\"coding\",\"version\":1}}",
            "{\"name\":\"x\",\"template\":{\"id\":\"coding\",\"version\":\"1\"}}","{\"name\":\"x\",\"template\":[]}","{\"name\":\"x\",\"template\":{\"id\":\"blank\",\"version\":2,\"actor\":null}}",
            "{\"name\":\"x\",\"settings\":{\"settingsVersion\":\"0\"}}","{\"name\":\"x\",\"settings\":{}}","{\"name\":\"x\",\"name\":\"y\"}","{\"name\":\"x\"}{}")) {
            nextAttempt();int status=(body.contains("leaderId") || body.contains("actor") || body.contains("settingsVersion") || body.contains("[]") || body.contains("\\\"1\\\"") || body.contains("\"version\":\"1\"") || body.contains("\"name\":\"y\"") || body.endsWith("}{}"))?400:422;
            error(leader.send("POST","/projects",body),status,status==400?"MALFORMED_REQUEST":"VALIDATION_FAILED");
        }
        for(String type:List.of("missing","coding")) {nextAttempt();var body=createFields(type);body.put("template",Map.of("id",type,"version",99));error(leader.send("POST","/projects",json(body)),409,"TEMPLATE_UNAVAILABLE");}
        nextAttempt();var body=createFields("coding");var values=rules();values.put("effortScaleExamples",List.of(Map.of("points","1.00","label","x","accepted",true)));body.put("settings",values);error(leader.send("POST","/projects",json(body)),400,"MALFORMED_REQUEST");
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM projects",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM tasks",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM activity_events",Integer.class));
    }
    @Test void r1_04CreatorMustAcceptAndM2CustomizationDoesNotRewriteSourceOrSnapshot() throws Exception {
        var created=create("coding");UUID project=id(created);String snap=snapshot(project);String catalogue=leader.send("GET","/templates/coding",null).body();JsonNode task=board(project).get("tasks").get(0);String root="/projects/"+project+"/tasks/"+task.get("id").asText();
        error(leader.send("POST",root+"/transitions",json(Map.of("action","START","version",0))),422,"ACCEPTANCE_REQUIRED");
        task=tree(leader.send("POST",root+"/acceptance","{\"agreementVersion\":1}"));assertTrue(task.get("currentUserAcceptedAgreement").asBoolean());
        task=tree(leader.send("POST",root+"/transitions",json(Map.of("action","START","version",task.get("version").asLong()))));assertEquals("IN_PROGRESS",task.get("status").asText());
        var edit=new HashMap<String,Object>(Map.of("version",task.get("version").asLong(),"title","Synthetic customized title"));error(leader.send("PATCH",root,json(edit)),422,"VALIDATION_FAILED");edit.put("reason","Synthetic scope correction");var changed=leader.send("PATCH",root,json(edit));assertEquals(200,changed.statusCode());task=tree(changed);
        assertEquals(2,task.get("agreementVersion").asLong());assertFalse(task.get("currentUserAcceptedAgreement").asBoolean());assertEquals("IN_PROGRESS",task.get("status").asText());
        assertEquals(snap,snapshot(project));assertEquals(catalogue,leader.send("GET","/templates/coding",null).body());
    }
    @Test void r1_05LegacyBlankStaysEmptyAndExplicitSetupDoesNotReviseTasksOrSnapshot() throws Exception {
        UUID project=leader.project("Synthetic legacy client");JsonNode initial=settings(project);assertFalse(initial.get("configured").asBoolean());assertEquals("0",initial.get("settingsVersion").asText());
        for(String field:List.of("effortScaleName","effortScaleExamples","overloadThreshold","nearDueDays","updatedAtUtc")) assertTrue(initial.get(field).isNull());
        assertEquals("Asia/Manila",initial.get("projectZone").asText());assertEquals(0,board(project).get("totalTasks").asInt());assertTrue(JSON.readTree(snapshot(project)).get("effectiveSettings").isNull());
        taskCreate(project);JsonNode tasks=board(project);String saved=snapshot(project);int events=events(project);error(patch(leader,project,"0",Map.of("nearDueDays",3)),422,"VALIDATION_FAILED");
        JsonNode after=change(project,"0",rules());assertEquals("1",after.get("settingsVersion").asText());assertEquals(events+1,events(project));var current=board(project);
        assertBoardContent(tasks,current);assertNotEquals(tasks.get("snapshot").get("stateToken"),current.get("snapshot").get("stateToken"));assertEquals(saved,snapshot(project));
        var event=jdbc.queryForMap("SELECT actor_id,occurred_at_utc,change_summary::text AS summary FROM activity_events WHERE project_id=? AND event_type='SETTINGS_CHANGED'",project);
        assertEquals(leader.id,event.get("actor_id"));assertEquals(clock.instant(),((Timestamp)event.get("occurred_at_utc")).toInstant());JsonNode summary=JSON.readTree((String)event.get("summary"));assertFalse(summary.get("before").get("configured").asBoolean());assertEquals("0",summary.get("before").get("settingsVersion").asText());assertTrue(summary.get("before").get("overloadThreshold").isNull());assertEquals(4,summary.get("changedFields").size());
    }
    @Test void r1_06V3UpgradePreservesLegacySnapshotTasksAgreementsHistoryAndRuntimePrivileges() throws Exception {
        try(var old=new PostgresFixture()) {
            org.flywaydb.core.Flyway.configure().configuration(old.flyway().getConfiguration()).target("3").load().migrate();UUID project=old.seed();UUID actor,task=UUID.randomUUID();String original;
            try(var c=old.runtime()) {
                actor=UUID.fromString(query(c,"SELECT leader_id FROM projects WHERE id='"+project+"'"));
                try(var s=c.prepareStatement("INSERT INTO tasks(id,project_id,title,status,effort_points,agreement_version,version,first_started_at_utc,created_by,created_at_utc,updated_at_utc) VALUES (?,?,'Synthetic preserved task','IN_PROGRESS',7.50,3,8,?,?,?,?)")) {s.setObject(1,task);s.setObject(2,project);s.setTimestamp(3,Timestamp.from(clock.instant()));s.setObject(4,actor);s.setTimestamp(5,Timestamp.from(clock.instant()));s.setTimestamp(6,Timestamp.from(clock.instant()));s.executeUpdate();}
                try(var s=c.prepareStatement("INSERT INTO task_assignees VALUES (?,?,?,?,?,?)")) {s.setObject(1,task);s.setObject(2,project);s.setObject(3,actor);s.setTimestamp(4,Timestamp.from(clock.instant()));s.setLong(5,3);s.setTimestamp(6,Timestamp.from(clock.instant()));s.executeUpdate();}
                original=legacyState(c);
            }
            assertEquals(1,old.flyway().migrate().migrationsExecuted);assertEquals(0,old.flyway().migrate().migrationsExecuted);old.flyway().validate();
            try(var c=old.runtime()) {
                assertEquals(original,legacyState(c));assertEquals("0",query(c,"SELECT settings_version FROM projects"));assertEquals("0",query(c,"SELECT count(*) FROM projects WHERE effort_scale_name IS NOT NULL OR effort_scale_examples IS NOT NULL OR overload_threshold IS NOT NULL OR near_due_days IS NOT NULL OR settings_updated_at_utc IS NOT NULL"));
                assertThrows(SQLException.class,() -> sql(c,"ALTER TABLE projects ADD COLUMN forbidden INTEGER"));assertThrows(SQLException.class,() -> sql(c,"UPDATE activity_events SET reason='forbidden'"));
                assertEquals("23514",assertThrows(SQLException.class,() -> sql(c,"UPDATE projects SET template_snapshot='{}'")).getSQLState());
                sql(c,"UPDATE projects SET settings_version=1,effort_scale_name='Synthetic scale',effort_scale_examples='[{\"points\":\"1.00\",\"label\":\"Small\"}]',overload_threshold=20.00,near_due_days=3,settings_updated_at_utc=CURRENT_TIMESTAMP");
                assertEquals("1",query(c,"SELECT settings_version FROM projects"));assertEquals(original,legacyState(c));
            }
            try(var upgraded=old.startApp(MutableClock.SOURCE)) {var result=upgraded.getBean(ProjectSettingsService.class).read(actor,project);assertTrue(result.configured());assertEquals("1",result.settingsVersion());}
        }
    }
    private static String legacyState(Connection c) throws Exception {
        return query(c,"SELECT jsonb_build_object('project',(SELECT to_jsonb(p)-ARRAY['settings_version','effort_scale_name','effort_scale_examples','overload_threshold','near_due_days','settings_updated_at_utc']::TEXT[] FROM projects p LIMIT 1),'members',(SELECT jsonb_agg(to_jsonb(m) ORDER BY project_id,user_id) FROM project_memberships m),'invitations',(SELECT jsonb_agg(to_jsonb(i) ORDER BY id) FROM project_invitations i),'tasks',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM tasks t),'assignees',(SELECT jsonb_agg(to_jsonb(a) ORDER BY task_id,user_id) FROM task_assignees a),'events',(SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM activity_events e))::text");
    }
}
