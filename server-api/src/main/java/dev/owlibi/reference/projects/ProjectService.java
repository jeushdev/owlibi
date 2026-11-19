package dev.owlibi.reference.projects;

import dev.owlibi.reference.security.RateLimits;
import dev.owlibi.reference.web.*;
import dev.owlibi.reference.templates.TemplateCatalog;
import dev.owlibi.reference.tasks.TaskService;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ProjectService {
    public record Project(UUID id,String name,String description,String role,Instant createdAtUtc) {}
    public record Page(List<Project> items,String nextCursor) {}
    public record Created(UUID id,String name,String description,String role,Instant createdAtUtc,TemplateCatalog.Ref templateRef,ProjectRules.Settings settings,List<UUID> starterTaskIds) {}
    public record Invitation(String token,Instant expiresAtUtc) {}
    private record StoredInvitation(UUID id,UUID projectId,Instant expires,boolean consumed) {}
    private final JdbcTemplate jdbc;
    private final TemplateCatalog templates;
    private final ProjectSettingsService settings;
    private final TaskService tasks;
    private final Clock clock;
    private final RateLimits rates;
    private final Duration invitationTtl;
    private final SecureRandom random=new SecureRandom();
    public ProjectService(JdbcTemplate jdbc,Clock clock,RateLimits rates,TemplateCatalog templates,ProjectSettingsService settings,TaskService tasks,@Value("${owlibi.invitation-ttl:PT24H}") Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Invitation TTL must be positive");
        this.jdbc=jdbc; this.clock=clock; this.rates=rates; this.invitationTtl=ttl; this.templates=templates; this.settings=settings; this.tasks=tasks;
    }
    @Transactional(readOnly=true) public Page list(UUID actor,String limitValue,String cursorValue) {
        int limit=Cursor.limit(limitValue); Cursor cursor=Cursor.decode(cursorValue);
        String sql="SELECT p.id,p.name,p.description,m.role,p.created_at_utc FROM projects p JOIN project_memberships m ON m.project_id=p.id WHERE m.user_id=?";
        var parameters=new ArrayList<Object>(); parameters.add(actor);
        if (cursor!=null) { sql+=" AND (p.created_at_utc,p.id)<(?,?)"; parameters.add(Timestamp.from(cursor.time())); parameters.add(cursor.id()); }
        sql+=" ORDER BY p.created_at_utc DESC,p.id DESC LIMIT ?"; parameters.add(limit+1);
        List<Project> rows;
        try {
            rows=jdbc.query(sql,(rs,n) -> new Project(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("description"),rs.getString("role"),rs.getTimestamp("created_at_utc").toInstant()),parameters.toArray());
        } catch (org.springframework.dao.DataAccessException failure) {
            // An otherwise canonical cursor may contain a timestamp outside PostgreSQL's range.
            if (cursor!=null && failure.getRootCause() instanceof java.sql.SQLException cause &&
                Set.of("22007","22008").contains(cause.getSQLState())) throw ApiFailure.malformed();
            throw failure;
        }
        String next=null;
        if (rows.size()>limit) { rows=rows.subList(0,limit); var last=rows.getLast(); next=new Cursor(last.createdAtUtc(),last.id()).encode(); }
        return new Page(List.copyOf(rows),next);
    }
    public void creationAttempt(UUID actor) { rates.attempt("project:"+actor,5); }
    public void redemptionAttempt(String peer) { rates.attempt("redeem:"+peer,20); }
    public void invitationAttempt(UUID actor,UUID project) { settings.requireLeader(actor,project); rates.attempt("invite:"+actor+":"+project,20); }
    @Transactional public Created create(UUID actor,String name,String description,TemplateCatalog.Selection selection,ProjectRules.Values rules) {
        name=Fields.text(name,"name",200,true,true); description=Fields.text(description,"description",4000,false,false);
        var template=templates.forCreation(selection);
        if (rules==null && !template.id().equals("blank")) throw ApiFailure.validation("settings");
        if (rules!=null) rules=ProjectRules.validated(rules);
        Instant now=now(); UUID id=UUID.randomUUID();
        var snapshot=new LinkedHashMap<String,Object>(); snapshot.put("schemaVersion",1); snapshot.put("template",template); snapshot.put("effectiveSettings",rules);
        jdbc.update("INSERT INTO projects (id,name,description,leader_id,template_snapshot,created_at_utc) VALUES (?,?,?,?,?::jsonb,?)",id,name,description,actor,JsonMapper.shared().writeValueAsString(snapshot),Timestamp.from(now));
        jdbc.update("INSERT INTO project_memberships (project_id,user_id,role,joined_at_utc) VALUES (?,?,'LEADER',?)",id,actor,Timestamp.from(now));
        var chosen=rules==null?null:settings.initialize(id,rules,now);
        var after=new LinkedHashMap<String,Object>(); after.put("name",name); after.put("description",description); after.put("leaderId",actor); after.put("memberCount",1);
        after.put("templateRef",new TemplateCatalog.Ref(template.id(),template.version())); after.put("settings",chosen==null?null:ProjectRules.summary(chosen));
        event(id,actor,"PROJECT_CREATED",now,Map.of("before",Map.of(),"after",after));
        var starters=new ArrayList<UUID>();
        for(var starter:template.starterTasks()) starters.add(tasks.createStarter(actor,id,starter.title(),starter.description(),starter.category(),starter.effortPoints(),now).id());
        return new Created(id,name,description,"LEADER",now,new TemplateCatalog.Ref(template.id(),template.version()),chosen,List.copyOf(starters));
    }
    @Transactional public Invitation invite(UUID actor,UUID project) {
        settings.requireLeader(actor,project); lockProject(project);
        if (memberCount(project)>=6) throw full();
        Instant now=now();
        int outstanding=jdbc.queryForObject("SELECT count(*) FROM project_invitations WHERE project_id=? AND accepted_at_utc IS NULL AND expires_at_utc>?",Integer.class,project,Timestamp.from(now));
        if (outstanding>=100) throw new ApiFailure(422,"INVITATION_LIMIT","Outstanding invitation limit reached");
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID id=UUID.randomUUID(); Instant requestedExpiry=now.plus(invitationTtl);
        Instant expires=requestedExpiry.truncatedTo(ChronoUnit.MICROS);
        // Round up to the database's precision so every representable positive TTL can expire.
        if (expires.isBefore(requestedExpiry)) expires=expires.plusNanos(1000);
        jdbc.update("INSERT INTO project_invitations (id,project_id,token_hash,created_by,created_at_utc,expires_at_utc) VALUES (?,?,?,?,?,?)",
            id,project,hash(bytes),actor,Timestamp.from(now),Timestamp.from(expires));
        event(project,actor,"INVITATION_CREATED",now,Map.of("before",Map.of(),"after",Map.of("invitationId",id,"expiresAtUtc",expires.toString())));
        return new Invitation(token,expires);
    }
    @Transactional public UUID accept(UUID actor,String token) {
        if (token==null) throw ApiFailure.validation("token");
        if (!token.matches("[A-Za-z0-9_-]{43}")) throw unavailable();
        byte[] bytes;
        try { bytes=Base64.getUrlDecoder().decode(token); } catch (IllegalArgumentException bad) { throw unavailable(); }
        if (bytes.length!=32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) throw unavailable();
        // Lock invitation, then project for both one-use and capacity. No path acquires these in reverse.
        var found=jdbc.query("SELECT id,project_id,expires_at_utc,accepted_at_utc FROM project_invitations WHERE token_hash=? FOR UPDATE",
            (rs,n) -> new StoredInvitation(rs.getObject("id",UUID.class),rs.getObject("project_id",UUID.class),rs.getTimestamp("expires_at_utc").toInstant(),rs.getTimestamp("accepted_at_utc")!=null),hash(bytes));
        if (found.isEmpty()) throw unavailable();
        var invitation=found.getFirst(); lockProject(invitation.projectId());
        Instant now=now();
        if (invitation.consumed() || !now.isBefore(invitation.expires()) || isMember(actor,invitation.projectId())) throw unavailable();
        int count=memberCount(invitation.projectId()); if (count>=6) throw full();
        jdbc.update("INSERT INTO project_memberships (project_id,user_id,role,joined_at_utc) VALUES (?,?,'MEMBER',?)",invitation.projectId(),actor,Timestamp.from(now));
        jdbc.update("UPDATE project_invitations SET accepted_at_utc=?,accepted_by=? WHERE id=?",Timestamp.from(now),actor,invitation.id());
        event(invitation.projectId(),actor,"MEMBER_JOINED",now,Map.of("before",Map.of("memberCount",count),"after",Map.of("memberId",actor,"role","MEMBER","memberCount",count+1,"invitationId",invitation.id())));
        return invitation.projectId();
    }
    private boolean isMember(UUID actor,UUID project) { return jdbc.queryForObject("SELECT count(*) FROM project_memberships WHERE project_id=? AND user_id=?",Integer.class,project,actor)>0; }
    private void lockProject(UUID project) { jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE",UUID.class,project); }
    private int memberCount(UUID project) { return jdbc.queryForObject("SELECT count(*) FROM project_memberships WHERE project_id=?",Integer.class,project); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private void event(UUID project,UUID actor,String type,Instant now,Map<String,Object> summary) {
        jdbc.update("INSERT INTO activity_events (id,project_id,actor_id,event_type,occurred_at_utc,change_summary) VALUES (?,?,?,?,?,?::jsonb)",
            UUID.randomUUID(),project,actor,type,Timestamp.from(now),JsonMapper.shared().writeValueAsString(summary));
    }
    private static ApiFailure unavailable() { return new ApiFailure(409,"INVITATION_UNAVAILABLE","Invitation is unavailable"); }
    private static ApiFailure full() { return new ApiFailure(409,"PROJECT_FULL","Project has reached its member limit"); }
}
