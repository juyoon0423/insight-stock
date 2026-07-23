package com.insightstock.insightstock.rag.dto;

import java.util.List;

public record OpenAiChatResponse(List<OpenAiChatChoice> choices, OpenAiChatUsage usage) {
}
