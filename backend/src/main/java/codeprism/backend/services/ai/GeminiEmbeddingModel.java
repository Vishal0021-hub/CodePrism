package codeprism.backend.services.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;

import lombok.extern.slf4j.Slf4j;

/**
 * Custom EmbeddingModel for Google Gemini's OpenAI-compatible endpoint.
 *
 * Google Gemini returns embedding objects as:
 * { "object": "embedding", "embedding": [...] }
 * without the "index" property. The official OpenAI Java SDK requires "index",
 * which causes OpenAIInvalidDataException: 'index' is not set.
 *
 * This custom model calls Gemini directly, synthesizes the index property,
 * handles 429 rate limit backoff, and seamlessly integrates with PgVectorStore.
 */
@Component
@Primary
@Slf4j
public class GeminiEmbeddingModel implements EmbeddingModel {

    private static final Pattern RETRY_DELAY_PATTERN = Pattern.compile("Please retry in ([0-9.]+)s");
    private static final int DEFAULT_DIMENSIONS = 3072;
    private static final int MAX_RETRIES = 5;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String modelName;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public GeminiEmbeddingModel(
            @Value("${spring.ai.openai.base-url:https://generativelanguage.googleapis.com/v1beta/openai/}") String baseUrl,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.embedding.model:gemini-embedding-001}") String modelName) {
        this.modelName = modelName;
        this.objectMapper = new ObjectMapper();

        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.restClient = RestClient.builder()
                .baseUrl(normalizedBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("Initialized GeminiEmbeddingModel with base-url: {} and model: {}", normalizedBaseUrl, modelName);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        if (instructions == null || instructions.isEmpty()) {
            return new EmbeddingResponse(List.of());
        }

        List<Embedding> results = fetchEmbeddingsWithRetry(instructions);
        return new EmbeddingResponse(results);
    }

    @Override
    public float[] embed(Document document) {
        if (document == null) {
            return new float[0];
        }
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[0];
        }
        List<float[]> list = embed(List.of(text));
        return list.isEmpty() ? new float[0] : list.get(0);
    }

    @Override
    public int dimensions() {
        return DEFAULT_DIMENSIONS;
    }

    private List<Embedding> fetchEmbeddingsWithRetry(List<String> texts) {
        Map<String, Object> body = Map.of(
                "model", modelName,
                "input", texts
        );

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Timer.Sample sample = meterRegistry != null ? Timer.start(meterRegistry) : null;
            try {
                String responseJson = restClient.post()
                        .uri("embeddings")
                        .body(body)
                        .retrieve()
                        .body(String.class);

                return parseEmbeddingResponse(responseJson, texts.size());
            } catch (RestClientResponseException ex) {
                String responseBody = ex.getResponseBodyAsString();
                log.warn("Gemini embedding API error on attempt {}/{}: status={}, body={}",
                        attempt, MAX_RETRIES, ex.getStatusCode(), responseBody);

                boolean isRateLimit = ex.getStatusCode().value() == 429
                        || responseBody.contains("RESOURCE_EXHAUSTED")
                        || responseBody.contains("quota");

                if (isRateLimit && attempt < MAX_RETRIES) {
                    long waitMs = extractRetryDelayMs(responseBody);
                    log.warn("Gemini rate limit encountered. Backing off for {} ms before retry...", waitMs);
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while waiting for rate limit backoff", ie);
                    }
                } else {
                    throw new RuntimeException("Failed to get embeddings from Gemini API: " + responseBody, ex);
                }
            } catch (Exception ex) {
                log.error("Unexpected error fetching embeddings on attempt {}/{}: {}", attempt, MAX_RETRIES, ex.getMessage(), ex);
                if (attempt == MAX_RETRIES) {
                    throw new RuntimeException("Failed to get embeddings: " + ex.getMessage(), ex);
                }
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry backoff", ie);
                }
            } finally {
                if (sample != null && meterRegistry != null) {
                    sample.stop(Timer.builder("codeprism.gemini.embedding.latency")
                            .description("Gemini embedding call latency")
                            .tag("model", modelName)
                            .register(meterRegistry));
                }
            }
        }

        throw new RuntimeException("Exhausted retries attempting to generate embeddings with Gemini");
    }

    private List<Embedding> parseEmbeddingResponse(String responseJson, int expectedCount) {
        List<Embedding> list = new ArrayList<>(expectedCount);
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode dataNode = root.get("data");

            if (dataNode != null && dataNode.isArray()) {
                for (int i = 0; i < dataNode.size(); i++) {
                    JsonNode item = dataNode.get(i);
                    JsonNode embeddingArray = item.get("embedding");
                    if (embeddingArray != null && embeddingArray.isArray()) {
                        float[] vector = new float[embeddingArray.size()];
                        for (int j = 0; j < vector.length; j++) {
                            vector[j] = (float) embeddingArray.get(j).asDouble();
                        }
                        // Explicitly assign synthetic 0-indexed index
                        list.add(new Embedding(vector, i));
                    }
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException("Failed to parse Gemini embedding response: " + ex.getMessage(), ex);
        }

        return list;
    }

    private long extractRetryDelayMs(String body) {
        if (body != null) {
            Matcher matcher = RETRY_DELAY_PATTERN.matcher(body);
            if (matcher.find()) {
                try {
                    double seconds = Double.parseDouble(matcher.group(1));
                    return Math.max(1000L, (long) Math.ceil(seconds * 1000L) + 1500L);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 35_000L;
    }
}
