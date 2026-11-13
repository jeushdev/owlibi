package dev.owlibi.reference.support;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP, independent cookie jar; credentials stay synthetic and out of assertions/logs. */
public final class ApiClient {
    public static final String PASSWORD=" synthetic-password-42 ";
    public static final JsonMapper JSON=JsonMapper.shared();
    public final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    public final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(3)).build();
    public final String origin;
    public String csrf,epoch;
    public UUID id;
    public ApiClient(int port) { origin="http://127.0.0.1:"+port; }
    public HttpResponse<String> send(String method,String path,String body) throws Exception {
        return raw(method,path,body==null?new byte[0]:body.getBytes(java.nio.charset.StandardCharsets.UTF_8),"application/json",csrf,epoch,Map.of());
    }
    public HttpResponse<String> raw(String method,String path,byte[] body,String type,String token,String sessionEpoch,Map<String,String> extra) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(origin+"/api/v1"+path)).timeout(Duration.ofSeconds(15));
        if (type!=null) builder.header("Content-Type",type);
        if (token!=null) builder.header("X-CSRF-TOKEN",token);
        if (sessionEpoch!=null) builder.header("X-Session-Epoch",sessionEpoch);
        extra.forEach(builder::header);
        return client.send(builder.method(method,HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    public HttpResponse<String> bootstrap() throws Exception {
        var response=send("GET","/auth/sessions",null); assertEquals(200,response.statusCode());
        var json=tree(response); csrf=json.get("token").asText(); assertEquals("X-CSRF-TOKEN",json.get("headerName").asText()); return response;
    }
    public HttpResponse<String> register(String email,String name,String password) throws Exception {
        return send("POST","/auth/accounts",json(Map.of("email",email,"displayName",name,"password",password)));
    }
    public HttpResponse<String> login(String email,String password) throws Exception {
        var response=send("POST","/auth/sessions",json(Map.of("email",email,"password",password)));
        if (response.statusCode()==200) { var body=tree(response); epoch=body.get("sessionEpoch").asText(); id=UUID.fromString(body.get("id").asText()); }
        return response;
    }
    public void account(String email) throws Exception {
        bootstrap(); assertEquals(201,register(email,"Synthetic account",PASSWORD).statusCode());
        assertEquals(200,login(email,PASSWORD).statusCode()); bootstrap();
    }
    public UUID project(String name) throws Exception {
        var response=send("POST","/projects",json(Map.of("name",name))); assertEquals(201,response.statusCode()); return UUID.fromString(tree(response).get("id").asText());
    }
    public String invitation(UUID project) throws Exception {
        var response=send("POST","/projects/"+project+"/invitations",null); assertEquals(201,response.statusCode()); return tree(response).get("token").asText();
    }
    public HttpResponse<String> accept(String token) throws Exception { return send("POST","/invitations/accept",json(Map.of("token",token))); }
    public String sessionCookie() { return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue(); }
    public static String json(Object value) { return JSON.writeValueAsString(value); }
    public static JsonNode tree(HttpResponse<String> response) { return JSON.readTree(response.body()); }
    public static void error(HttpResponse<String> response,int status,String code) {
        assertEquals(status,response.statusCode(),"HTTP status"); var body=tree(response);
        assertEquals(Set.of("code","message","fieldErrors"),body.propertyNames()); assertEquals(code,body.get("code").asText()); assertTrue(body.get("fieldErrors").isObject());
        assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));
        for (String secret:List.of("password_hash","jdbc:","PSQLException","stackTrace",PASSWORD)) assertFalse(response.body().contains(secret));
    }
}
