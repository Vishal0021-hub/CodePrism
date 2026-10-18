package codeprism.backend.services.github;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import lombok.extern.slf4j.Slf4j;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;

@Service
@Slf4j
public class GithubClient implements GithubApiClient {
    private static final String API_BASE = "https://api.github.com";

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final GitHubRateLimiter rateLimiter;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public GithubClient(RestClient.Builder restClientBuilder, GitHubRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
        this.restClient = restClientBuilder
                .baseUrl(API_BASE)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.USER_AGENT, "CodePrism")
                .build();
    }

    private void recordGithubError(String endpoint, int status) {
        if (meterRegistry != null) {
            Counter.builder("codeprism.github.errors")
                    .tag("endpoint", endpoint)
                    .tag("status", String.valueOf(status))
                    .description("GitHub API errors")
                    .register(meterRegistry)
                    .increment();
        }
    }


    @Override
    public List<Map<String, Object>> listUserRepos(String accessToken) {
        List<Map<String, Object>> all = new ArrayList<>();
        int page = 1;
        while (page <= 10) {
            final int currentPage = page;
            try {
                List<Map<String, Object>> pageRepos = restClient
                        .get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/user/repos")
                                .queryParam("affiliation", "owner,collaborator,organization_member")
                                .queryParam("sort", "updated")
                                .queryParam("per_page", 100)
                                .queryParam("page", currentPage)
                                .build())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .retrieve()
                        .body(LIST_MAP);

                if (pageRepos == null || pageRepos.isEmpty()) {
                    break;
                }
                all.addAll(pageRepos);
                if (pageRepos.size() < 100) {
                    break;
                }
                page++;
                if (rateLimiter != null) {
                    rateLimiter.pause();
                }
            } catch (RestClientResponseException ex) {
                recordGithubError("/user/repos", ex.getStatusCode().value());
                log.error("Failed to list GitHub repos on page {}: status={}, message={}",
                        currentPage, ex.getStatusCode(), ex.getResponseBodyAsString());
                throw ex;
            }
        }
        return all;
    }

    @Override
    public Map<String, Object> getRepoTree(String accessToken, String owner, String repo, String branch) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return restClient
                        .get()
                        .uri("/repos/{owner}/{repo}/git/trees/{branch}?recursive=1", owner, repo, branch)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .retrieve()
                        .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                            log.warn("Repository tree not found for {}/{}: status={}", owner, repo, resp.getStatusCode());
                        })
                        .body(MAP);
            } catch (RestClientResponseException ex) {
                recordGithubError("/repos/{owner}/{repo}/git/trees", ex.getStatusCode().value());
                if (attempt < maxAttempts && (ex.getStatusCode().is5xxServerError() || ex.getStatusCode().value() == 429)) {
                    log.warn("Transient error fetching tree for {}/{} (attempt {}/{}): {}. Retrying...",
                            owner, repo, attempt, maxAttempts, ex.getMessage());
                    pauseBeforeRetry(attempt);
                    continue;
                }
                log.warn("Error fetching repo tree for {}/{}: {}", owner, repo, ex.getMessage());
                return Map.of();
            }
        }
        return Map.of();
    }

    @Override
    public String getFileContent(String accessToken, String owner, String repo, String path) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Map<String, Object> body = restClient
                        .get()
                        .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .retrieve()
                        .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                            log.warn("File not found or inaccessible at {}/{}/{}: status={}", owner, repo, path, resp.getStatusCode());
                        })
                        .body(MAP);

                if (body == null) {
                    return null;
                }
                Object encoding = body.get("encoding");
                Object content = body.get("content");
                if (content == null) {
                    return null;
                }
                if ("base64".equals(String.valueOf(encoding))) {
                    String raw = String.valueOf(content).replaceAll("\\s", "");
                    try {
                        return new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException e) {
                        log.warn("Failed to decode base64 content for file {}/{}/{}: {}", owner, repo, path, e.getMessage());
                        return String.valueOf(content);
                    }
                }
                return String.valueOf(content);
            } catch (RestClientResponseException ex) {
                recordGithubError("/repos/{owner}/{repo}/contents", ex.getStatusCode().value());
                if (attempt < maxAttempts && (ex.getStatusCode().is5xxServerError() || ex.getStatusCode().value() == 429)) {
                    log.warn("Transient error fetching file {}/{}/{} (attempt {}/{}): {}. Retrying...",
                            owner, repo, path, attempt, maxAttempts, ex.getMessage());
                    pauseBeforeRetry(attempt);
                    continue;
                }
                log.warn("Error fetching file content for {}/{}/{}: {}", owner, repo, path, ex.getMessage());
                return null;
            }
        }
        return null;
    }

    private void pauseBeforeRetry(int attempt) {
        try {
            Thread.sleep(1000L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}

