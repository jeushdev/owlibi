package dev.owlibi.reference.tasks;

import dev.owlibi.reference.snapshots.SnapshotService;
import dev.owlibi.reference.web.*;
import static dev.owlibi.reference.tasks.TaskDtos.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TaskService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SnapshotService snapshots;
    public TaskService(JdbcTemplate jdbc,Clock clock,SnapshotService snapshots) { this.jdbc=jdbc; this.clock=clock; this.snapshots=snapshots; }
    private record Stored(UUID id,UUID project,String title,String description,String category,String status,
        LocalDate due,String points,long agreement,long version,Instant firstStart,UUID creator,Instant created,Instant updated) {}
    private record Assigned(UUID task,UUID user,String name,Long agreement,Instant time) {}
    private static Instant instant(ResultSet rs,String field) throws SQLException {
        var time=rs.getTimestamp(field); return time==null?null:time.toInstant();
    }
    private static final RowMapper<Stored> TASK_ROW=(rs,n) -> new Stored(rs.getObject("id",UUID.class),rs.getObject("project_id",UUID.class),
        rs.getString("title"),rs.getString("description"),rs.getString("category"),rs.getString("status"),rs.getObject("due_date",LocalDate.class),
        rs.getBigDecimal("effort_points").toPlainString(),rs.getLong("agreement_version"),rs.getLong("version"),instant(rs,"first_started_at_utc"),
        rs.getObject("created_by",UUID.class),instant(rs,"created_at_utc"),instant(rs,"updated_at_utc"));
    private static final RowMapper<Assigned> ASSIGNEE_ROW=(rs,n) -> new Assigned(rs.getObject("task_id",UUID.class),rs.getObject("user_id",UUID.class),
        rs.getString("display_name"),rs.getObject("accepted_agreement_version",Long.class),instant(rs,"accepted_at_utc"));
    private static final String ASSIGNEES="SELECT a.*,u.display_name FROM task_assignees a JOIN users u ON u.id=a.user_id ";
    public String requireMember(UUID actor,UUID project) {
        var roles=jdbc.queryForList("SELECT role FROM project_memberships WHERE project_id=? AND user_id=?",String.class,project,actor);
        if (roles.isEmpty()) throw missing(); return roles.getFirst();
    }
    public void requireLeader(UUID actor,UUID project) { if (!requireMember(actor,project).equals("LEADER")) throw forbidden(); }

    @Transactional public Task create(UUID actor,UUID project,String title,String description,String category,String dueDate,String effortPoints,List<String> assigneeIds) {
        return createTask(actor,project,title,description,category,dueDate,effortPoints,assigneeIds,null);
    }
    @Transactional(propagation=Propagation.MANDATORY) public Task createStarter(UUID actor,UUID project,String title,String description,String category,String effortPoints,Instant createdAt) {
        return createTask(actor,project,title,description,category,null,effortPoints,List.of(actor.toString()),createdAt);
    }
    private Task createTask(UUID actor,UUID project,String title,String description,String category,String dueDate,String effortPoints,List<String> assigneeIds,Instant initialTime) {
        requireLeader(actor,project);
        title=Fields.text(title,"title",200,true,true);
        description=Fields.text(description,"description",4000,false,false);
        category=Fields.text(category,"category",100,false,false);
        var due=TaskFields.date(dueDate); var points=Fields.points(effortPoints,"effortPoints"); var ids=TaskFields.assignees(assigneeIds);
        // Shared with invitation capacity locking. READ_COMMITTED sees a predecessor's committed insertion after this lock.
        jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE",UUID.class,project);
        for (UUID id:ids) if (jdbc.queryForObject("SELECT count(*) FROM project_memberships WHERE project_id=? AND user_id=?",Integer.class,project,id)!=1)
            throw ApiFailure.validation("assigneeIds");
        if (taskCount(project)>=1000) throw taskLimit();
        UUID id=UUID.randomUUID(); Instant now=initialTime==null?now():initialTime;
        jdbc.update("INSERT INTO tasks (id,project_id,title,description,category,status,due_date,effort_points,agreement_version,version,created_by,created_at_utc,updated_at_utc) VALUES (?,?,?,?,?,'TODO',?,?,1,0,?,?,?)",
            id,project,title,description,category,due,points,actor,Timestamp.from(now),Timestamp.from(now));
        for (UUID user:ids) jdbc.update("INSERT INTO task_assignees (task_id,project_id,user_id,assigned_at_utc) VALUES (?,?,?,?)",id,project,user,Timestamp.from(now));
        var after=new LinkedHashMap<String,Object>();
        after.put("title",title); after.put("description",description); after.put("category",category); after.put("dueDate",due==null?null:due.toString());
        after.put("effortPoints",points.toPlainString()); after.put("assigneeIds",ids); after.put("status","TODO"); after.put("agreementVersion",1); after.put("version",0);
        event(project,id,actor,"TASK_CREATED",now,null,Map.of("before",Map.of(),"after",after));
        return response(load(project,id,false),actor,assigned(id));
    }
    @Transactional public Task edit(UUID actor,UUID project,UUID task,TaskPatch patch) {
        requireLeader(actor,project); Stored stored=load(project,task,true); var assignees=assigned(task);
        long version=TaskFields.version(patch.version(),"version",0);
        if (version!=stored.version()) throw new ApiFailure(409,"STALE_VERSION","Reload the current task");
        if (Set.of("DONE","CANCELLED").contains(stored.status())) throw state();
        String title=patch.title().supplied()?Fields.text(patch.title().value(),"title",200,true,true):stored.title();
        String description=patch.description().supplied()?Fields.text(patch.description().value(),"description",4000,false,false):stored.description();
        String category=patch.category().supplied()?Fields.text(patch.category().value(),"category",100,false,false):stored.category();
        LocalDate due=patch.dueDate().supplied()?TaskFields.date(patch.dueDate().value()):stored.due();
        var points=patch.effortPoints().supplied()?Fields.points(patch.effortPoints().value(),"effortPoints"):new java.math.BigDecimal(stored.points());
        var previousIds=assignees.stream().map(Assigned::user).toList();
        var ids=patch.assigneeIds().supplied()?TaskFields.assignees(patch.assigneeIds().value()):previousIds;
        for (UUID id:ids) if (jdbc.queryForObject("SELECT count(*) FROM project_memberships WHERE project_id=? AND user_id=?",Integer.class,project,id)!=1)
            throw ApiFailure.validation("assigneeIds");
        var changed=new ArrayList<String>();
        if (!title.equals(stored.title())) changed.add("title");
        if (!description.equals(stored.description())) changed.add("description");
        if (!category.equals(stored.category())) changed.add("category");
        if (!Objects.equals(due,stored.due())) changed.add("dueDate");
        if (points.compareTo(new java.math.BigDecimal(stored.points()))!=0) changed.add("effortPoints");
        if (!new HashSet<>(ids).equals(new HashSet<>(previousIds))) changed.add("assigneeIds");
        boolean revise=changed.stream().anyMatch(field -> Set.of("title","description","effortPoints","assigneeIds").contains(field));
        String reason=Fields.text(patch.reason(),"reason",1000,true,revise && stored.firstStart()!=null);
        if (changed.isEmpty()) return response(stored,actor,assignees);
        Instant now=now(); long agreement=stored.agreement()+(revise?1:0);
        var before=editSummary(stored.title(),stored.description(),stored.category(),stored.due(),stored.points(),previousIds,stored.status(),stored.version(),stored.agreement());
        jdbc.update("UPDATE tasks SET title=?,description=?,category=?,due_date=?,effort_points=?,agreement_version=?,version=version+1,updated_at_utc=? WHERE id=?",
            title,description,category,due,points,agreement,Timestamp.from(now),task);
        for (UUID user:previousIds) if (!ids.contains(user)) jdbc.update("DELETE FROM task_assignees WHERE task_id=? AND user_id=?",task,user);
        for (UUID user:ids) if (!previousIds.contains(user)) jdbc.update("INSERT INTO task_assignees (task_id,project_id,user_id,assigned_at_utc) VALUES (?,?,?,?)",task,project,user,Timestamp.from(now));
        if (revise) jdbc.update("UPDATE task_assignees SET accepted_agreement_version=NULL,accepted_at_utc=NULL WHERE task_id=?",task);
        var after=editSummary(title,description,category,due,points.toPlainString(),ids,stored.status(),version+1,agreement);
        event(project,task,actor,"TASK_EDITED",now,reason.isEmpty()?null:reason,Map.of("before",before,"after",after,"changedFields",changed));
        return response(load(project,task,false),actor,assigned(task));
    }
    private static Map<String,Object> editSummary(String title,String description,String category,LocalDate due,String points,List<UUID> ids,String status,long version,long agreement) {
        var summary=new LinkedHashMap<String,Object>();
        summary.put("title",title); summary.put("description",description); summary.put("category",category); summary.put("dueDate",due==null?null:due.toString());
        summary.put("effortPoints",points); summary.put("assigneeIds",ids.stream().sorted().toList()); summary.put("status",status);
        summary.put("version",version); summary.put("agreementVersion",agreement); return summary;
    }
    @Transactional public Task accept(UUID actor,UUID project,UUID task,Long agreementValue) {
        requireMember(actor,project); Stored stored=load(project,task,true); var assignees=assigned(task);
        Assigned mine=assignees.stream().filter(a -> a.user().equals(actor)).findFirst().orElseThrow(TaskService::forbidden);
        long agreement=TaskFields.version(agreementValue,"agreementVersion",1);
        if (agreement!=stored.agreement()) throw new ApiFailure(409,"STALE_AGREEMENT","Reload the current agreement");
        if (Objects.equals(mine.agreement(),agreement)) return response(stored,actor,assignees);
        if (Set.of("DONE","CANCELLED").contains(stored.status())) throw state();
        Instant now=now();
        jdbc.update("UPDATE task_assignees SET accepted_agreement_version=?,accepted_at_utc=? WHERE task_id=? AND user_id=?",agreement,Timestamp.from(now),task,actor);
        jdbc.update("UPDATE tasks SET version=version+1,updated_at_utc=? WHERE id=?",Timestamp.from(now),task);
        event(project,task,actor,"AGREEMENT_ACCEPTED",now,null,Map.of("before",Map.of("accepted",false,"version",stored.version()),
            "after",Map.of("userId",actor,"accepted",true,"agreementVersion",agreement,"version",stored.version()+1)));
        return response(load(project,task,false),actor,assigned(task));
    }
    @Transactional public Task transition(UUID actor,UUID project,UUID task,String action,Long versionValue,String reasonValue) {
        String role=requireMember(actor,project); Stored stored=load(project,task,true); var assignees=assigned(task);
        if (action==null || !Set.of("START","SUBMIT_REVIEW","APPROVE","RETURN","CANCEL","REOPEN").contains(action)) throw ApiFailure.validation("action");
        boolean leader=role.equals("LEADER");
        boolean worker=assignees.stream().anyMatch(a -> a.user().equals(actor));
        if (!leader && (!Set.of("START","SUBMIT_REVIEW").contains(action) || !worker)) throw forbidden();
        long version=TaskFields.version(versionValue,"version",0);
        if (version!=stored.version()) throw new ApiFailure(409,"STALE_VERSION","Reload the current task");
        boolean needsReason=Set.of("RETURN","CANCEL","REOPEN").contains(action);
        String reason=Fields.text(reasonValue,"reason",1000,true,needsReason); if (reason.isEmpty()) reason=null;
        String next=switch (action) {
            case "START" -> { if (!stored.status().equals("TODO")) throw state(); yield "IN_PROGRESS"; }
            case "SUBMIT_REVIEW" -> { if (!stored.status().equals("IN_PROGRESS")) throw state(); yield "REVIEW"; }
            case "APPROVE" -> { if (!stored.status().equals("REVIEW")) throw state(); yield "DONE"; }
            case "RETURN" -> { if (!stored.status().equals("REVIEW")) throw state(); yield "IN_PROGRESS"; }
            case "REOPEN" -> { if (!stored.status().equals("DONE")) throw state(); yield "IN_PROGRESS"; }
            case "CANCEL" -> { if (!Set.of("TODO","IN_PROGRESS","REVIEW").contains(stored.status())) throw state(); yield "CANCELLED"; }
            default -> throw ApiFailure.validation("action");
        };
        if (Set.of("START","SUBMIT_REVIEW","APPROVE","REOPEN").contains(action) &&
            (assignees.isEmpty() || assignees.stream().anyMatch(a -> !Objects.equals(a.agreement(),stored.agreement()))))
            throw new ApiFailure(422,"ACCEPTANCE_REQUIRED","Every current assignee must accept");
        Instant now=now(); Instant first=stored.firstStart();
        if (action.equals("START") && first==null) first=now;
        jdbc.update("UPDATE tasks SET status=?,version=version+1,updated_at_utc=?,first_started_at_utc=? WHERE id=?",
            next,Timestamp.from(now),first==null?null:Timestamp.from(first),task);
        var before=new LinkedHashMap<String,Object>(); before.put("status",stored.status()); before.put("version",version); before.put("firstStartedAtUtc",stored.firstStart()==null?null:stored.firstStart().toString());
        var after=new LinkedHashMap<String,Object>(); after.put("status",next); after.put("version",version+1); after.put("firstStartedAtUtc",first==null?null:first.toString());
        event(project,task,actor,action,now,reason,Map.of("before",before,"after",after));
        return response(load(project,task,false),actor,assignees);
    }
    public Board board(UUID actor,UUID project) { return snapshots.capture(actor,project).board(actor); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Activity activity(UUID actor,UUID project,String limitValue,String cursorValue) {
        requireMember(actor,project);
        int limit=limitValue==null?50:Cursor.limit(limitValue); Cursor cursor=Cursor.decode(cursorValue);
        String sql="SELECT e.*,u.display_name FROM activity_events e JOIN users u ON u.id=e.actor_id WHERE e.project_id=?";
        var args=new ArrayList<Object>(); args.add(project);
        if (cursor!=null) { sql+=" AND (e.occurred_at_utc,e.id)<(?,?)"; args.add(Timestamp.from(cursor.time())); args.add(cursor.id()); }
        sql+=" ORDER BY e.occurred_at_utc DESC,e.id DESC LIMIT ?"; args.add(limit+1);
        List<Event> rows;
        try {
            rows=jdbc.query(sql,(rs,n) -> new Event(rs.getObject("id",UUID.class),rs.getObject("project_id",UUID.class),rs.getObject("task_id",UUID.class),
                rs.getObject("actor_id",UUID.class),rs.getString("display_name"),rs.getString("event_type"),instant(rs,"occurred_at_utc"),rs.getString("reason"),
                JsonMapper.shared().readTree(rs.getString("change_summary"))),args.toArray());
        } catch (org.springframework.dao.DataAccessException failure) {
            if (cursor!=null && failure.getRootCause() instanceof SQLException cause && Set.of("22007","22008").contains(cause.getSQLState())) throw ApiFailure.malformed();
            throw failure;
        }
        String next=null;
        if (rows.size()>limit) { rows=rows.subList(0,limit); var last=rows.getLast(); next=new Cursor(last.occurredAtUtc(),last.id()).encode(); }
        return new Activity(List.copyOf(rows),next);
    }
    private Stored load(UUID project,UUID task,boolean lock) {
        var rows=jdbc.query("SELECT * FROM tasks WHERE project_id=? AND id=?"+(lock?" FOR UPDATE":""),TASK_ROW,project,task);
        if (rows.isEmpty()) throw missing(); return rows.getFirst();
    }
    private List<Assigned> assigned(UUID task) { return jdbc.query(ASSIGNEES+"WHERE a.task_id=? ORDER BY a.user_id",ASSIGNEE_ROW,task); }
    private Task response(Stored stored,UUID actor,List<Assigned> assigned) {
        var acceptances=assigned.stream().map(a -> new Acceptance(a.user(),a.name(),Objects.equals(a.agreement(),stored.agreement()),
            Objects.equals(a.agreement(),stored.agreement())?a.time():null)).toList();
        return new Task(stored.id(),stored.project(),stored.title(),stored.description(),stored.category(),stored.status(),stored.due(),stored.points(),stored.agreement(),
            acceptances,acceptances.stream().anyMatch(a -> a.userId().equals(actor) && a.accepted()),assigned.stream().map(Assigned::user).toList(),stored.version(),
            stored.firstStart(),stored.creator(),stored.created(),stored.updated());
    }
    private void event(UUID project,UUID task,UUID actor,String type,Instant now,String reason,Map<String,Object> summary) {
        jdbc.update("INSERT INTO activity_events (id,project_id,task_id,actor_id,event_type,occurred_at_utc,reason,change_summary) VALUES (?,?,?,?,?,?,?,?::jsonb)",
            UUID.randomUUID(),project,task,actor,type,Timestamp.from(now),reason,JsonMapper.shared().writeValueAsString(summary));
    }
    private int taskCount(UUID project) { return jdbc.queryForObject("SELECT count(*) FROM tasks WHERE project_id=?",Integer.class,project); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static ApiFailure missing() { return new ApiFailure(404,"NOT_FOUND","Resource unavailable"); }
    private static ApiFailure forbidden() { return new ApiFailure(403,"FORBIDDEN","Action unavailable"); }
    private static ApiFailure state() { return new ApiFailure(422,"INVALID_TRANSITION","Action unavailable in the current state"); }
    private static ApiFailure taskLimit() { return new ApiFailure(422,"TASK_LIMIT","Project task limit exceeded"); }
}
