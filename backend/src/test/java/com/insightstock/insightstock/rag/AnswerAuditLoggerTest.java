package com.insightstock.insightstock.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

// ANSWER_LOG 한 줄의 형식을 고정한다 - 이 로그는 나중에 jq 등으로 분석하는 "데이터"라
// 필드 이름이 조용히 바뀌면 분석 스크립트가 깨진다.
class AnswerAuditLoggerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 분석에_필요한_필드를_JSON_한_줄로_남긴다() throws Exception {
        String json = new AnswerAuditLogger(objectMapper, true).toJson(entry("현대모비스의 1주당 배당금은?"));

        assertThat(json).doesNotContain("\n");
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("query").asText()).isEqualTo("현대모비스의 1주당 배당금은?");
        assertThat(node.get("queryLength").asInt()).isEqualTo(16);
        assertThat(node.get("queryHash").asText()).hasSize(12);
        assertThat(node.get("topK").asInt()).isEqualTo(10);
        assertThat(node.get("contextSize").asInt()).isEqualTo(2);
        JsonNode first = node.get("context").get(0);
        assertThat(first.get("number").asInt()).isEqualTo(1);
        assertThat(first.get("chunkId").asLong()).isEqualTo(101L);
        assertThat(first.get("rceptNo").asText()).isEqualTo("20260724800076");
        assertThat(first.get("keywordRank").asInt()).isEqualTo(3);
        assertThat(first.get("vectorRank").isNull()).isTrue();
        assertThat(node.get("citedNumbers")).hasSize(1);
        assertThat(node.get("outOfRangeCitations").get(0).asInt()).isEqualTo(12);
        assertThat(node.get("hasOutOfRangeCitation").asBoolean()).isTrue();
        assertThat(node.get("refused").asBoolean()).isFalse();
        assertThat(node.get("answeredWithoutCitation").asBoolean()).isFalse();
        assertThat(node.get("llmCalled").asBoolean()).isTrue();
        assertThat(node.get("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(node.get("promptVersion").asText()).isEqualTo("abc123def456");
        assertThat(node.get("searchMs").asLong()).isEqualTo(320);
        assertThat(node.get("llmMs").asLong()).isEqualTo(1850);
        assertThat(node.get("promptTokens").asInt()).isEqualTo(4800);
        assertThat(node.get("completionTokens").asInt()).isEqualTo(60);
        assertThat(node.get("costUsd").asDouble()).isEqualTo(0.000756);
    }

    @Test
    void 질의_원문을_끄면_원문은_빠지고_해시와_길이만_남는다() throws Exception {
        String json = new AnswerAuditLogger(objectMapper, false).toJson(entry("제 주민번호는 900101-1234567 입니다"));

        JsonNode node = objectMapper.readTree(json);
        assertThat(node.has("query")).isFalse();
        assertThat(json).doesNotContain("900101");
        assertThat(node.get("queryHash").asText()).hasSize(12);
        assertThat(node.get("queryLength").asInt()).isPositive();
    }

    @Test
    void 같은_질의는_같은_해시_다른_질의는_다른_해시다() {
        assertThat(AnswerAuditLogger.shortHash("질문 A")).isEqualTo(AnswerAuditLogger.shortHash("질문 A"));
        assertThat(AnswerAuditLogger.shortHash("질문 A")).isNotEqualTo(AnswerAuditLogger.shortHash("질문 B"));
    }

    private AnswerLogEntry entry(String query) {
        return new AnswerLogEntry(
                query,
                10,
                List.of(
                        new AnswerLogEntry.ContextChunk(1, 101L, "20260724800076", 3, null),
                        new AnswerLogEntry.ContextChunk(2, 102L, "20260723800798", null, 1)),
                List.of(1),
                List.of(12),
                false,
                true,
                "gpt-4o-mini",
                "abc123def456",
                320,
                1850,
                4800,
                60,
                0.000756);
    }
}
