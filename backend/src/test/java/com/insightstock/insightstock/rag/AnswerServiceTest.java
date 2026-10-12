package com.insightstock.insightstock.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightstock.insightstock.domain.ChunkSource;
import com.insightstock.insightstock.rag.dto.AnswerResponse;
import com.insightstock.insightstock.rag.dto.ChatCompletionResult;
import com.insightstock.insightstock.rag.dto.SourceRef;
import com.insightstock.insightstock.search.HybridSearchService;
import com.insightstock.insightstock.search.dto.RankedChunk;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

// 검색과 LLM을 대역으로 바꿔 "모델 답변의 [N] -> 출처" 후처리만 검증한다(비용 0).
// 실제 LLM 호출 검증은 AnswerServiceManualIT(@Tag("manual"))가 따로 맡는다.
class AnswerServiceTest {

    private final HybridSearchService hybridSearchService = mock(HybridSearchService.class);
    private final ChatCompletionClient chatCompletionClient = mock(ChatCompletionClient.class);
    private final AnswerService answerService = new AnswerService(
            hybridSearchService, new PromptBuilder(), chatCompletionClient, new CitationParser(), 10);

    @Test
    void 인용된_번호만_등장_순서대로_출처와_citedNumbers에_담는다() {
        givenChunks(3);
        givenModelAnswer("배당이 결정됐습니다[3]. 기준일은 12월 31일입니다[1][3].");

        AnswerResponse response = answerService.answer("질문");

        assertThat(response.citedNumbers()).containsExactly(3, 1);
        assertThat(response.sources()).extracting(SourceRef::rceptNo).containsExactly("rcept-3", "rcept-1");
    }

    @Test
    void 컨텍스트_범위_밖_번호는_출처에서_빼고_citedNumbers에도_넣지_않는다() {
        // 컨텍스트는 3건인데 모델이 [12]와 [0]을 지어냈고, 본문에 [2024] 같은 표기도 섞였다.
        givenChunks(3);
        givenModelAnswer("매출이 늘었습니다[12]. [2024]년 기준 부채비율은 낮아졌습니다[2][0].");

        AnswerResponse response = answerService.answer("질문");

        assertThat(response.citedNumbers()).containsExactly(2);
        assertThat(response.sources()).extracting(SourceRef::rceptNo).containsExactly("rcept-2");
        // 본문은 손대지 않는다 - 어떤 번호가 유효한지는 citedNumbers로만 전달한다.
        assertThat(response.answer()).contains("[12]").contains("[2024]");
    }

    @Test
    void 유효한_인용이_하나도_없으면_출처와_citedNumbers가_모두_비어있다() {
        givenChunks(2);
        givenModelAnswer("근거 없이 쓴 문장입니다[7].");

        AnswerResponse response = answerService.answer("질문");

        assertThat(response.citedNumbers()).isEmpty();
        assertThat(response.sources()).isEmpty();
    }

    @Test
    void 검색_결과가_없으면_LLM을_호출하지_않고_빈_citedNumbers로_응답한다() {
        givenChunks(0);

        AnswerResponse response = answerService.answer("질문");

        assertThat(response.citedNumbers()).isEmpty();
        assertThat(response.sources()).isEmpty();
        verify(chatCompletionClient, never()).complete(any());
    }

    private void givenChunks(int count) {
        List<RankedChunk> chunks = IntStream.rangeClosed(1, count)
                .mapToObj(this::chunk)
                .toList();
        when(hybridSearchService.search("질문", 10)).thenReturn(chunks);
    }

    private void givenModelAnswer(String content) {
        when(chatCompletionClient.complete(any())).thenReturn(new ChatCompletionResult(content, 100, 20));
    }

    private RankedChunk chunk(int number) {
        return new RankedChunk(
                (long) number,
                1.0 / number,
                number,
                number,
                "rcept-" + number,
                "회사" + number,
                "보고서" + number,
                null,
                ChunkSource.SECTION,
                "내용 " + number,
                List.of());
    }
}
