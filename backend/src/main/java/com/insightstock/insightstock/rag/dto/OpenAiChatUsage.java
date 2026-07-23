package com.insightstock.insightstock.rag.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenAiChatUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
}
