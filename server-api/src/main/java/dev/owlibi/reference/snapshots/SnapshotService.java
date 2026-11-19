package dev.owlibi.reference.snapshots;

import dev.owlibi.reference.projects.ProjectService.Project;
import dev.owlibi.reference.projects.ProjectSettingsService;
import dev.owlibi.reference.web.ApiFailure;
import static dev.owlibi.reference.snapshots.ProjectSnapshot.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import dev.owlibi.reference.reports.ReportBudget;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SnapshotService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ProjectSettingsService settings;
    public SnapshotService(JdbcTemplate jdbc,Clock clock,ProjectSettingsService settings) { this.jdbc=jdbc;this.clock=clock;this.settings=settings; }
    private record Selected(Project project,String template) {}
    private static Instant instant(ResultSet rs,String column) throws SQLException { var v=rs.getTimestamp(column);return v==null?null:v.toInstant(); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ProjectSnapshot capture(UUID actor,UUID project) {
        return selectedCapture(actor,project,null);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=10)
    public ProjectSnapshot captureReport(UUID actor,UUID project,CaptureBudget budget) {
        return selectedCapture(actor,project,budget);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=10)
    public void authorizeReport(UUID actor,UUID project,CaptureBudget budget) {
        budget.statement(jdbc);
        var roles=jdbc.queryForList("SELECT role FROM project_memberships WHERE project_id=? AND user_id=?",String.class,project,actor);
        if(roles.isEmpty()) throw new ApiFailure(404,"NOT_FOUND","Resource unavailable");
        if(!roles.getFirst().equals("LEADER")) throw new ApiFailure(403,"FORBIDDEN","Action unavailable");budget.check();
    }
    private ProjectSnapshot selectedCapture(UUID actor,UUID project,CaptureBudget budget) {
        if(budget!=null) budget.statement(jdbc);
        // First domain SQL statement establishes the MVCC snapshot and current membership before sampling Clock.
        var selected=jdbc.query("SELECT p.*,m.role AS caller_role FROM projects p JOIN project_memberships m ON m.project_id=p.id WHERE p.id=? AND m.user_id=?",
            (rs,n) -> new Selected(new Project(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("description"),
                rs.getString("caller_role"),instant(rs,"created_at_utc")),rs.getString("template_snapshot")),project,actor);
        if(selected.isEmpty()) throw new ApiFailure(404,"NOT_FOUND","Resource unavailable");
        if(budget!=null && !selected.getFirst().project().role().equals("LEADER")) throw new ApiFailure(403,"FORBIDDEN","Action unavailable");
        Instant at=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(budget!=null) budget.statement(jdbc);
        int count=jdbc.queryForObject("SELECT count(*) FROM tasks WHERE project_id=?",Integer.class,project);
        if(count>1000) { if(budget!=null) throw ReportBudget.tooLarge();throw new ApiFailure(422,"TASK_LIMIT","Project task limit exceeded"); }
        if(budget!=null) {
            budget.statement(jdbc);
            if(jdbc.queryForObject("SELECT count(*) FROM activity_events WHERE project_id=?",Long.class,project)>ReportBudget.EVENTS) throw ReportBudget.tooLarge();
            var domain=new LinkedHashMap<String,Object>();domain.put("project",selected.getFirst().project());domain.put("templateSnapshot",JsonMapper.shared().readTree(selected.getFirst().template()));budget.add("project",domain);
            budget.statement(jdbc);
        }
        var members=jdbc.query("SELECT u.id,u.display_name,m.role,m.joined_at_utc FROM project_memberships m JOIN users u ON u.id=m.user_id WHERE m.project_id=? ORDER BY m.joined_at_utc,u.id",
            (rs,n) -> { var m=new Member(rs.getObject("id",UUID.class),rs.getString("display_name"),rs.getString("role"),instant(rs,"joined_at_utc"));if(budget!=null) budget.add("members",m);return m; },project);
        if(budget!=null) budget.statement(jdbc);
        var rules=settings.captureSettings(project);
        if(budget!=null) { if(!rules.configured()) throw new ApiFailure(409,"SETTINGS_REQUIRED","The leader must configure project rules");budget.add("settings",rules);budget.statement(jdbc); }
        var assignments=new HashMap<UUID,List<Assignment>>();
        for(var a:jdbc.query("SELECT a.*,u.display_name FROM task_assignees a JOIN users u ON u.id=a.user_id WHERE a.project_id=? ORDER BY a.task_id,a.user_id",
            (rs,n) -> new Assignment(rs.getObject("task_id",UUID.class),rs.getObject("project_id",UUID.class),rs.getObject("user_id",UUID.class),
                rs.getString("display_name"),instant(rs,"assigned_at_utc"),rs.getObject("accepted_agreement_version",Long.class),instant(rs,"accepted_at_utc")),project))
            assignments.computeIfAbsent(a.taskId(),unused -> new ArrayList<>()).add(a);
        if(budget!=null) budget.statement(jdbc);
        var tasks=jdbc.query("SELECT * FROM tasks WHERE project_id=? ORDER BY created_at_utc,id LIMIT 1001",(rs,n) -> { var t=new Task(rs.getObject("id",UUID.class),
            rs.getObject("project_id",UUID.class),rs.getString("title"),rs.getString("description"),rs.getString("category"),rs.getString("status"),
            rs.getObject("due_date",LocalDate.class),rs.getBigDecimal("effort_points").setScale(2).toPlainString(),rs.getLong("agreement_version"),rs.getLong("version"),
            instant(rs,"first_started_at_utc"),rs.getObject("created_by",UUID.class),instant(rs,"created_at_utc"),instant(rs,"updated_at_utc"),
            assignments.getOrDefault(rs.getObject("id",UUID.class),List.of()));if(budget!=null) budget.add("tasks",t);return t; },project);
        History history=null;
        if(budget!=null) {
            if(tasks.size()>ReportBudget.TASKS) throw ReportBudget.tooLarge();
            budget.statement(jdbc);
            // Payload lengths are checked in the same snapshot BEFORE fetching their text.
            var lengths=jdbc.queryForList("SELECT id,octet_length(change_summary::text) AS bytes FROM activity_events WHERE project_id=? ORDER BY occurred_at_utc,id LIMIT 10001",project);
            if(lengths.size()>ReportBudget.EVENTS) throw ReportBudget.tooLarge();
            for(var length:lengths) budget.rawRow(((Number)length.get("bytes")).longValue());
            var events=new ArrayList<Event>();
            for(int start=0;start<lengths.size();) {
                budget.statement(jdbc);var ids=new ArrayList<Object>();long batchBytes=0;
                // Batches never fetch more raw payload than the remaining captured-text budget.
                for(int index=start;index<lengths.size() && ids.size()<100;index++) {
                    long size=((Number)lengths.get(index).get("bytes")).longValue();budget.rawRow(size);
                    if(!ids.isEmpty() && batchBytes+size>ReportBudget.TEXT-budget.bytes()) break;
                    ids.add(lengths.get(index).get("id"));batchBytes+=size;
                }
                String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));var args=new ArrayList<Object>();args.add(project);args.addAll(ids);
                jdbc.query("SELECT e.id,e.project_id,e.task_id,e.actor_id,u.display_name,e.event_type,e.occurred_at_utc,e.reason,e.change_summary::text FROM activity_events e JOIN users u ON u.id=e.actor_id WHERE e.project_id=? AND e.id IN ("+placeholders+") ORDER BY e.occurred_at_utc,e.id",(org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String payload=rs.getString("change_summary");safePayload(JsonMapper.shared().readTree(payload));
                    var event=new Event(rs.getObject("id",UUID.class),rs.getObject("project_id",UUID.class),rs.getObject("task_id",UUID.class),rs.getObject("actor_id",UUID.class),rs.getString("display_name"),rs.getString("event_type"),instant(rs,"occurred_at_utc"),rs.getString("reason"),payload);
                    budget.add("events",event);events.add(event);
                },args.toArray());start+=ids.size();
            }
            if(events.size()!=lengths.size()) throw ReportBudget.unavailable();
            budget.check();
            java.security.MessageDigest digest;
            try { digest=java.security.MessageDigest.getInstance("SHA-256"); } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            digest.update((byte)'[');boolean first=true;
            for(var event:events) { budget.check();if(!first) digest.update((byte)',');first=false;digest.update(CanonicalState.json(event).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            digest.update((byte)']');history=new History(events,events.size(),HexFormat.of().formatHex(digest.digest()),budget.bytes());
        }
        LocalDate today=LocalDate.ofInstant(at,ZoneId.of("Asia/Manila"));
        var source=selected.getFirst();
        var draft=new ProjectSnapshot(source.project(),source.template(),members,rules,tasks,new Metadata(null,at),today);
        return new ProjectSnapshot(source.project(),source.template(),members,rules,tasks,new Metadata(CanonicalState.token(draft),at),today,history);
    }
    private static void safePayload(tools.jackson.databind.JsonNode node) {
        if(node.isObject()) for(var entry:node.properties()) {
            String key=entry.getKey().replace("_","").toLowerCase(Locale.ROOT);
            if(Set.of("email","password","passwordhash","token","tokenhash","invitationtoken","session","sessionid","csrf","secret").contains(key)) throw ReportBudget.unavailable();
            safePayload(entry.getValue());
        } else if(node.isArray()) node.forEach(SnapshotService::safePayload);
    }
}
