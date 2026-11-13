package dev.owlibi.reference.persistence;
import static org.junit.jupiter.api.Assertions.*;
import static dev.owlibi.reference.support.PostgresFixture.*;
import dev.owlibi.reference.support.PostgresFixture;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
class RuntimePrivilegesIT {
    @Test void runtimeDmlAllowedButDdlOwnershipHistoryAndEventMutationDenied() throws Exception {
        try (var db=new PostgresFixture()) {
            db.migrate(); db.seed();
            try (var c=db.runtime()) {
                assertEquals("owlibi_runtime",query(c,"SELECT current_user"));
                sql(c,"UPDATE users SET display_name='Synthetic updated'");
                sql(c,"DELETE FROM project_invitations");
                assertEquals("1",query(c,"SELECT count(*) FROM activity_events"));
                for (String denied : new String[]{
                    "CREATE TABLE owlibi.forbidden (id INTEGER)","CREATE TABLE public.forbidden (id INTEGER)",
                    "CREATE SCHEMA forbidden","CREATE DATABASE forbidden","CREATE ROLE forbidden",
                    "ALTER TABLE users ADD COLUMN forbidden INTEGER","DROP TABLE users","TRUNCATE users",
                    "DROP SCHEMA owlibi CASCADE","SET ROLE owlibi_migrator",
                    "UPDATE flyway_schema_history SET checksum=0","DELETE FROM flyway_schema_history",
                    "INSERT INTO flyway_schema_history (installed_rank,description,type,script,installed_by,execution_time,success) VALUES (99,'synthetic','SQL','synthetic','synthetic',0,true)",
                    "UPDATE activity_events SET reason='forbidden'","DELETE FROM activity_events","TRUNCATE activity_events"}) {
                    SQLException error=assertThrows(SQLException.class,() -> sql(c,denied),denied);
                    assertEquals("42501",error.getSQLState(),denied);
                }
                assertEquals("1",query(c,"SELECT count(*) FROM activity_events"));
            }
            try (var c=db.migrator()) {
                sql(c,"CREATE TABLE owned_probe (id INTEGER)");
                assertEquals("owlibi_migrator",query(c,"SELECT tableowner FROM pg_tables WHERE schemaname='owlibi' AND tablename='users'"));
                assertThrows(SQLException.class,() -> sql(c,"CREATE ROLE forbidden"));
                assertThrows(SQLException.class,() -> sql(c,"CREATE DATABASE forbidden"));
            }
            try (var c=db.admin()) {
                assertEquals("0",query(c,"SELECT count(*) FROM pg_roles WHERE rolname IN ('owlibi_runtime','owlibi_migrator') AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication)"));
            }
        }
    }
}
