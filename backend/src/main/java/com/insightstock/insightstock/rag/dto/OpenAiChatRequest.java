package com.insightstock.insightstock.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record OpenAiChatRequest(
        String model, List<ChatMessage> messages, Double temperature, @JsonProperty("max_tokens") Integer maxTokens) {
}
