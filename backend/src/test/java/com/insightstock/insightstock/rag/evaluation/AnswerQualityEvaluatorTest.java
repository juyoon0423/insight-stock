package com.insightstock.insightstock.rag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.insightstock.insightstock.rag.evaluation.AnswerQualityEvaluator.CitationValidity;
import com.insightstock.insightstock.rag.evaluation.AnswerQualityEvaluator.ExpectedFactResult;
import com.insightstock.insightstock.rag.evaluation.AnswerQualityEvaluator.NumberFinding;
import com.insightstock.insightstock.rag.evaluation.AnswerQualityEvaluator.NumberStatus;
import com.insightstock.insightstock.rag.evaluation.AnswerQualityEvaluator.Outcome;
import java.util.List;
import org.junit.jupiter.api.Test;

// 채점 규칙 자체가 틀리면 측정 결과 전체가 무의미해지므로, 유료 측정(AnswerQualityEvaluationManualIT)
// 전에 규칙을 비용 0인 단위 테스트로 먼저 고정한다(SearchQualityEvaluatorTest와 같은 이유).
class AnswerQualityEvaluatorTest {

    private final AnswerQualityEvaluator evaluator = new AnswerQualityEvaluator();

    // ---- 거절 ----

    @Test
    void 고정_문구_그대로일_때만_정확한_거절이다() {
        assertThat(evaluator.isExactRefusal(AnswerQualityEvaluator.REFUSAL_MESSAGE)).isTrue();
        assertThat(evaluator.isExactRefusal("  " + AnswerQualityEvaluator.REFUSAL_MESSAGE + "\n")).isTrue();
        assertThat(evaluator.isExactRefusal(
                        AnswerQualityEvaluator.REFUSAL_MESSAGE + " 다만 테슬라는 전기차 회사입니다."))
                .isFalse();
        assertThat(evaluator.isExactRefusal("테슬라는 자율주행을 개발 중입니다.")).isFalse();
    }

    // ---- 인용 유효성 ----

    @Test
    void 본문에_적힌_번호_중_인정되지_않은_것이_범위_밖_인용이다() {
        CitationValidity validity = evaluator.citationValidity("매출이 늘었습니다[12]. 부채는 줄었습니다[2][0].", List.of(2));

        assertThat(validity.validCitationCount()).isEqualTo(1);
        assertThat(validity.outOfRangeNumbers()).containsExactly(12, 0);
        assertThat(validity.hasOutOfRange()).isTrue();
        assertThat(validity.hasNoValidCitation()).isFalse();
    }

    @Test
    void 인용이_전혀_없는_답변을_구분한다() {
        CitationValidity validity = evaluator.citationValidity("배당금은 1,500원입니다.", List.of());

        assertThat(validity.hasNoValidCitation()).isTrue();
        assertThat(validity.hasOutOfRange()).isFalse();
    }

    // ---- 정답 일치 ----

    @Test
    void 기대값은_쉼표와_공백_차이를_무시하고_허용_표기_중_하나만_있으면_된다() {
        List<List<String>> facts = List.of(List.of("23,712,000,000", "237억 1,200만"), List.of("2026년 8월 12일"));

        assertThat(evaluator.checkExpectedFacts("계약금액은 23712000000원이며 2026년 8월12일에 체결됐습니다.", facts).allPresent())
                .isTrue();
        assertThat(evaluator.checkExpectedFacts("계약금액은 237억 1200만원, 일자는 2026년 8월 12일입니다.", facts).allPresent())
                .isTrue();
    }

    @Test
    void 사실이_하나라도_빠지면_불일치이고_빠진_사실을_알려준다() {
        List<List<String>> facts = List.of(List.of("1,000"), List.of("17,882,307,000"));

        ExpectedFactResult result = evaluator.checkExpectedFacts("1주당 배당금은 1,000원입니다[1].", facts);

        assertThat(result.allPresent()).isFalse();
        assertThat(result.missingFacts()).containsExactly("17,882,307,000");
    }

    // ---- 수치 근거 ----

