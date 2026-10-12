package com.insightstock.insightstock.rag;

import com.insightstock.insightstock.dart.DartUrls;
import com.insightstock.insightstock.rag.dto.AnswerResponse;
import com.insightstock.insightstock.rag.dto.ChatCompletionResult;
import com.insightstock.insightstock.rag.dto.RagPrompt;
import com.insightstock.insightstock.rag.dto.SourceRef;
import com.insightstock.insightstock.search.HybridSearchService;
import com.insightstock.insightstock.search.dto.RankedChunk;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// HybridSearchService(검색) -> PromptBuilder(조립) -> ChatCompletionClient(LLM) ->
// CitationParser(출처 매핑)를 엮는 조립점. 각 단계는 이미 독립적으로 검증돼 있어
// 여기서는 "연결"과 "실제로 인용된 출처만 노출"하는 후처리에만 집중한다.
@Service
public class AnswerService {

    private static final Logger log = LoggerFactory.getLogger(AnswerService.class);

    // rag.chat.model(gpt-4o-mini) 기준 단가. EmbeddingService의 PRICE_PER_MILLION_TOKENS와
    // 같은 패턴 - 모델을 바꾸면 이 값도 함께 갱신해야 한다.
    private static final double PRICE_PER_MILLION_INPUT_TOKENS = 0.15;
    private static final double PRICE_PER_MILLION_OUTPUT_TOKENS = 0.60;

    private static final String NO_ANSWER_MESSAGE = "제공된 공시 자료에서는 이 질문에 답할 수 있는 내용을 찾지 못했습니다.";

    private final HybridSearchService hybridSearchService;
    private final PromptBuilder promptBuilder;
    private final ChatCompletionClient chatCompletionClient;
    private final CitationParser citationParser;
    private final int defaultTopK;

    public AnswerService(
            HybridSearchService hybridSearchService,
            PromptBuilder promptBuilder,
            ChatCompletionClient chatCompletionClient,
            CitationParser citationParser,
            @Value("${hybrid-search.top-k}") int defaultTopK) {
        this.hybridSearchService = hybridSearchService;
        this.promptBuilder = promptBuilder;
        this.chatCompletionClient = chatCompletionClient;
        this.citationParser = citationParser;
        this.defaultTopK = defaultTopK;
    }

    public AnswerResponse answer(String query) {
        return answer(query, defaultTopK);
    }

    public AnswerResponse answer(String query, int topK) {
        List<RankedChunk> chunks = hybridSearchService.search(query, topK);
        if (chunks.isEmpty()) {
            log.info("검색 결과 없음 - LLM 호출 없이 응답: query=\"{}\"", query);
            return new AnswerResponse(query, NO_ANSWER_MESSAGE, List.of(), List.of());
        }

        RagPrompt prompt = promptBuilder.build(query, chunks);
        ChatCompletionResult result = chatCompletionClient.complete(prompt);

        double cost = result.promptTokens() / 1_000_000.0 * PRICE_PER_MILLION_INPUT_TOKENS
                + result.completionTokens() / 1_000_000.0 * PRICE_PER_MILLION_OUTPUT_TOKENS;
        log.info(
                "답변 생성 완료: query=\"{}\", 컨텍스트 청크 {}건, 입력 토큰 {}개, 출력 토큰 {}개, 예상 비용 ${}",
                query, chunks.size(), result.promptTokens(), result.completionTokens(), "%.6f".formatted(cost));

        // 모델이 쓴 [N] 중 컨텍스트 범위 안의 번호만 출처로 인정한다. 범위 밖 번호는 조용히
        // 버리지 않고 로그로 남기며, 인정된 번호 목록(citedNumbers)을 응답에 실어 프론트가
        // 같은 판정을 그대로 쓰게 한다 - 예전엔 프론트가 범위 밖 번호까지 "등장 순서"로 다시
        // 번호를 매겨, 지어낸 [12]가 엉뚱한 출처 배지로 연결되는 버그가 있었다.
        List<Integer> allCitedNumbers = citationParser.extractCitedNumbers(result.content());
        List<Integer> citedNumbers = allCitedNumbers.stream()
                .filter(number -> number >= 1 && number <= chunks.size())
                .toList();
        if (citedNumbers.size() < allCitedNumbers.size()) {
            List<Integer> outOfRange = allCitedNumbers.stream()
                    .filter(number -> !citedNumbers.contains(number))
                    .toList();
            log.warn(
                    "컨텍스트 범위(1~{}) 밖의 인용 번호를 출처에서 제외: {} (query=\"{}\")",
                    chunks.size(), outOfRange, query);
        }

        List<SourceRef> sources = citedNumbers.stream()
                .map(number -> toSourceRef(chunks.get(number - 1)))
                .toList();

        return new AnswerResponse(query, result.content(), sources, citedNumbers);
    }

    private SourceRef toSourceRef(RankedChunk chunk) {
        return new SourceRef(
                chunk.rceptNo(),
                chunk.corpName(),
                chunk.reportNm(),
                chunk.sectionTitle(),
                DartUrls.viewerUrl(chunk.rceptNo()));
    }
}
