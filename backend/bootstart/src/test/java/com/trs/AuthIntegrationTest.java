package com.trs;

import java.net.*;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.junit.jupiter.api.Assertions.*;

/** 登录会话与安全边界集成测试。 @author agent */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest {
    @LocalServerPort int port;
    @Test
    void loginAndLogout() throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String base = "http://localhost:" + port;
        assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/me")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        var csrf = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).build(), HttpResponse.BodyHandlers.ofString()).body();
        String token = csrf.split("\"token\":\"")[1].split("\"")[0];
        var bad = HttpRequest.newBuilder(URI.create(base + "/api/auth/login")).header("Content-Type", "application/x-www-form-urlencoded").header("X-CSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.ofString("username=admin&password=wrong")).build();
        assertEquals(401, client.send(bad, HttpResponse.BodyHandlers.ofString()).statusCode());
        var login = HttpRequest.newBuilder(URI.create(base + "/api/auth/login")).header("Content-Type", "application/x-www-form-urlencoded").header("X-CSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.ofString("username=admin&password=123456")).build();
        assertEquals(200, client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertTrue(client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/me")).build(), HttpResponse.BodyHandlers.ofString()).body().contains("admin"));
        assertEquals(403, client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/logout")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        csrf = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).build(), HttpResponse.BodyHandlers.ofString()).body();
        token = csrf.split("\"token\":\"")[1].split("\"")[0];
        assertEquals(204, client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/logout")).header("X-CSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/me")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
