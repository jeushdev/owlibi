package dev.owlibi.reference.persistence;
import static org.junit.jupiter.api.Assertions.*;
import static dev.owlibi.reference.support.PostgresFixture.*;
import dev.owlibi.reference.support.PostgresFixture;
import org.junit.jupiter.api.Test;
class PersistenceRestartIT {
    @Test void committedSyntheticDataAndSchemaSurviveDatabaseAndApplicationRestarts() throws Exception {
        try (var db=new PostgresFixture()) {
            db.migrate(); var project=db.seed();
            try(var app=db.startApp()) {
                assertNotNull(app.getBean(dev.owlibi.reference.health.DatabaseReadinessService.class));
                assertEquals("owlibi_runtime",app.getBean(org.springframework.jdbc.core.JdbcTemplate.class).queryForObject("SELECT current_user",String.class));
                try(var migration=app.getBean(org.flywaydb.core.Flyway.class).getConfiguration().getDataSource().getConnection()) {
                    assertEquals("owlibi_migrator",query(migration,"SELECT current_user"));
                }
            }
            db.restart();
            try(var c=db.runtime()) {
                assertEquals("1",query(c,"SELECT count(*) FROM projects WHERE id='"+project+"'"));
                assertEquals("1",query(c,"SELECT count(*) FROM activity_events"));
            }
            try(var app=db.startApp()) { assertTrue(app.getBean(dev.owlibi.reference.health.DatabaseReadinessService.class).isReady()); }
        }
    }
}
