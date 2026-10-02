package codeprism.backend.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mockito;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import tools.jackson.databind.json.JsonMapper;

import codeprism.backend.entity.Repository;
import codeprism.backend.repository.RepositoryRepository;
import codeprism.backend.services.indexing.IndexingService;

class WebhookControllerTest {

    private final String secret = "test-secret-12345";
    private RepositoryRepository repositoryRepository;
    private IndexingService indexingService;
    private JsonMapper jsonMapper;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        repositoryRepository = Mockito.mock(RepositoryRepository.class);
        indexingService = Mockito.mock(IndexingService.class);
        jsonMapper = JsonMapper.builder().build();
        controller = new WebhookController(secret, repositoryRepository, indexingService, jsonMapper);
    }

    private String calculateSignature(byte[] payload, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
    }

    @Test
    void shouldRejectInvalidSignature() {
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<?> response = controller.handleGithubWebhook("sha256=invalid", "push", payload);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void shouldProcessValidPushWebhook() throws Exception {
        String payloadJson = """
            {
              "repository": {
                "full_name": "owner/repo"
              },
              "after": "abc1234567890",
              "commits": [
                {
                  "added": ["src/NewFile.java"],
                  "modified": ["src/ExistingFile.java"],
                  "removed": []
                }
              ]
            }
        """;
        byte[] payloadBytes = payloadJson.getBytes(StandardCharsets.UTF_8);
        String signature = calculateSignature(payloadBytes, secret);

        Repository repo = Repository.builder()
                .id(UUID.randomUUID())
                .fullName("owner/repo")
                .autoSync(true)
                .build();

        when(repositoryRepository.findByFullName("owner/repo")).thenReturn(List.of(repo));

        ResponseEntity<?> response = controller.handleGithubWebhook(signature, "push", payloadBytes);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(indexingService).doReindexFiles(eq(repo), any(), eq("abc1234567890"));
    }

    @Test
    void shouldHandlePingEvent() throws Exception {
        byte[] payloadBytes = "{}".getBytes(StandardCharsets.UTF_8);
        String signature = calculateSignature(payloadBytes, secret);

        ResponseEntity<?> response = controller.handleGithubWebhook(signature, "ping", payloadBytes);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
