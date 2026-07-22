package com.insightstock.insightstock.embedding.dto;

import java.util.List;

public record OpenAiEmbeddingItem(Integer index, List<Float> embedding) {
}
