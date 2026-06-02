package com.example.audiogateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test"
})
class AudioGatewayApplicationTest {

    @Test
    void contextLoads() {
        // Smoke: app starts without misconfig.
    }
}
