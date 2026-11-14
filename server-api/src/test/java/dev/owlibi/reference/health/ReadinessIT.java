package dev.owlibi.reference.health;
import static org.junit.jupiter.api.Assertions.*;
import dev.owlibi.reference.support.PostgresFixture;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.time.Duration;
import org.junit.jupiter.api.Test;
class ReadinessIT {
    @Test void healthIsMinimalAndLossOfDatabaseReturnsSafe503ThenStartupFails() throws Exception {
        try(var db=new PostgresFixture()) {
            try(var app=db.startApp()) {
                var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.getWebServer().getPort()+"/api/v1/health")).timeout(Duration.ofSeconds(15)).build();
                var ready=client.send(request,HttpResponse.BodyHandlers.ofString());
                assertEquals(200,ready.statusCode()); assertEquals("{\"status\":\"UP\"}",ready.body());
                assertTrue(ready.headers().firstValue("cache-control").orElse("").contains("no-store"));
                db.stopDatabase();
                var down=client.send(request,HttpResponse.BodyHandlers.ofString());
                assertEquals(503,down.statusCode());
                var mapper=new tools.jackson.databind.json.JsonMapper();
                var body=mapper.readTree(down.body());
                assertEquals("SERVICE_UNAVAILABLE",body.get("code").asText());
                assertEquals("Service temporarily unavailable",body.get("message").asText());
                assertEquals(0,body.get("fieldErrors").size());
                assertEquals(3,body.size());
                assertFalse(down.body().contains("jdbc")); assertFalse(down.body().contains("password"));
            }
            assertThrows(RuntimeException.class,db::startApp);
        }
    }
    @Test void unknownFutureSchemaAndInvalidChecksumFailApplicationStartup() throws Exception {
        try(var db=new PostgresFixture()) {
            db.migrate();
            var dir=Files.createTempDirectory("owlibi-readiness-future-"); var file=dir.resolve("V5__future.sql");
            try {
                Files.writeString(file,"CREATE TABLE future_fixture (id INTEGER);");
                db.flyway("filesystem:"+dir).migrate();
                assertThrows(RuntimeException.class,db::startApp);
            } finally { Files.deleteIfExists(file); Files.deleteIfExists(dir); }
        }
        try(var db=new PostgresFixture()) {
            db.migrate();
            // Synthetic fixture only: emulate a corrupted migration history, never repair production.
            try(var c=db.migrator()) { PostgresFixture.sql(c,"UPDATE flyway_schema_history SET checksum=0 WHERE version='1'"); }
            assertThrows(RuntimeException.class,db::startApp);
        }
    }
}
