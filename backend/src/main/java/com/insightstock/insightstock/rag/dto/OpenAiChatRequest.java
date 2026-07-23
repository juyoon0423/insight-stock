package com.insightstock.insightstock.rag.dto;

import java.util.List;

public record OpenAiChatRequest(String model, List<ChatMessage> messages, Double temperature) {
}
