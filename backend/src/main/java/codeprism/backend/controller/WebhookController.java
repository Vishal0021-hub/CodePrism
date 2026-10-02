package codeprism.backend.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import codeprism.backend.entity.Repository;
import codeprism.backend.repository.RepositoryRepository;
import codeprism.backend.services.indexing.IndexingService;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/webhooks")
@Slf4j
public class WebhookController {

    private final String clientSecret;
    private final RepositoryRepository repositoryRepository;
    private final IndexingService indexingService;
    private final JsonMapper jsonMapper;

    public WebhookController(
            @Value("${spring.security.oauth2.client.registration.github.client-secret}") String clientSecret,
            RepositoryRepository repositoryRepository,
            IndexingService indexingService,
            JsonMapper jsonMapper) {
        this.clientSecret = clientSecret;
        this.repositoryRepository = repositoryRepository;
        this.indexingService = indexingService;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping("/github")
    public ResponseEntity<?> handleGithubWebhook(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signatureHeader,
            @RequestHeader(value = "X-GitHub-Event", defaultValue = "push") String eventType,
            @RequestBody byte[] payloadBytes) {

        // 1. Verify GitHub webhook signature using existing OAuth app's client secret
        if (!verifySignature(payloadBytes, signatureHeader)) {
            log.warn("Rejected GitHub webhook with invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid signature"));
        }

        // Handle ping event
        if ("ping".equalsIgnoreCase(eventType)) {
            return ResponseEntity.ok(Map.of("message", "pong"));
        }

        try {
            JsonNode root = jsonMapper.readTree(payloadBytes);

            // 2. Extract repository full_name
            String fullName = root.path("repository").path("full_name").asText();
            if (fullName == null || fullName.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Missing repository.full_name"));
            }

            // Extract commit SHA (after or head_commit.id)
            String afterSha = root.path("after").asText(null);
            if (afterSha == null || afterSha.isBlank() || "0000000000000000000000000000000000000000".equals(afterSha)) {
                afterSha = root.path("head_commit").path("id").asText(null);
            }

            // Extract changed file paths from push payload commits array
            Set<String> changedPaths = new LinkedHashSet<>();
            JsonNode commits = root.path("commits");
            if (commits.isArray()) {
                for (JsonNode commit : commits) {
                    collectPaths(commit.path("added"), changedPaths);
                    collectPaths(commit.path("modified"), changedPaths);
                    collectPaths(commit.path("removed"), changedPaths);
                }
            }

            // Also check head_commit if present
            JsonNode headCommit = root.path("head_commit");
            if (!headCommit.isMissingNode() && !headCommit.isNull()) {
                collectPaths(headCommit.path("added"), changedPaths);
                collectPaths(headCommit.path("modified"), changedPaths);
                collectPaths(headCommit.path("removed"), changedPaths);
            }

            // 3. Look up Repository by full_name
            List<Repository> repos = repositoryRepository.findByFullName(fullName);
            if (repos.isEmpty()) {
                log.info("Webhook received for untracked repository: {}", fullName);
                return ResponseEntity.ok(Map.of(
                        "status", "ignored",
                        "reason", "Repository not tracked in CodePrism",
                        "fullName", fullName));
            }

            log.info("Received GitHub push webhook for {} with {} changed files across {} matching repositories",
                    fullName, changedPaths.size(), repos.size());

            int updatedRepos = 0;
            for (Repository repo : repos) {
                if (repo.isAutoSync()) {
                    indexingService.doReindexFiles(repo, new ArrayList<>(changedPaths), afterSha);
                    updatedRepos++;
                } else {
                    log.info("Auto-sync disabled for repo {} (id: {}), skipping reindex", fullName, repo.getId());
                }
            }

            return ResponseEntity.ok(Map.of(
                    "status", "processed",
                    "fullName", fullName,
                    "changedFilesCount", changedPaths.size(),
                    "updatedRepositoriesCount", updatedRepos,
                    "lastIndexedCommitSha", afterSha != null ? afterSha : ""));

        } catch (Exception ex) {
            log.error("Failed to process GitHub webhook payload: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to process webhook: " + ex.getMessage()));
        }
    }

    private boolean verifySignature(byte[] payloadBytes, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(
                    clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hmacBytes = mac.doFinal(payloadBytes);
            String expected = "sha256=" + HexFormat.of().formatHex(hmacBytes);

            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            log.warn("Error calculating HMAC signature: {}", ex.getMessage());
            return false;
        }
    }

    private void collectPaths(JsonNode arrayNode, Set<String> target) {
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                String path = item.asText();
                if (path != null && !path.isBlank()) {
                    target.add(path);
                }
            }
        }
    }
}
