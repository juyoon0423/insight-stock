package com.insightstock.insightstock.rag;

import com.insightstock.insightstock.rag.dto.ChatCompletionResult;
import com.insightstock.insightstock.rag.dto.ChatMessage;
import com.insightstock.insightstock.rag.dto.OpenAiChatChoice;
import com.insightstock.insightstock.rag.dto.OpenAiChatRequest;
import com.insightstock.insightstock.rag.dto.OpenAiChatResponse;
import com.insightstock.insightstock.rag.dto.RagPrompt;
import java.time.Duration;
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
public class OpenAiChatCompletionClient implements ChatCompletionClient {

    private final RestClient restClient;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int maxRetries;
    private final long initialBackoffMs;

    public OpenAiChatCompletionClient(
            @Value("${rag.chat.api.base-url}") String baseUrl,
            @Value("${rag.chat.api.key}") String apiKey,
            @Value("${rag.chat.api.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${rag.chat.api.read-timeout-ms}") long readTimeoutMs,
            @Value("${rag.chat.model}") String model,
            @Value("${rag.chat.temperature}") double temperature,
            @Value("${rag.chat.max-tokens}") int maxTokens,
            @Value("${rag.chat.rate-limit.max-retries}") int maxRetries,
            @Value("${rag.chat.rate-limit.initial-backoff-ms}") long initialBackoffMs) {
        // 타임아웃을 지정하지 않으면 RestClient는 무기한 기다린다 - OpenAI가 느려지거나 응답을
        // 멈추면 사용자 화면이 그대로 멈춘다. detect()는 타임아웃을 넣기 전과 같은 HTTP
        // 클라이언트(현재 클래스패스 기준 JDK HttpClient)를 그대로 고르므로 동작은 타임아웃
        // 외에 달라지지 않는다. 타임아웃이 나면 RestClientException(ResourceAccessException)
        // -> RagApiException -> 502/LLM_UNAVAILABLE로 이어지고, 재시도는 하지 않는다.
        ClientHttpRequestFactorySettings timeouts = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(timeouts))
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.maxRetries = maxRetries;
        this.initialBackoffMs = initialBackoffMs;
    }

    @Override
    public ChatCompletionResult complete(RagPrompt prompt) {
        OpenAiChatRequest request = new OpenAiChatRequest(
                model,
                List.of(ChatMessage.system(prompt.systemPrompt()), ChatMessage.user(prompt.userPrompt())),
                temperature,
                maxTokens);

        OpenAiChatResponse response = callWithRetry(request);

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new RagApiException("OpenAI 채팅 완성 API 응답이 비어있습니다.", null);
        }

        // 여기서부터는 "HTTP 200인데 쓸 수 없는 응답"을 걸러낸다. 그대로 넘기면 뒤에서 null을
        // 다루다 NullPointerException이 나 원인을 알 수 없는 500이 된다 - LLM 쪽 문제이므로
        // RagApiException(-> 502/LLM_UNAVAILABLE)으로 분명하게 실패시킨다.
        OpenAiChatChoice choice = response.choices().get(0);
        String content = choice.message() == null ? null : choice.message().content();
        if ("content_filter".equals(choice.finishReason())) {
            throw new RagApiException("OpenAI 채팅 완성 API가 콘텐츠 필터로 답변을 중단했습니다.", null);
        }
        if (content == null || content.isBlank()) {
            throw new RagApiException(
                    "OpenAI 채팅 완성 API 응답에 답변 본문이 없습니다 (finish_reason=%s)".formatted(choice.finishReason()),
                    null);
        }
        // max_tokens에 걸려 잘린 답변은 버리지 않고 내보낸다(앞부분은 유효한 답변이다). 다만
        // 마지막 문장과 그 인용이 잘렸을 수 있으므로 로그로 남겨 상한을 조정할 근거로 쓴다.
        if ("length".equals(choice.finishReason())) {
            log.warn("채팅 완성 답변이 max_tokens({})에 걸려 잘렸습니다.", maxTokens);
        }

        int promptTokens = usagePromptTokens(response);
        int completionTokens = usageCompletionTokens(response);

        return new ChatCompletionResult(content, promptTokens, completionTokens);
    }

    // 429(rate limit)만 지수 백오프로 재시도한다 - OpenAiEmbeddingClient와 같은 패턴.
    // 400/401/5xx 등 그 외 에러는 재시도 없이 바로 실패시킨다. 임베딩(배치)과 달리 이 호출은
    // 사용자가 화면 앞에서 기다리는 요청이라, 재시도 횟수와 대기 시간을 훨씬 짧게 잡는다
    // (application.yml의 rag.chat.rate-limit 참고).
    private OpenAiChatResponse callWithRetry(OpenAiChatRequest request) {
        long backoffMs = initialBackoffMs;
        for (int attempt = 0; ; attempt++) {
            try {
                return restClient.post()
                        .uri("/chat/completions")
                        .body(request)
                        .retrieve()
                        .body(OpenAiChatResponse.class);
            } catch (HttpClientErrorException.TooManyRequests e) {
                // OpenAI는 "크레딧 소진"도 429로 응답한다. 이건 기다려도 풀리지 않으므로
                // 재시도하지 않고 일반 장애(RagApiException -> 502)로 취급한다.
                if (isQuotaExhausted(e)) {
                    throw new RagApiException("OpenAI 채팅 완성 API 호출 실패 (insufficient_quota - 크레딧 소진)", e);
                }
                if (attempt >= maxRetries) {
                    throw new RagRateLimitException(
                            "OpenAI 채팅 완성 API 호출 실패 (429 재시도 %d회 소진)".formatted(maxRetries), e);
                }
                log.warn("429 Too Many Requests (채팅 완성) - {}ms 후 재시도 ({}/{})", backoffMs, attempt + 1, maxRetries);
                sleep(backoffMs);
                backoffMs *= 2;
            } catch (RestClientException e) {
                throw new RagApiException("OpenAI 채팅 완성 API 호출 실패", e);
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

    private int usagePromptTokens(OpenAiChatResponse response) {
        return response.usage() != null && response.usage().promptTokens() != null
                ? response.usage().promptTokens()
                : 0;
    }

    private int usageCompletionTokens(OpenAiChatResponse response) {
        return response.usage() != null && response.usage().completionTokens() != null
                ? response.usage().completionTokens()
                : 0;
    }
}
