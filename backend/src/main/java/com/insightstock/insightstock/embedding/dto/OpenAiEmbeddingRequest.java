package com.insightstock.insightstock.embedding.dto;

import java.util.List;

public record OpenAiEmbeddingRequest(String model, List<String> input) {
}
