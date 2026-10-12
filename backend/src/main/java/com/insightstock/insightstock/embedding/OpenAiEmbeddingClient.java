package com.insightstock.insightstock.embedding;

import com.insightstock.insightstock.embedding.dto.OpenAiEmbeddingItem;
import com.insightstock.insightstock.embedding.dto.OpenAiEmbeddingRequest;
import com.insightstock.insightstock.embedding.dto.OpenAiEmbeddingResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class OpenAiEmbeddingClient implements EmbeddingClient {

    // 같은 API를 부르지만 경로별로 read timeout이 다르다 - RestClient의 타임아웃은 요청
    // 팩토리 단위로 정해지므로 클라이언트를 두 개 둔다.
    private final RestClient batchRestClient;
    private final RestClient singleRestClient;
    private final String model;
    private final int maxRetries;
    private final long initialBackoffMs;

    public OpenAiEmbeddingClient(
            @Value("${embedding.api.base-url}") String baseUrl,
            @Value("${embedding.api.key}") String apiKey,
            @Value("${embedding.api.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${embedding.api.single.read-timeout-ms}") long singleReadTimeoutMs,
            @Value("${embedding.api.batch.read-timeout-ms}") long batchReadTimeoutMs,
            @Value("${embedding.model}") String model,
            @Value("${embedding.rate-limit.max-retries}") int maxRetries,
            @Value("${embedding.rate-limit.initial-backoff-ms}") long initialBackoffMs) {
        this.singleRestClient = buildRestClient(baseUrl, apiKey, connectTimeoutMs, singleReadTimeoutMs);
        this.batchRestClient = buildRestClient(baseUrl, apiKey, connectTimeoutMs, batchReadTimeoutMs);
        this.model = model;
        this.maxRetries = maxRetries;
        this.initialBackoffMs = initialBackoffMs;
    }

    // 타임아웃을 지정하지 않으면 RestClient는 무기한 기다린다 - OpenAI가 응답을 멈추면
    // /search, /answers 화면이 그대로 멈추고 배치는 스케줄러 스레드가 묶인다.
    // OpenAiChatCompletionClient와 같은 방식(detect()로 기존과 같은 HTTP 클라이언트 유지).
    // 타임아웃이 나면 RestClientException -> EmbeddingApiException으로 이어지고, 429와 달리
    // 재시도하지 않는다.
    private RestClient buildRestClient(String baseUrl, String apiKey, long connectTimeoutMs, long readTimeoutMs) {
        ClientHttpRequestFactorySettings timeouts = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(timeouts))
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public EmbeddingBatchResult embed(List<String> texts) {
        return embedWith(batchRestClient, texts);
    }

    @Override
    public float[] embedQuery(String text) {
        return embedWith(singleRestClient, List.of(text)).embeddings().get(0);
    }

    private EmbeddingBatchResult embedWith(RestClient restClient, List<String> texts) {
        OpenAiEmbeddingRequest request = new OpenAiEmbeddingRequest(model, texts);

        OpenAiEmbeddingResponse response = callWithRetry(restClient, request, texts.size());

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
    private OpenAiEmbeddingResponse callWithRetry(
            RestClient restClient, OpenAiEmbeddingRequest request, int textCount) {
        long backoffMs = initialBackoffMs;
        for (int attempt = 0; ; attempt++) {
            try {
                return restClient.post()
                        .uri("/embeddings")
                        .body(request)
                        .retrieve()
                        .body(OpenAiEmbeddingResponse.class);
            } catch (HttpClientErrorException.TooManyRequests e) {
                // OpenAI는 "크레딧 소진"도 429로 응답한다. 이건 기다려도 풀리지 않으므로
                // 재시도하지 않고 일반 장애(EmbeddingApiException -> 502)로 취급한다
                // (OpenAiChatCompletionClient와 같은 규칙).
                if (isQuotaExhausted(e)) {
                    throw new EmbeddingApiException(
                            "OpenAI 임베딩 API 호출 실패 (텍스트 %d건, insufficient_quota - 크레딧 소진)"
                                    .formatted(textCount),
                            e);
                }
                if (attempt >= maxRetries) {
                    throw new EmbeddingRateLimitException(
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

    private boolean isQuotaExhausted(HttpClientErrorException e) {
        return e.getResponseBodyAsString().contains("insufficient_quota");
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