    @Test
    void 인용된_청크에_있는_수치는_근거_있음이다() {
        List<NumberFinding> findings = evaluator.analyzeNumbers(
                "1주당 배당금은 1,500원이고 배당기준일은 2026년 8월 10일입니다[1].",
                List.of(1),
                List.of("3. 1주당 배당금(원) 보통주식 1,500 ... 6. 배당기준일 2026-08-10"),
                "현대모비스의 1주당 배당금은 얼마인가?");

        // "1주당"의 1, "8월"의 8은 한 자리라 세지 않는다.
        assertThat(findings).extracting(NumberFinding::number).containsExactly("1500", "2026", "10");
        assertThat(findings).extracting(NumberFinding::status).containsOnly(NumberStatus.GROUNDED_IN_CITED);
    }

    @Test
    void 컨텍스트_어디에도_없는_수치는_근거_없음이다() {
        List<NumberFinding> findings = evaluator.analyzeNumbers(
                "1주당 배당금은 2,500원입니다[1].", List.of(1), List.of("1주당 배당금(원) 1,500"), "배당금은?");

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.number()).isEqualTo("2500");
            assertThat(finding.status()).isEqualTo(NumberStatus.UNGROUNDED);
        });
    }

    @Test
    void 다른_청크의_수치를_가져오면_잘못된_인용으로_구분한다() {
        List<NumberFinding> findings = evaluator.analyzeNumbers(
                "배당금총액은 17,882,307,000원입니다[1].",
                List.of(1),
                List.of("1주당 배당금 1,000", "배당금총액(원) 17,882,307,000"),
                "배당금총액은?");

        assertThat(findings).singleElement().extracting(NumberFinding::status).isEqualTo(NumberStatus.IN_OTHER_CHUNK);
    }

    @Test
    void 인용_없는_문장의_수치는_컨텍스트에_있어도_인용_누락이다() {
        List<NumberFinding> findings = evaluator.analyzeNumbers(
                "이자율은 4.6%입니다.", List.of(), List.of("마. 이자율(%) | 4.60"), "이자율은?");

        // 4.6과 4.60은 같은 수치로 본다.
        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.number()).isEqualTo("4.6");
            assertThat(finding.status()).isEqualTo(NumberStatus.UNCITED_IN_CONTEXT);
        });
    }

    @Test
    void 문장마다_자기_인용으로_판정한다() {
        List<NumberFinding> findings = evaluator.analyzeNumbers(
                "차입금액은 1,300백만원입니다[1]. 이자율은 12.5%입니다.[2] 상환일은 2027.07.18.입니다[1].",
                List.of(1, 2),
                List.of("라. 차입금액 | 1,300 ... 상환일 | 2027.07.18.", "무관한 내용 99"),
                "차입 금액은?");

        assertThat(findings).extracting(NumberFinding::number, NumberFinding::status)
                .containsExactly(
                        tuple("1300", NumberStatus.GROUNDED_IN_CITED),
                        tuple("12.5", NumberStatus.UNGROUNDED),
                        tuple("2027", NumberStatus.GROUNDED_IN_CITED),
                        tuple("18", NumberStatus.GROUNDED_IN_CITED));
    }

    @Test
    void 마침표_뒤에_공백을_두고_온_인용도_앞_문장의_인용이다() {
        // 실제 측정에서 나온 형태(Q11). 처음에는 ". [1]"에서 문장을 끊어 인용 없는 문장으로 잘못 셌다.
        List<NumberFinding> findings = evaluator.analyzeNumbers(
                "실적발표회는 2026년 8월 12일 16:00에 열립니다. [1] 자료는 당일 게재됩니다.",
                List.of(1),
                List.of("일시 2026-08-12 16:00"),
                "언제 열리는가?");

        assertThat(findings).extracting(NumberFinding::number).containsExactly("2026", "12", "16");
        assertThat(findings).extracting(NumberFinding::status).containsOnly(NumberStatus.GROUNDED_IN_CITED);
    }

    @Test
    void 인용_번호와_범위_밖_번호는_답변의_수치로_세지_않는다() {
        List<NumberFinding> findings = evaluator.analyzeNumbers(
                "매출은 719,897백만원입니다[3][12].", List.of(3), List.of("a", "b", "매출액 719,897"), "매출은?");

        assertThat(findings).extracting(NumberFinding::number).containsExactly("719897");
        assertThat(findings).extracting(NumberFinding::status).containsExactly(NumberStatus.GROUNDED_IN_CITED);
    }

    @Test
    void 수치는_부분_문자열이_아니라_토큰_단위로_비교한다() {
        List<NumberFinding> findings = evaluator.analyzeNumbers(
                "배당금은 220원입니다[1].", List.of(1), List.of("배당금 1,220원"), "배당금은?");

        assertThat(findings).singleElement().extracting(NumberFinding::status).isEqualTo(NumberStatus.UNGROUNDED);
    }

    @Test
    void 질문에_있던_수치를_되풀이한_것은_따로_구분한다() {
        List<NumberFinding> findings = evaluator.analyzeNumbers(
                "2030년 전망은 자료에 없습니다[1].", List.of(1), List.of("2025년 실적"), "2030년 전망은?");

        assertThat(findings).singleElement().extracting(NumberFinding::status).isEqualTo(NumberStatus.FROM_QUERY);
    }

    @Test
    void 단위를_환산한_수치는_근거_없음으로_잡힌다_알려진_한계() {
        // 원문은 "1,688"(단위: 백만 원)인데 답변이 "16억 8,800만원"으로 환산했다. 값은 맞지만
        // 문자열 규칙으로는 확인할 수 없어 UNGROUNDED가 된다 - 사람이 확인해야 하는 후보다.
        List<NumberFinding> findings = evaluator.analyzeNumbers(
                "대여 금액은 16억 8,800만원입니다[1].", List.of(1), List.of("나. 거래금액 | 1,688"), "빌려준 금액은?");

        assertThat(findings).extracting(NumberFinding::status).containsOnly(NumberStatus.UNGROUNDED);
        // 근거는 확인하지 못했지만 인용은 붙어 있었다는 사실은 따로 남는다(인용 동반율 계산용).
        assertThat(findings).extracting(NumberFinding::sentenceCited).containsOnly(true);
    }

    @Test
    void 범위_밖_번호만_붙은_문장은_인용이_없는_문장으로_본다() {
        List<NumberFinding> findings = evaluator.analyzeNumbers(
                "매출은 719,897백만원입니다[12].", List.of(), List.of("매출액 719,897"), "매출은?");

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.sentenceCited()).isFalse();
            assertThat(finding.status()).isEqualTo(NumberStatus.UNCITED_IN_CONTEXT);
        });
    }

    // ---- 실패 원인 분류 ----

    @Test
    void 정답이_컨텍스트에_없으면_검색_실패다() {
        assertThat(evaluator.classifyOutcome(false, true, false, null)).isEqualTo(Outcome.SEARCH_FAILURE_REFUSED);
        assertThat(evaluator.classifyOutcome(false, false, false, false))
                .isEqualTo(Outcome.SEARCH_FAILURE_ANSWERED_ANYWAY);
    }

    @Test
    void 정답이_컨텍스트에_있었는데_못_맞히면_생성_실패다() {
        assertThat(evaluator.classifyOutcome(true, true, false, null))
                .isEqualTo(Outcome.GENERATION_FAILURE_FALSE_REFUSAL);
        assertThat(evaluator.classifyOutcome(true, false, false, true))
                .isEqualTo(Outcome.GENERATION_FAILURE_WRONG_SOURCE);
        assertThat(evaluator.classifyOutcome(true, false, true, false))
                .isEqualTo(Outcome.GENERATION_FAILURE_WRONG_ANSWER);
    }

    @Test
    void 정답_공시를_인용하고_기대값도_맞으면_성공이다_기대값이_없는_질의는_출처만_본다() {
        assertThat(evaluator.classifyOutcome(true, false, true, true)).isEqualTo(Outcome.SUCCESS);
        assertThat(evaluator.classifyOutcome(true, false, true, null)).isEqualTo(Outcome.SUCCESS);
    }
}
