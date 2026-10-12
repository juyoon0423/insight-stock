package com.insightstock.insightstock.embedding;

// OpenAI가 rate limit(429)으로 임베딩 요청을 거절했고, 재시도까지 전부 소진한 경우.
// RagRateLimitException과 같은 이유로 EmbeddingApiException의 하위 타입으로 분리했다 -
// GlobalExceptionHandler가 "잠깐 막힌 것"(429)과 "실제 장애"(502)를 상태 코드로 구분한다.
// 하위 타입이라 배치(EmbeddingService)의 기존 catch (EmbeddingApiException)에는 그대로 잡힌다.
public class EmbeddingRateLimitException extends EmbeddingApiException {

    public EmbeddingRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
