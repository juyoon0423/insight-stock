package com.insightstock.insightstock.rag;

import com.insightstock.insightstock.rag.dto.RagPrompt;
import com.insightstock.insightstock.search.dto.RankedChunk;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

// HybridSearchService가 반환한 RankedChunk 리스트를 LLM에 보낼 프롬프트로 조립한다.
// 컨텍스트 번호([1], [2]...)는 RankedChunk 리스트의 순서(RRF 순위) 그대로 매기고,
// AnswerService가 같은 리스트를 들고 있으면 모델이 인용한 번호를 그대로 실제
// RankedChunk(-> 출처)에 매핑할 수 있다.
@Component
public class PromptBuilder {

    private static final String SYSTEM_PROMPT_PATH = "prompts/rag-system-prompt.txt";

    private final String systemPrompt;

    public PromptBuilder() {
        this.systemPrompt = loadSystemPrompt();
    }

    public RagPrompt build(String query, List<RankedChunk> chunks) {
        String userPrompt = "[컨텍스트]\n" + buildContextBlock(chunks) + "\n[질문]\n" + query;
        return new RagPrompt(systemPrompt, userPrompt);
    }

    private String buildContextBlock(List<RankedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RankedChunk chunk = chunks.get(i);
            sb.append('[').append(i + 1).append("] (")
                    .append(chunk.corpName()).append(" / ").append(chunk.reportNm());
            if (chunk.sectionTitle() != null && !chunk.sectionTitle().isBlank()) {
                sb.append(" / ").append(chunk.sectionTitle());
            }
            sb.append(")\n").append(chunk.content()).append("\n\n");
        }
        return sb.toString();
    }

    private String loadSystemPrompt() {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource(SYSTEM_PROMPT_PATH).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("RAG system prompt 로딩 실패: " + SYSTEM_PROMPT_PATH, e);
        }
    }
}
