package dev.owlibi.reference.persistence;
import static org.junit.jupiter.api.Assertions.*;
import static dev.owlibi.reference.support.PostgresFixture.*;
import dev.owlibi.reference.support.PostgresFixture;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class MigrationIT {
    @Test void emptyAndExistingV1MigrateWithoutDataLossAndDefaultGrantsApplyOnUpgrade() throws Exception {
        try (var db = new PostgresFixture()) {
            assertEquals(1, org.flywaydb.core.Flyway.configure().configuration(db.flyway().getConfiguration()).target("1").load().migrate().migrationsExecuted);
            var project = db.seed();
            assertEquals(1, org.flywaydb.core.Flyway.configure().configuration(db.flyway().getConfiguration()).target("2").load().migrate().migrationsExecuted);
            try (var c=db.runtime()) {
                assertEquals("1",query(c,"SELECT count(*) FROM projects WHERE id='"+project+"'"));
                assertEquals("2",query(c,"SELECT count(*) FROM flyway_schema_history WHERE success"));
            }
            assertEquals(2, db.flyway().migrate().migrationsExecuted);
            assertEquals(0, db.flyway().migrate().migrationsExecuted);
            db.flyway().validate();
            try (var c = db.runtime()) {
                assertEquals("1",query(c,"SELECT count(*) FROM projects WHERE id='"+project+"'"));
                assertEquals("4",query(c,"SELECT count(*) FROM flyway_schema_history WHERE success"));
            }
            // A test-only future migration proves V4->V5 and grants; it is not application source.
            var dir = Files.createTempDirectory("owlibi-synthetic-upgrade-");
            var file = dir.resolve("V5__synthetic_upgrade.sql");
            try {
                Files.writeString(file,"CREATE TABLE synthetic_upgrade (id INTEGER PRIMARY KEY); INSERT INTO synthetic_upgrade VALUES (1);");
                assertEquals(1,db.flyway("filesystem:"+dir).migrate().migrationsExecuted);
                try (var c = db.runtime()) {
                    assertEquals("1",query(c,"SELECT count(*) FROM synthetic_upgrade"));
                    sql(c,"INSERT INTO synthetic_upgrade VALUES (2)");
                    assertEquals("1",query(c,"SELECT count(*) FROM projects"));
                }
            } finally { Files.deleteIfExists(file); Files.deleteIfExists(dir); }
        }
    }
    @Test void failedUpgradeRollsBackAndIdentityProjectConstraintsAreReal() throws Exception {
        try (var db = new PostgresFixture()) {
            db.migrate(); db.seed();
            var dir=Files.createTempDirectory("owlibi-synthetic-rollback-");
            var file=dir.resolve("V5__broken_upgrade.sql");
            try {
                Files.writeString(file,"CREATE TABLE synthetic_rollback (id INTEGER); SELECT 1/0;");
                assertThrows(RuntimeException.class,() -> db.flyway("filesystem:"+dir).migrate());
                try (var c=db.runtime()) {
                    assertNull(query(c,"SELECT to_regclass('owlibi.synthetic_rollback')"));
                    assertEquals("4",query(c,"SELECT count(*) FROM flyway_schema_history WHERE success"));
                    assertThrows(java.sql.SQLException.class,() -> sql(c,"INSERT INTO users SELECT * FROM users"));
                    assertThrows(java.sql.SQLException.class,() -> sql(c,"UPDATE project_memberships SET role='OWNER'"));
                    assertThrows(java.sql.SQLException.class,() -> sql(c,"UPDATE projects SET template_snapshot='{}'"));
                    assertThrows(java.sql.SQLException.class,() -> sql(c,"UPDATE projects SET leader_id='00000000-0000-0000-0000-000000000000'"));
                    assertThrows(java.sql.SQLException.class,() -> sql(c,"INSERT INTO project_invitations (id,project_id,token_hash,created_by,created_at_utc,expires_at_utc) SELECT gen_random_uuid(),id,decode('00','hex'),leader_id,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM projects"));
                }
            } finally { Files.deleteIfExists(file); Files.deleteIfExists(dir); }
        }
    }
}
