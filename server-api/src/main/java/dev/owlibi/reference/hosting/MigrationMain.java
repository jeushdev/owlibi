package dev.owlibi.reference.hosting;

import org.flywaydb.core.Flyway;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Separate process, no web server: only this process receives the migration credential. */
public final class MigrationMain {
    private MigrationMain() {}
    public static void main(String[] args) {
        try {
            if (args.length != 0) throw new IllegalArgumentException();
            migrate(System.getenv("OWLIBI_MIGRATION_URL"), System.getenv("OWLIBI_MIGRATION_USER"), System.getenv("OWLIBI_MIGRATION_PASSWORD"));
        } catch (RuntimeException failure) {
            System.err.println("Migration unavailable or schema incompatible");
            System.exit(78);
        }
    }
    public static void migrate(String url, String user, String password) {
        HostedPolicy.jdbc(url);
        if (user == null || user.isBlank() || password == null || password.isBlank()) throw new IllegalArgumentException("Migration credential required");
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.prepareStatement("SELECT pg_has_role(current_user,nspowner,'USAGE') FROM pg_namespace WHERE nspname='owlibi'");
             var rows = statement.executeQuery()) {
            if (!rows.next() || !rows.getBoolean(1)) throw new IllegalArgumentException("Schema owner credential required");
        } catch (SQLException unavailable) { throw new IllegalStateException("Migration database unavailable"); }
        var flyway = Flyway.configure().dataSource(url, user, password).defaultSchema("owlibi").schemas("owlibi")
            .createSchemas(false).cleanDisabled(true).validateOnMigrate(true).locations("classpath:db/migration").load();
        flyway.migrate();
        if (!flyway.validateWithResult().validationSuccessful || flyway.info().current() == null
            || !"4".equals(flyway.info().current().getVersion().toString())) throw new IllegalStateException("Schema incompatible");
    }
}
