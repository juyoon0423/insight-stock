package com.insightstock.insightstock.rag;

// OpenAI가 rate limit(429)으로 요청을 거절했고, 재시도까지 전부 소진한 경우.
// "잠깐 막힌 것"과 "실제 장애"를 GlobalExceptionHandler가 상태 코드로 구분해 응답할 수
// 있도록 RagApiException의 하위 타입으로 분리했다.
public class RagRateLimitException extends RagApiException {

    public RagRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
