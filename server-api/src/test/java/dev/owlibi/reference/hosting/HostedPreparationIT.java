package dev.owlibi.reference.hosting;

import static org.junit.jupiter.api.Assertions.*;
import static dev.owlibi.reference.support.ApiClient.*;
import dev.owlibi.reference.support.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostedPreparationIT {
    private static final String ORIGIN="https://owlibi.example.invalid";
    private PostgresFixture db;
    private Path directory, certificate, badCertificate;
    private String url;
    @BeforeAll void prepare() throws Exception {
        directory=Files.createTempDirectory("owlibi h0 tls-");
        privatePermissions(directory,true);
        certificate=certificate("server"); badCertificate=certificate("untrusted");
        db=new PostgresFixture(); db.enableTls(certificate,directory.resolve("server.key"));
        url=db.url()+"&sslmode=verify-full&sslrootcert="+encoded(certificate);
        MigrationMain.migrate(url,"owlibi_migrator",db.migrationPassword());
    }
    private Path certificate(String name) throws Exception {
        Path store=directory.resolve(name+".p12"), cert=directory.resolve(name+".crt"), key=directory.resolve(name+".key");
        String password=UUID.randomUUID().toString();char[] secret=password.toCharArray();
        String executable=System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool";
        Path diagnostic=directory.resolve(name+".keytool.log");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),
            "-genkeypair","-alias","synthetic","-keyalg","RSA","-keysize","2048","-sigalg","SHA256withRSA",
            "-dname","CN=127.0.0.1","-validity","1","-ext","SAN=IP:127.0.0.1","-ext","BC:critical=ca:true",
            "-ext","KU:critical=digitalSignature,keyEncipherment,keyCertSign","-storetype","PKCS12","-keystore",store.toString(),
            "-storepass:env","OWLIBI_FIXTURE_KEYSTORE_PASSWORD","-noprompt").redirectErrorStream(true).redirectOutput(diagnostic.toFile());
        builder.environment().put("OWLIBI_FIXTURE_KEYSTORE_PASSWORD",password);
        try {
            var process=builder.start();
            try {
                assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS),"Synthetic JDK certificate generation timed out");
                assertEquals(0,process.exitValue(),"Synthetic JDK certificate generation required: "+Files.readString(diagnostic));
            } finally { if(process.isAlive()) { process.destroyForcibly();assertTrue(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS),"Synthetic keytool cleanup required"); } }
            privatePermissions(store,false);
            var keys=java.security.KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(store)) { keys.load(input,secret); }
            Files.writeString(cert,pem("CERTIFICATE",keys.getCertificate("synthetic").getEncoded()));
            Files.writeString(key,pem("PRIVATE KEY",keys.getKey("synthetic",secret).getEncoded()));privatePermissions(key,false);
            return cert;
        } finally { Arrays.fill(secret,'\0');Files.deleteIfExists(store); }
    }
    private static String pem(String type,byte[] bytes) {
        return "-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(bytes)+"\n-----END "+type+"-----\n";
    }
    private static String encoded(Path path) { return java.net.URLEncoder.encode(path.toString(),java.nio.charset.StandardCharsets.UTF_8); }
    private static void privatePermissions(Path path,boolean directory) throws Exception {
        var posix=Files.getFileAttributeView(path,java.nio.file.attribute.PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null) { posix.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));return; }
        var acl=Files.getFileAttributeView(path,java.nio.file.attribute.AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);assertNotNull(acl,"Owner-only fixture permissions required");
        acl.setAcl(List.of(java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(acl.getOwner()).setPermissions(EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)).build()));
    }
    @AfterAll void cleanup() throws Exception {
        try { if(db!=null) db.close(); }
        finally { if(directory!=null) {
            try(var files=Files.list(directory)) { for(var file:files.toList()) Files.deleteIfExists(file); }
            Files.delete(directory);
        } }
    }
    private String[] hosted(String... additional) {
        var values=new ArrayList<>(List.of("--spring.profiles.active=hosted","--owlibi.hosted.frontend-origin="+ORIGIN,"--spring.datasource.url="+url,"--spring.datasource.username=owlibi_runtime",
            "--spring.flyway.enabled=false","--spring.flyway.url=","--spring.flyway.user=","--spring.flyway.password="));
        values.addAll(List.of(additional));return values.toArray(String[]::new);
    }
    @Test void h0_01_strictOriginsTlsParametersAndOverridesFailClosed() throws Exception {
        assertEquals(ORIGIN,HostedPolicy.origin(ORIGIN));
        for(String invalid:List.of("", "null", "http://owlibi.example.invalid", ORIGIN+"/", "https://*.example.invalid", ORIGIN+"?x=1", "https://user@owlibi.example.invalid", ORIGIN+"#fragment"))
            assertThrows(IllegalArgumentException.class,()->HostedPolicy.origin(invalid));
        assertEquals(url,HostedPolicy.jdbc(url));
        for(String invalid:List.of(db.url(),url.replace("verify-full","require"),url+"&sslmode=require",url+"&sslfactory=org.postgresql.ssl.NonValidatingFactory",url+"&password=secret",url.replace(encoded(certificate),"/missing/ca.crt")))
            assertThrows(IllegalArgumentException.class,()->HostedPolicy.jdbc(invalid));
        // Invalid hosted transport must fail before even initiating a TCP connection.
        try(var listener=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(500);
            String plaintext="jdbc:postgresql://127.0.0.1:"+listener.getLocalPort()+"/owlibi?currentSchema=owlibi&connectTimeout=3&socketTimeout=5";
            assertThrows(RuntimeException.class,()->db.startApp(hosted("--spring.datasource.url="+plaintext)));
            assertThrows(java.net.SocketTimeoutException.class,listener::accept,"No plaintext connection may precede policy validation");
        }
        for(String override:List.of("--server.servlet.session.cookie.secure=false","--server.servlet.session.cookie.domain=example.invalid","--server.forward-headers-strategy=framework","--spring.flyway.enabled=true","--spring.flyway.password=synthetic-forbidden"))
            assertThrows(RuntimeException.class,()->{try(var app=db.startApp(hosted(override))){};});
    }
    @Test void h0_02_verifiedTlsRefusesWrongCaHostnameAndPlaintext() throws Exception {
        try(var c=DriverManager.getConnection(url,"owlibi_runtime",db.runtimePassword())) {
            assertEquals("true",PostgresFixture.query(c,"SELECT ssl::text FROM pg_stat_ssl WHERE pid=pg_backend_pid()"));
        }
        assertThrows(SQLException.class,()->DriverManager.getConnection(url.replace(encoded(certificate),encoded(badCertificate)),"owlibi_runtime",db.runtimePassword()));
        assertThrows(SQLException.class,()->DriverManager.getConnection(url.replace("127.0.0.1","localhost"),"owlibi_runtime",db.runtimePassword()));
        try(var plaintext=new PostgresFixture()) {
            assertThrows(SQLException.class,()->DriverManager.getConnection(plaintext.url()+"&sslmode=verify-full&sslrootcert="+encoded(certificate),"owlibi_runtime",plaintext.runtimePassword()));
        }
    }
    @Test void h0_03_runtimeOnlyValidationAndMigrationRoleDenied() throws Exception {
        try(var app=db.startApp(hosted())) {
            assertEquals("127.0.0.1",app.getEnvironment().getProperty("server.address"),"Host-JVM fixtures must bind only loopback");
            assertEquals(0,app.getBeansOfType(org.flywaydb.core.Flyway.class).size());
            assertEquals("",app.getEnvironment().getProperty("spring.flyway.password"));
            var response=new ApiClient(app.getWebServer().getPort()).send("GET","/health",null);
            assertEquals(200,response.statusCode());assertEquals("{\"status\":\"UP\"}",response.body());
        }
        try(var c=db.runtime()) {
            assertThrows(SQLException.class,()->PostgresFixture.sql(c,"CREATE TABLE owlibi.h0_forbidden (id INTEGER)"));
            assertThrows(SQLException.class,()->PostgresFixture.sql(c,"SET ROLE owlibi_migrator"));
        }
        assertThrows(RuntimeException.class,()->MigrationMain.migrate(url,"owlibi_runtime",db.runtimePassword()));
    }
    @Test void h0_04_badChecksumFutureAndMissingSchemaNeverReady() throws Exception {
        try(var c=db.migrator()) {
            String checksum=PostgresFixture.query(c,"SELECT checksum FROM flyway_schema_history WHERE version='1'");
            try {PostgresFixture.sql(c,"UPDATE flyway_schema_history SET checksum=0 WHERE version='1'");assertThrows(RuntimeException.class,()->db.startApp(hosted()));}
            finally {PostgresFixture.sql(c,"UPDATE flyway_schema_history SET checksum="+checksum+" WHERE version='1'");}
            try {PostgresFixture.sql(c,"UPDATE flyway_schema_history SET version='99' WHERE version='4'");assertThrows(RuntimeException.class,()->db.startApp(hosted()));}
            finally {PostgresFixture.sql(c,"UPDATE flyway_schema_history SET version='4' WHERE version='99'");}
        }
        try(var empty=new PostgresFixture()) {assertThrows(RuntimeException.class,()->empty.startApp("--spring.flyway.enabled=false"));}
    }
    @Test void h0_05_secureCookiesOriginRefusalCsrfEpochLogoutAndSpoofedForwarding() throws Exception {
        try(var app=db.startApp(hosted())) {
            var client=HttpClient.newHttpClient();String base="http://127.0.0.1:"+app.getWebServer().getPort()+"/api/v1";
            var bootstrap=client.send(HttpRequest.newBuilder(URI.create(base+"/auth/sessions")).build(),HttpResponse.BodyHandlers.ofString());
            String cookie=bootstrap.headers().firstValue("set-cookie").orElseThrow();assertCookie(cookie);
            String session=cookie.split(";",2)[0],token=tree(bootstrap).get("token").asText();
            for(String origin:List.of("null","https://preview.example.invalid","https://api.example.invalid")) {
                var denied=request(client,base,"POST","/auth/accounts","{}",session,token,null,origin);
                error(denied,403,"ORIGIN_INVALID");assertFalse(denied.headers().firstValue("access-control-allow-origin").isPresent());
            }
            error(request(client,base,"POST","/auth/accounts","{}",session,token,null,null),403,"ORIGIN_INVALID");
            error(request(client,base,"POST","/auth/accounts","{}",session,null,null,ORIGIN),403,"CSRF_INVALID");
            String email=UUID.randomUUID()+"@example.invalid";
            assertEquals(201,request(client,base,"POST","/auth/accounts",json(Map.of("email",email,"displayName","Synthetic hosted", "password",PASSWORD)),session,token,null,ORIGIN).statusCode());
            var login=request(client,base,"POST","/auth/sessions",json(Map.of("email",email,"password",PASSWORD)),session,token,null,ORIGIN);assertEquals(200,login.statusCode());
            String loginCookie=login.headers().firstValue("set-cookie").orElseThrow();assertCookie(loginCookie);assertNotEquals(session,loginCookie.split(";",2)[0]);session=loginCookie.split(";",2)[0];
            var fresh=request(client,base,"GET","/auth/sessions",null,session,null,null,null);token=tree(fresh).get("token").asText();String epoch=tree(login).get("sessionEpoch").asText();
            error(request(client,base,"POST","/auth/sessions/current/activity","{}",session,token,UUID.randomUUID().toString(),ORIGIN),409,"SESSION_CHANGED");
            var forwarded=client.send(HttpRequest.newBuilder(URI.create(base+"/auth/sessions/current")).header("Cookie",session).header("Forwarded","for=203.0.113.1;host=evil.invalid;proto=https").header("X-Forwarded-Host","evil.invalid").header("X-Forwarded-Proto","https").build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,forwarded.statusCode());assertFalse(forwarded.headers().firstValue("location").isPresent());assertEquals("none",app.getEnvironment().getProperty("server.forward-headers-strategy"));
            var logout=request(client,base,"DELETE","/auth/sessions/current",null,session,token,epoch,ORIGIN);assertEquals(204,logout.statusCode());assertCookie(logout.headers().firstValue("set-cookie").orElseThrow());
            error(request(client,base,"GET","/auth/sessions/current",null,session,null,null,null),401,"UNAUTHENTICATED");
        }
    }
    @Test void h0_06_protectedReadsStillRequireIdentityAndRejectForeignOrigin() throws Exception {
        try(var app=db.startApp(hosted())) {
            var api=new ApiClient(app.getWebServer().getPort());
            error(api.send("GET","/projects/"+UUID.randomUUID()+"/professor-report",null),401,"UNAUTHENTICATED");
            var foreign=api.raw("GET","/auth/sessions",new byte[0],null,null,null,Map.of("Origin","https://evil.invalid"));
            error(foreign,403,"ORIGIN_INVALID");assertTrue(foreign.headers().firstValue("cache-control").orElseThrow().contains("no-store"));
        }
    }
    private static void assertCookie(String value) {
        assertTrue(value.contains("Secure"));assertTrue(value.contains("HttpOnly"));assertTrue(value.contains("SameSite=Lax"));assertTrue(value.contains("Path=/"));assertFalse(value.toLowerCase(Locale.ROOT).contains("domain="));
    }
    private static HttpResponse<String> request(HttpClient client,String base,String method,String path,String body,String cookie,String token,String epoch,String origin) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(15));
        if(cookie!=null)builder.header("Cookie",cookie);if(token!=null)builder.header("X-CSRF-TOKEN",token);if(epoch!=null)builder.header("X-Session-Epoch",epoch);if(origin!=null)builder.header("Origin",origin);
        if(body!=null)builder.header("Content-Type","application/json");
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
