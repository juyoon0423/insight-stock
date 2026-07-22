package com.insightstock.insightstock.embedding.dto;

import java.util.List;

public record OpenAiEmbeddingResponse(List<OpenAiEmbeddingItem> data, String model, OpenAiUsage usage) {
}
