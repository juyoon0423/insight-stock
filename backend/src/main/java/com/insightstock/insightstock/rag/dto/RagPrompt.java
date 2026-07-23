package com.insightstock.insightstock.rag.dto;

// PromptBuilder가 조립한 최종 프롬프트. system/user 메시지를 분리해 ChatCompletion API의
// messages 배열(role=system, role=user)에 그대로 매핑할 수 있게 한다.
public record RagPrompt(String systemPrompt, String userPrompt) {
}
