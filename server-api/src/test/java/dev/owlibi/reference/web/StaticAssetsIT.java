package dev.owlibi.reference.web;
import static org.junit.jupiter.api.Assertions.*;
import dev.owlibi.reference.support.PostgresFixture;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
class StaticAssetsIT {
    @Test void executableJarContainsExactlyPublicAssetsAndSameOriginServiceServesThem() throws Exception {
        try(var jar=new JarFile("target/server-api-0.1.0-SNAPSHOT.jar")) {
            for(String name:new String[]{"index.html","main.js","styles.css","api.js","session.js","model.js","ui.js","brand-logo.png"}) {
                var entry=jar.getJarEntry("BOOT-INF/classes/static/"+name); assertNotNull(entry,name);
                try(var in=jar.getInputStream(entry)) {
                    assertArrayEquals(Files.readAllBytes(Path.of("../web-client/src/"+name)),in.readAllBytes());
                }
            }
            var files=jar.stream().filter(e->e.getName().startsWith("BOOT-INF/classes/static/")&&!e.isDirectory()).toList();
            assertEquals(8,files.size());
            assertNull(jar.getJarEntry("BOOT-INF/classes/.env"));
            var catalogue=jar.getJarEntry("BOOT-INF/classes/templates/catalogue.json"); assertNotNull(catalogue);
            try(var in=jar.getInputStream(catalogue)) { assertArrayEquals(Files.readAllBytes(Path.of("src/main/resources/templates/catalogue.json")),in.readAllBytes()); }
            assertNull(jar.getJarEntry("BOOT-INF/classes/static/templates/catalogue.json"));
            assertNull(jar.getJarEntry("BOOT-INF/classes/dev/owlibi/reference/projects/ProjectSetupIT.class"));
        }
        try(var db=new PostgresFixture();var app=db.startApp()) {
            var client=HttpClient.newHttpClient(); String origin="http://127.0.0.1:"+app.getWebServer().getPort();
            for(String name:new String[]{"index.html","main.js","styles.css","api.js","session.js","model.js","ui.js","brand-logo.png"}) {
                var response=client.send(HttpRequest.newBuilder(URI.create(origin+"/"+name)).build(),HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200,response.statusCode(),name);
                assertArrayEquals(Files.readAllBytes(Path.of("../web-client/src/"+name)),response.body());
                if(name.equals("brand-logo.png")) assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("image/png"));
                assertTrue(response.headers().firstValue("content-security-policy").orElse("").contains("default-src 'self'"));
                assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));
            }
            var denied=client.send(HttpRequest.newBuilder(URI.create(origin+"/api/v1/projects")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(401,denied.statusCode());
            assertTrue(denied.body().contains("UNAUTHENTICATED"));
            assertFalse(denied.body().contains("Synthetic"));
            for(String privatePath:new String[]{"/templates/catalogue.json","/api/v1/templates"}) {
                var privateResponse=client.send(HttpRequest.newBuilder(URI.create(origin+privatePath)).build(),HttpResponse.BodyHandlers.ofString());
                assertTrue(privateResponse.statusCode()==401 || privateResponse.statusCode()==403);
                assertFalse(privateResponse.body().contains("Clarify requirements"));
            }
        }
    }
}
