package com.insightstock.insightstock.embedding;

import com.insightstock.insightstock.embedding.dto.OpenAiEmbeddingItem;
import com.insightstock.insightstock.embedding.dto.OpenAiEmbeddingRequest;
import com.insightstock.insightstock.embedding.dto.OpenAiEmbeddingResponse;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private final RestClient restClient;
    private final String model;
    private final int maxRetries;
    private final long initialBackoffMs;

    public OpenAiEmbeddingClient(
            @Value("${embedding.api.base-url}") String baseUrl,
            @Value("${embedding.api.key}") String apiKey,
            @Value("${embedding.model}") String model,
            @Value("${embedding.rate-limit.max-retries}") int maxRetries,
            @Value("${embedding.rate-limit.initial-backoff-ms}") long initialBackoffMs) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.model = model;
        this.maxRetries = maxRetries;
        this.initialBackoffMs = initialBackoffMs;
    }

    @Override
    public EmbeddingBatchResult embed(List<String> texts) {
        OpenAiEmbeddingRequest request = new OpenAiEmbeddingRequest(model, texts);

        OpenAiEmbeddingResponse response = callWithRetry(request, texts.size());

        if (response == null || response.data() == null) {
            throw new EmbeddingApiException("OpenAI 임베딩 API 응답이 비어있습니다.", null);
        }

        List<float[]> embeddings = response.data().stream()
                .sorted(Comparator.comparing(OpenAiEmbeddingItem::index))
                .map(item -> toFloatArray(item.embedding()))
                .toList();

        int totalTokens = response.usage() != null ? response.usage().totalTokens() : 0;
        return new EmbeddingBatchResult(embeddings, totalTokens);
    }

    // 429(TPM 한도 초과)만 지수 백오프로 재시도한다 - 400/401 등 그 외 에러는 재시도해도
    // 같은 결과라 바로 실패시킨다. EmbeddingService의 sub-batch 사이 딜레이가 대부분의
    // 429를 사전에 막아주므로, 여기 재시도는 그래도 새는 소수 사례를 위한 안전망이다.
    private OpenAiEmbeddingResponse callWithRetry(OpenAiEmbeddingRequest request, int textCount) {
        long backoffMs = initialBackoffMs;
        for (int attempt = 0; ; attempt++) {
            try {
                return restClient.post()
                        .uri("/embeddings")
                        .body(request)
                        .retrieve()
                        .body(OpenAiEmbeddingResponse.class);
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt >= maxRetries) {
                    throw new EmbeddingApiException(
                            "OpenAI 임베딩 API 호출 실패 (텍스트 %d건, 429 재시도 %d회 소진)"
                                    .formatted(textCount, maxRetries),
                            e);
                }
                log.warn(
                        "429 Too Many Requests (텍스트 {}건) - {}ms 후 재시도 ({}/{})",
                        textCount, backoffMs, attempt + 1, maxRetries);
                sleep(backoffMs);
                backoffMs *= 2;
            } catch (RestClientException e) {
                throw new EmbeddingApiException("OpenAI 임베딩 API 호출 실패 (텍스트 %d건)".formatted(textCount), e);
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private float[] toFloatArray(List<Float> values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }
}
