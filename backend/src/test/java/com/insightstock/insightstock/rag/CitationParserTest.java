package com.insightstock.insightstock.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CitationParserTest {

    private final CitationParser citationParser = new CitationParser();

    @Test
    void 등장_순서대로_중복없이_인용번호를_추출한다() {
        String answer = "매출은 증가했습니다[2][1]. 부채비율도 개선됐습니다[1].";

        assertThat(citationParser.extractCitedNumbers(answer)).containsExactly(2, 1);
    }

    @Test
    void 인용이_없으면_빈_리스트를_반환한다() {
        String answer = "제공된 공시 자료에서는 이 질문에 답할 수 있는 내용을 찾지 못했습니다.";

        assertThat(citationParser.extractCitedNumbers(answer)).isEmpty();
    }

    @Test
    void 두자리_이상_번호도_인식한다() {
        String answer = "근거[10]와 근거[2]를 함께 확인했습니다.";

        assertThat(citationParser.extractCitedNumbers(answer)).containsExactly(10, 2);
    }

    @Test
    void 빈_문자열이면_빈_리스트를_반환한다() {
        assertThat(citationParser.extractCitedNumbers("")).isEqualTo(List.of());
    }

    @Test
    void int_범위를_넘는_긴_숫자는_인용으로_보지_않고_예외도_던지지_않는다() {
        String answer = "접수번호 [20260724800076] 공시에 따르면 배당이 결정됐습니다[1].";

        assertThat(citationParser.extractCitedNumbers(answer)).containsExactly(1);
    }
}
