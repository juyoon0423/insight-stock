package com.insightstock.insightstock.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// AnswerLogEntry를 "ANSWER_LOG {json}" 한 줄로 남긴다. 사람이 읽는 문장형 로그 대신 JSON을
// 쓰는 이유: 나중에 `grep ANSWER_LOG app.log | sed 's/.*ANSWER_LOG //' | jq ...`만으로 거절 비율,
// 범위 밖 인용 비율, 검색/LLM 시간 분포를 뽑을 수 있어야 하기 때문이다. 로그 수집 도구를 붙이게
// 되더라도(관측성은 아직 미착수 - progress.md) 형식을 바꿀 필요가 없다.
//
// 로거 이름을 클래스명이 아닌 "rag.answer"로 고정했다 - 이 로그만 따로 파일로 빼거나 레벨을
// 조정할 때 클래스 이동/이름 변경의 영향을 받지 않게 하기 위함이다.
@Component
public class AnswerAuditLogger {

    static final String PREFIX = "ANSWER_LOG ";

    private static final Logger log = LoggerFactory.getLogger("rag.answer");

    private final ObjectMapper objectMapper;
    private final boolean includeQuery;

    public AnswerAuditLogger(ObjectMapper objectMapper, @Value("${rag.answer-log.include-query}") boolean includeQuery) {
        this.objectMapper = objectMapper;
        this.includeQuery = includeQuery;
    }

    public void log(AnswerLogEntry entry) {
        log.info("{}{}", PREFIX, toJson(entry));
    }

    // 테스트에서 형식을 검증할 수 있도록 직렬화만 분리했다.
    String toJson(AnswerLogEntry entry) {
        Map<String, Object> fields = new LinkedHashMap<>();
        // queryHash/queryLength는 항상 남긴다 - 질의 원문을 끄더라도 "같은 질문이 반복됐는지",
        // "비정상적으로 긴 입력인지"는 알 수 있어야 한다.
        fields.put("queryHash", shortHash(entry.query()));
        fields.put("queryLength", entry.query().length());
        if (includeQuery) {
            fields.put("query", entry.query());
        }
        fields.put("topK", entry.topK());
        fields.put("contextSize", entry.context().size());
        fields.put("context", entry.context());
        fields.put("citedNumbers", entry.citedNumbers());
        fields.put("outOfRangeCitations", entry.outOfRangeCitations());
        fields.put("hasOutOfRangeCitation", !entry.outOfRangeCitations().isEmpty());
        fields.put("refused", entry.refused());
        fields.put("answeredWithoutCitation", entry.answeredWithoutCitation());
        fields.put("llmCalled", entry.llmCalled());
        fields.put("model", entry.model());
        fields.put("promptVersion", entry.promptVersion());
        fields.put("searchMs", entry.searchMs());
        fields.put("llmMs", entry.llmMs());
        fields.put("promptTokens", entry.promptTokens());
        fields.put("completionTokens", entry.completionTokens());
        fields.put("costUsd", entry.costUsd());
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            // 로그를 못 남긴다고 사용자 응답까지 실패시키지 않는다.
            return "{\"error\":\"answer log serialization failed\"}";
        }
    }

    static String shortHash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
