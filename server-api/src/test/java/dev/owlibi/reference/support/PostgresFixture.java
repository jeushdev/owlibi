package dev.owlibi.reference.support;

import dev.owlibi.reference.OwlibiApplication;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.DockerClientFactory;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;

/** Every fixture owns its container, random credentials, loopback port and anonymous volume. */
public final class PostgresFixture implements AutoCloseable {
    public static final String IMAGE = "postgres:17.11@sha256:d74eeac9a635390a49bc21bd49fccd973de707e2a53a76ac49b552b8712ec46f";
    private final String migrationPassword = UUID.randomUUID().toString();
    private final String runtimePassword = UUID.randomUUID().toString();
    private final PostgreSQLContainer container;
    private int port;
    public PostgresFixture() {
        Path bootstrap = Path.of("../scripts/db/bootstrap-roles.sh").toAbsolutePath().normalize();
        container = new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("owlibi").withUsername("owlibi_admin").withPassword(UUID.randomUUID().toString())
            .withEnv("OWLIBI_MIGRATION_PASSWORD", migrationPassword)
            .withEnv("OWLIBI_RUNTIME_PASSWORD", runtimePassword)
            .withLabel("owlibi.fixture", "a1-integration")
            .withCopyFileToContainer(MountableFile.forHostPath(bootstrap, 0755), "/docker-entrypoint-initdb.d/10-owlibi-roles.sh")
            .withStartupTimeout(Duration.ofSeconds(60))
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                new Ports(new ExposedPort(5432), Ports.Binding.bindIp("127.0.0.1"))));
        // Failure is a test error, never a disabled/skipped test.
        container.start();
        port = container.getMappedPort(5432);
    }
    public String url() { return "jdbc:postgresql://127.0.0.1:" + port + "/owlibi?currentSchema=owlibi&connectTimeout=3&socketTimeout=5"; }
    public Connection runtime() throws SQLException { return DriverManager.getConnection(url(), "owlibi_runtime", runtimePassword); }
    public Connection migrator() throws SQLException { return DriverManager.getConnection(url(), "owlibi_migrator", migrationPassword); }
    public Connection admin() throws SQLException { return DriverManager.getConnection(url(), container.getUsername(), container.getPassword()); }
    public String migrationPassword() { return migrationPassword; }
    public String runtimePassword() { return runtimePassword; }
    /** Enable synthetic TLS only on this owned fixture; no development/provider database access. */
    public void enableTls(Path certificate, Path key) throws Exception {
        container.copyFileToContainer(MountableFile.forHostPath(certificate, 0644), "/tmp/owlibi-server.crt");
        container.copyFileToContainer(MountableFile.forHostPath(key, 0600), "/tmp/owlibi-server.key");
        var permissions = container.execInContainer("sh", "-c", "chown postgres:postgres /tmp/owlibi-server.key && chmod 600 /tmp/owlibi-server.key");
        if (permissions.getExitCode() != 0) throw new IllegalStateException("Owned TLS fixture unavailable");
        try (var c = admin()) {
            sql(c, "ALTER SYSTEM SET ssl_cert_file='/tmp/owlibi-server.crt'");
            sql(c, "ALTER SYSTEM SET ssl_key_file='/tmp/owlibi-server.key'");
            sql(c, "ALTER SYSTEM SET ssl='on'");
            query(c, "SELECT pg_reload_conf()");
        }
        for (int attempt=0; attempt<40; attempt++) {
            try (var c = DriverManager.getConnection(url()+"&sslmode=require", "owlibi_runtime", runtimePassword)) { return; }
            catch (SQLException waiting) { Thread.sleep(100); }
        }
        throw new IllegalStateException("Owned TLS fixture did not become ready");
    }
    public Flyway flyway(String... extraLocations) {
        String[] locations = new String[extraLocations.length + 1]; locations[0] = "classpath:db/migration";
        System.arraycopy(extraLocations, 0, locations, 1, extraLocations.length);
        return Flyway.configure().dataSource(url(), "owlibi_migrator", migrationPassword)
            .defaultSchema("owlibi").schemas("owlibi").createSchemas(false).cleanDisabled(true).locations(locations).load();
    }
    public void migrate() { flyway().migrate(); }
    public ServletWebServerApplicationContext startApp(String... overrides) {
        String[] config = {"--spring.profiles.active=local", "--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url(),
            "--spring.datasource.password=" + runtimePassword, "--spring.flyway.url=" + url(),
            "--spring.flyway.password=" + migrationPassword, "--spring.main.banner-mode=off", "--logging.level.root=ERROR"};
        // Spring combines repeated CLI values with commas; replace fixture defaults explicitly.
        var options = new java.util.LinkedHashMap<String,String>();
        for (String value : config) options.put(value.split("=",2)[0], value);
        for (String value : overrides) options.put(value.split("=",2)[0], value);
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(OwlibiApplication.class).run(options.values().toArray(String[]::new));
    }
    public void restart() throws Exception {
        var docker = DockerClientFactory.instance().client();
        docker.restartContainerCmd(container.getContainerId()).exec();
        int oldPort = port;
        // Docker can allocate a new random host port on restart; refresh the actual mapping.
        port = Integer.parseInt(docker.inspectContainerCmd(container.getContainerId()).exec()
            .getNetworkSettings().getPorts().getBindings().get(new ExposedPort(5432))[0].getHostPortSpec());
        System.out.println("Test-owned PostgreSQL restart loopback port: " + oldPort + " -> " + port);
        for (int attempt = 0; attempt < 60; attempt++) {
            try (Connection c = runtime()) { if (query(c,"SELECT 1").equals("1")) return; }
            catch (SQLException waiting) { Thread.sleep(250); }
        }
        throw new IllegalStateException("PostgreSQL did not recover after restart");
    }
    public void stopDatabase() { container.stop(); }
    public static void sql(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement()) { s.execute(sql); } }
    public static String query(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { if (!r.next()) throw new SQLException("No row"); return r.getString(1); }
    }
    public UUID seed() throws SQLException {
        UUID user = UUID.randomUUID(), project = UUID.randomUUID();
        try (Connection c = runtime()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("INSERT INTO users VALUES (?,?,'Synthetic fixture','not-a-login-hash',CURRENT_TIMESTAMP)")) {
                s.setObject(1,user); s.setString(2,user + "@example.invalid"); s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO projects (id,name,leader_id,template_snapshot,created_at_utc) VALUES (?,'Synthetic fixture',?,'{\"id\":\"blank\",\"version\":1}',CURRENT_TIMESTAMP)")) {
                s.setObject(1,project); s.setObject(2,user); s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO project_memberships VALUES (?,?,'LEADER',CURRENT_TIMESTAMP)")) {
                s.setObject(1,project); s.setObject(2,user); s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO activity_events (id,project_id,actor_id,event_type,occurred_at_utc,change_summary) VALUES (?,?,?,'PROJECT_CREATED',CURRENT_TIMESTAMP,'{}')")) {
                s.setObject(1,UUID.randomUUID()); s.setObject(2,project); s.setObject(3,user); s.executeUpdate();
            }
            c.commit();
        }
        return project;
    }
    @Override public void close() { container.close(); }
}
