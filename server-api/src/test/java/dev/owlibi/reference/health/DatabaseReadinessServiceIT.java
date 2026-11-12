package dev.owlibi.reference.health;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseReadinessServiceIT {

    private static final String MIGRATOR_PASSWORD = "test-migrator-password";
    private static final String RUNTIME_PASSWORD = "test-runtime-password";

    @Test
    void reportsReadyWithLatestSchemaUsingRestrictedRuntimeRole() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse(
                        "postgres:17.11@sha256:d74eeac9a635390a49bc21bd49fccd973de707e2a53a76ac49b552b8712ec46f")
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("owlibi")
                .withUsername("owlibi_admin")
                .withPassword("test-admin-password")) {

            postgres.start();

            createRolesAndSchema(postgres);

            Flyway migrator = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), "owlibi_migrator", MIGRATOR_PASSWORD)
                    .locations("classpath:db/migration")
                    .defaultSchema("owlibi")
                    .schemas("owlibi")
                    .createSchemas(false)
                    .cleanDisabled(true)
                    .load();

            migrator.migrate();

            DataSource runtime = new DriverManagerDataSource(
                    postgres.getJdbcUrl(),
                    "owlibi_runtime",
                    RUNTIME_PASSWORD);

            JdbcTemplate jdbc = new JdbcTemplate(runtime);

            StaticListableBeanFactory beans = new StaticListableBeanFactory();

            DatabaseReadinessService service = new DatabaseReadinessService(
                    jdbc,
                    beans.getBeanProvider(Flyway.class),
                    runtime);

            assertDoesNotThrow(service::requireCompatibleSchema);
            assertTrue(service.isReady());

            assertTrue(hasPrivilege(runtime, "owlibi.projects", "SELECT"));
            assertTrue(hasPrivilege(runtime, "owlibi.projects", "INSERT"));
            assertTrue(hasPrivilege(runtime, "owlibi.projects", "UPDATE"));
            assertTrue(hasPrivilege(runtime, "owlibi.projects", "DELETE"));
            assertFalse(hasPrivilege(runtime, "owlibi.projects", "TRUNCATE"));
            assertFalse(hasSchemaPrivilege(runtime, "owlibi", "CREATE"));
        }
    }

    private static void createRolesAndSchema(PostgreSQLContainer postgres)
            throws Exception {

        try (Connection connection = DriverManager.getConnection(
                     postgres.getJdbcUrl(),
                     postgres.getUsername(),
                     postgres.getPassword());
             Statement statement = connection.createStatement()) {

            statement.execute("""
                    CREATE ROLE owlibi_migrator LOGIN PASSWORD 'test-migrator-password'
                    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
                    """);

            statement.execute("""
                    CREATE ROLE owlibi_runtime LOGIN PASSWORD 'test-runtime-password'
                    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
                    """);

            statement.execute("REVOKE ALL ON DATABASE owlibi FROM PUBLIC");

            statement.execute("""
                    GRANT CONNECT ON DATABASE owlibi
                    TO owlibi_migrator, owlibi_runtime
                    """);

            statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");

            statement.execute("""
                    CREATE SCHEMA owlibi AUTHORIZATION owlibi_migrator
                    """);

            statement.execute("""
                    GRANT USAGE ON SCHEMA owlibi TO owlibi_runtime
                    """);

            statement.execute("""
                    ALTER ROLE owlibi_migrator SET search_path = owlibi
                    """);

            statement.execute("""
                    ALTER ROLE owlibi_runtime SET search_path = owlibi
                    """);

            statement.execute("""
                    ALTER DEFAULT PRIVILEGES FOR ROLE owlibi_migrator
                    IN SCHEMA owlibi
                    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES
                    TO owlibi_runtime
                    """);

            statement.execute("""
                    ALTER DEFAULT PRIVILEGES FOR ROLE owlibi_migrator
                    IN SCHEMA owlibi
                    GRANT USAGE, SELECT ON SEQUENCES
                    TO owlibi_runtime
                    """);
        }
    }

    private static boolean hasSchemaPrivilege(
            DataSource dataSource,
            String schema,
            String privilege) {

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Boolean result = jdbc.queryForObject(
                "SELECT has_schema_privilege(current_user, ?, ?)",
                Boolean.class,
                schema,
                privilege);

        return Boolean.TRUE.equals(result);
    }

    private static boolean hasPrivilege(
            DataSource dataSource,
            String object,
            String privilege) {

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Boolean result = jdbc.queryForObject(
                "SELECT has_table_privilege(current_user, ?, ?)",
                Boolean.class,
                object,
                privilege);

        return Boolean.TRUE.equals(result);
    }
}
