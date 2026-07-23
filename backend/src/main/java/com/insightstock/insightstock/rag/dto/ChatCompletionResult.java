package com.insightstock.insightstock.rag.dto;

// promptTokens/completionTokens는 EmbeddingService의 토큰/비용 로깅과 같은 목적으로 남긴다.
public record ChatCompletionResult(String content, int promptTokens, int completionTokens) {
}
