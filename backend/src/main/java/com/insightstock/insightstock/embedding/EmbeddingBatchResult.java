package com.insightstock.insightstock.embedding;

import java.util.List;

// embeddings는 요청한 texts와 같은 순서로 정렬되어 있음이 보장된다(EmbeddingClient 구현체의 책임).
public record EmbeddingBatchResult(List<float[]> embeddings, int totalTokens) {
}
