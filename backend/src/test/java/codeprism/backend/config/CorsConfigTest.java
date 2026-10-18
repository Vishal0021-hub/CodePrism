package codeprism.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CorsConfigTest {

    private final CorsConfig corsConfig = new CorsConfig();

    @Test
    void testCorsConfigurationSourceParsesAllowedOrigins() {
        String allowedOrigins = "https://app.codeprism.com, https://preview.codeprism.com ";
        CorsConfigurationSource source = corsConfig.corsConfigurationSource(allowedOrigins);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/user/me");
        CorsConfiguration configuration = source.getCorsConfiguration(request);

        assertNotNull(configuration);
        assertEquals(List.of("https://app.codeprism.com", "https://preview.codeprism.com"), configuration.getAllowedOrigins());
        assertTrue(configuration.getAllowCredentials());
        assertTrue(configuration.getAllowedMethods().containsAll(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS")));
    }
}
