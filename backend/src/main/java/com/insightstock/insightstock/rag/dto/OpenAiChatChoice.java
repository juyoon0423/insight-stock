package com.insightstock.insightstock.rag.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// finishReason: 모델이 답변을 왜 멈췄는가. "stop"이 정상 종료, "length"는 max_tokens에 걸려
// 잘린 것, "content_filter"는 필터에 걸린 것이다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiChatChoice(ChatMessage message, @JsonProperty("finish_reason") String finishReason) {
}
