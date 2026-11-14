package dev.owlibi.reference.projects;

import dev.owlibi.reference.web.*;
import static dev.owlibi.reference.projects.ProjectRules.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ProjectSettingsService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public ProjectSettingsService(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc; this.clock=clock; }
    public void requireLeader(UUID actor,UUID project) {
        if (!requireMember(actor,project).equals("LEADER")) throw new ApiFailure(403,"FORBIDDEN","Action unavailable");
    }
    private String requireMember(UUID actor,UUID project) {
        var roles=jdbc.queryForList("SELECT role FROM project_memberships WHERE project_id=? AND user_id=?",String.class,project,actor);
        if (roles.isEmpty()) throw new ApiFailure(404,"NOT_FOUND","Resource unavailable"); return roles.getFirst();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Settings read(UUID actor,UUID project) {
        requireMember(actor,project); return stored(project);
    }
    @Transactional(propagation=Propagation.MANDATORY) public Settings initialize(UUID project,Values values,Instant at) {
        if (stored(project).configured()) throw new IllegalStateException("Project rules already initialized");
        Values normalized=validated(values); write(project,normalized,1,at); return stored(project);
    }
    @Transactional public Settings edit(UUID actor,UUID project,JsonNode body) {
        requireLeader(actor,project);
        jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE",UUID.class,project);
        JsonBody.validate(body,ProjectRules.PATCH);
        Settings before=stored(project); long requested=version(JsonBody.string(body,"settingsVersion"));
        if (requested!=Long.parseLong(before.settingsVersion())) throw new ApiFailure(409,"STALE_SETTINGS_VERSION","Reload the current project rules");
        Values prior=before.values();
        Values after=normalize(body,prior);
        if (after.equals(prior)) return before;
        if (requested==Long.MAX_VALUE) throw new ApiFailure(409,"VERSION_EXHAUSTED","Project rule version cannot advance");
        var changed=new ArrayList<String>();
        if (prior==null || !prior.effortScaleName().equals(after.effortScaleName())) changed.add("effortScaleName");
        if (prior==null || !prior.effortScaleExamples().equals(after.effortScaleExamples())) changed.add("effortScaleExamples");
        if (prior==null || !prior.overloadThreshold().equals(after.overloadThreshold())) changed.add("overloadThreshold");
        if (prior==null || prior.nearDueDays()!=after.nearDueDays()) changed.add("nearDueDays");
        Instant at=clock.instant().truncatedTo(ChronoUnit.MICROS); write(project,after,requested+1,at); Settings result=stored(project);
        var summary=Map.of("before",ProjectRules.summary(before),"after",ProjectRules.summary(result),"changedFields",changed);
        jdbc.update("INSERT INTO activity_events (id,project_id,actor_id,event_type,occurred_at_utc,change_summary) VALUES (?,?,?,'SETTINGS_CHANGED',?,?::jsonb)",
            UUID.randomUUID(),project,actor,Timestamp.from(at),JsonMapper.shared().writeValueAsString(summary));
        return result;
    }
    private void write(UUID project,Values values,long version,Instant at) {
        jdbc.update("UPDATE projects SET settings_version=?,effort_scale_name=?,effort_scale_examples=?::jsonb,overload_threshold=?,near_due_days=?,settings_updated_at_utc=? WHERE id=?",
            version,values.effortScaleName(),JsonMapper.shared().writeValueAsString(values.effortScaleExamples()),new java.math.BigDecimal(values.overloadThreshold()),values.nearDueDays(),Timestamp.from(at),project);
    }
    @Transactional(readOnly=true,propagation=Propagation.MANDATORY) public Settings captureSettings(UUID project) { return stored(project); }
    private Settings stored(UUID project) {
        return jdbc.queryForObject("SELECT id,project_zone,settings_version,effort_scale_name,effort_scale_examples::text,overload_threshold,near_due_days,settings_updated_at_utc FROM projects WHERE id=?",(rs,n) -> {
            long version=rs.getLong("settings_version"); List<Example> examples=null;
            if (version>0) {
                var values=new ArrayList<Example>();
                for(var item:JsonMapper.shared().readTree(rs.getString("effort_scale_examples"))) values.add(new Example(item.get("points").asText(),item.get("label").asText()));
                examples=List.copyOf(values);
            }
            var updated=rs.getTimestamp("settings_updated_at_utc"); var threshold=rs.getBigDecimal("overload_threshold");
            return new Settings(rs.getObject("id",UUID.class),version>0,Long.toString(version),rs.getString("project_zone"),rs.getString("effort_scale_name"),examples,
                threshold==null?null:threshold.toPlainString(),rs.getObject("near_due_days",Integer.class),updated==null?null:updated.toInstant());
        },project);
    }
}
