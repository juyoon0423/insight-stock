package com.insightstock.insightstock.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.insightstock.insightstock.domain.ChunkSource;
import com.insightstock.insightstock.rag.dto.RagPrompt;
import com.insightstock.insightstock.search.dto.RankedChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

// PromptBuilder는 DB/API 의존성이 없는 순수 조립 로직이라 RrfFuserTest와 같은 패턴으로
// 단위 테스트만으로 검증한다(실제 LLM 호출은 비용이 들어 AnswerService 쪽에서 소량만 확인).
class PromptBuilderTest {

    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Test
    void 시스템_프롬프트는_리소스_파일에서_로드되고_환각_방지_규칙을_포함한다() {
        RagPrompt prompt = promptBuilder.build("질문", List.of(chunk(1, "삼성전자", "사업보고서", "재무제표 주석")));

        assertThat(prompt.systemPrompt()).contains("컨텍스트에 없는 정보").contains("찾지 못했습니다");
    }

    @Test
    void 청크_순서대로_번호를_매기고_메타데이터와_섹션명을_포함한다() {
        RankedChunk first = chunk(1, "삼성전자", "사업보고서", "재무제표 주석");
        RankedChunk second = chunk(2, "SK하이닉스", "분기보고서", null);

        RagPrompt prompt = promptBuilder.build("질문", List.of(first, second));

        assertThat(prompt.userPrompt())
                .contains("[1] (삼성전자 / 사업보고서 / 재무제표 주석)")
                .contains("[2] (SK하이닉스 / 분기보고서)")
                .contains("내용 1")
                .contains("내용 2")
                .contains("[질문]\n질문");
        assertThat(prompt.userPrompt()).doesNotContain("[2] (SK하이닉스 / 분기보고서 / ");
    }

    private RankedChunk chunk(long id, String corpName, String reportNm, String sectionTitle) {
        return new RankedChunk(
                id,
                1.0 / id,
                (int) id,
                (int) id,
                "2026072" + id,
                corpName,
                reportNm,
                sectionTitle,
                ChunkSource.SECTION,
                "내용 " + id,
                List.of());
    }
}
