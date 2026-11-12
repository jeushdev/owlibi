package dev.owlibi.reference.health;
import jakarta.annotation.PostConstruct;
import org.flywaydb.core.Flyway;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
@DependsOnDatabaseInitialization
public class DatabaseReadinessService {
    private final JdbcTemplate jdbc;
    private final Flyway flyway;
    public DatabaseReadinessService(JdbcTemplate jdbc, ObjectProvider<Flyway> migrations, DataSource runtime) {
        this.jdbc = jdbc;
        // Hosted mode disables startup migration. Validation needs SELECT only and uses the runtime role.
        this.flyway = migrations.getIfAvailable(() -> Flyway.configure().dataSource(runtime)
            .defaultSchema("owlibi").schemas("owlibi").createSchemas(false).cleanDisabled(true).load());
    }
    @PostConstruct void requireCompatibleSchema() {
        if (!isReady()) throw new IllegalStateException("Database unavailable or schema incompatible");
    }
    public boolean isReady() {
        try {
            if (!flyway.validateWithResult().validationSuccessful) return false;
            String version = jdbc.queryForObject("SELECT version FROM owlibi.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1", String.class);
            if (!"4".equals(version)) return false;
            jdbc.queryForObject("SELECT count(*) FROM owlibi.users WHERE false", Integer.class);
            jdbc.queryForObject("SELECT count(settings_version),count(effort_scale_name),count(effort_scale_examples),count(overload_threshold),count(near_due_days),count(settings_updated_at_utc) FROM owlibi.projects WHERE false", (rs,n) -> rs.getInt(1));
            jdbc.queryForObject("SELECT count(*) FROM owlibi.project_memberships WHERE false", Integer.class);
            jdbc.queryForObject("SELECT count(*) FROM owlibi.project_invitations WHERE false", Integer.class);
            jdbc.queryForObject("SELECT count(*) FROM owlibi.activity_events WHERE false", Integer.class);
            jdbc.queryForObject("SELECT count(*) FROM owlibi.tasks WHERE false", Integer.class);
            jdbc.queryForObject("SELECT count(*) FROM owlibi.task_assignees WHERE false", Integer.class);
            return true;
        } catch (RuntimeException unavailable) { return false; }
    }
}
