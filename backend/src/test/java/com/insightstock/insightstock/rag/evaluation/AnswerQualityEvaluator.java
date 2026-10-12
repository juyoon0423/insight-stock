package com.insightstock.insightstock.rag.evaluation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// RAG 답변 1건을 채점하는 순수 로직. DB/API 의존성이 없어 SearchQualityEvaluator와 같은
// 방식으로 단위 테스트(AnswerQualityEvaluatorTest)만으로 검증하고, 실제 답변을 받아오는
// 일은 AnswerQualityEvaluationManualIT가 맡는다. LLM을 심사위원으로 쓰지 않는다 - 모든
// 판정이 문자열 규칙이라 같은 답변에는 항상 같은 점수가 나오고 추가 비용이 없다.
public class AnswerQualityEvaluator {

    // AnswerService.NO_ANSWER_MESSAGE, prompts/rag-system-prompt.txt 규칙 3과 같은 문구.
    public static final String REFUSAL_MESSAGE = "제공된 공시 자료에서는 이 질문에 답할 수 있는 내용을 찾지 못했습니다.";

    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,9})]");
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");
    // "2026.07.14" 같은 점 구분 날짜 - 그대로 두면 "2026.07"이 소수로 읽힌다.
    private static final Pattern DOTTED_DATE = Pattern.compile("(\\d{4})\\.\\s?(\\d{1,2})\\.\\s?(\\d{1,2})");
    // 문장 끝: 마침표류(+ 그 뒤에 붙은 인용) 다음의 공백. 소수점("4.6%")이나 날짜("2027.07.18")의
    // 점은 뒤에 공백이 없어 걸리지 않는다. 인용은 "입니다[1]." "입니다.[1]" "입니다. [1]" 세 가지로
    // 다 쓰이므로, 마침표 뒤에 공백을 두고 온 인용도 앞 문장에 속한 것으로 본다 - 뒤따르는 것이
    // 인용이면 거기서 끊지 않는다. (첫 측정에서 "입니다. [1]"을 끊어 인용 없는 문장으로 잘못 센
    // 것을 발견해 고쳤다 - docs/answer-quality-eval.md 6절.)
    private static final Pattern SENTENCE_END =
            Pattern.compile("([.!?](?:\\s?\\[\\d{1,9}])*)\\s+(?!\\[\\d)");

    // ---- 거절 ----

    // 고정 문구 "그대로"인지(앞뒤 공백만 무시). 문구에 다른 내용을 덧붙이면 정확한 거절이 아니다.
    public boolean isExactRefusal(String answer) {
        return answer != null && answer.strip().equals(REFUSAL_MESSAGE);
    }

    public boolean containsRefusal(String answer) {
        return answer != null && answer.contains("찾지 못했습니다");
    }

    // ---- 인용 유효성 ----

    // citedNumbers는 AnswerService가 출처로 인정한 번호(컨텍스트 범위 안)다. 본문에 적힌 [N] 중
    // 거기 없는 것이 범위 밖 인용이다.
    public CitationValidity citationValidity(String answer, List<Integer> citedNumbers) {
        Set<Integer> written = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            written.add(Integer.parseInt(matcher.group(1)));
        }
        List<Integer> outOfRange = written.stream().filter(n -> !citedNumbers.contains(n)).toList();
        return new CitationValidity(citedNumbers.size(), outOfRange);
    }

    public record CitationValidity(int validCitationCount, List<Integer> outOfRangeNumbers) {

        public boolean hasOutOfRange() {
            return !outOfRangeNumbers.isEmpty();
        }

        public boolean hasNoValidCitation() {
            return validCitationCount == 0;
        }
    }

    // ---- 정답 일치 ----

    // facts: 사실의 목록, 각 사실은 허용 표기의 목록. 모든 사실이 (허용 표기 중 하나로) 답변에
    // 들어 있어야 일치다. 쉼표/공백/대소문자 차이는 무시한다.
    public ExpectedFactResult checkExpectedFacts(String answer, List<List<String>> facts) {
        String normalizedAnswer = normalizeForContains(answer);
        List<String> missing = new ArrayList<>();
        for (List<String> alternatives : facts) {
            boolean found = alternatives.stream().anyMatch(alt -> normalizedAnswer.contains(normalizeForContains(alt)));
            if (!found) {
                missing.add(alternatives.get(0));
            }
        }
        return new ExpectedFactResult(facts.size(), missing);
    }

    public record ExpectedFactResult(int factCount, List<String> missingFacts) {

        public boolean allPresent() {
            return missingFacts.isEmpty();
        }
    }

    private String normalizeForContains(String text) {
        return text.replace(",", "").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    // ---- 수치 근거 ----

    // 답변에 나온 수치 하나하나가 (1) 인용 번호와 함께 쓰였는지 (2) 그 인용된 청크 본문에 실제로
    // 있는지를 본다. contextContents.get(n - 1)이 [n]번 컨텍스트 청크의 본문이다.
    //
    // 한 자리 정수(1~9)는 "1주당", "제3자", 목록 번호처럼 사실 주장이 아닌 경우가 대부분이라 세지 않는다.
    // 수치는 쉼표를 지우고 앞자리 0과 소수점 뒤 0을 정리한 뒤 "토큰 단위"로 비교한다(부분 문자열이
    // 아니다 - "220"이 "1,220"에 걸리지 않는다).
    //
    // 알려진 한계: 원문과 다른 단위로 환산한 수치(원문 "1,688백만" -> 답변 "16억 8,800만원")는 값이
    // 맞아도 UNGROUNDED로 잡힌다. 그래서 UNGROUNDED는 "환각 확정"이 아니라 "사람이 확인할 후보"다.
    public List<NumberFinding> analyzeNumbers(
            String answer, List<Integer> citedNumbers, List<String> contextContents, String query) {
        List<Set<String>> tokensByChunk = contextContents.stream().map(this::numberTokens).toList();
        Set<String> allContextTokens = new HashSet<>();
        tokensByChunk.forEach(allContextTokens::addAll);
        Set<String> queryTokens = numberTokens(query);

        List<NumberFinding> findings = new ArrayList<>();
        for (String unit : SENTENCE_END.matcher(answer).replaceAll("$1\n").split("\\n+")) {
            if (unit.isBlank()) {
                continue;
            }
            List<Integer> unitCitations = new ArrayList<>();
            Matcher citation = CITATION.matcher(unit);
            while (citation.find()) {
                int number = Integer.parseInt(citation.group(1));
                if (citedNumbers.contains(number) && number <= contextContents.size()) {
                    unitCitations.add(number);
                }
            }
            Set<String> citedTokens = new HashSet<>();
            unitCitations.forEach(n -> citedTokens.addAll(tokensByChunk.get(n - 1)));

            // 인용 표시 자체([3])의 숫자는 답변의 수치가 아니다.
            String unitWithoutCitations = CITATION.matcher(unit).replaceAll(" ");
            for (String token : orderedNumberTokens(unitWithoutCitations)) {
                if (isTrivial(token)) {
                    continue;
                }
                boolean cited = !unitCitations.isEmpty();
                findings.add(new NumberFinding(
                        token, classify(token, cited, citedTokens, allContextTokens, queryTokens), cited, unit.strip()));
            }
        }
        return findings;
    }

    private NumberStatus classify(
            String token, boolean unitHasCitation, Set<String> citedTokens, Set<String> allContextTokens,
            Set<String> queryTokens) {
        if (unitHasCitation && citedTokens.contains(token)) {
            return NumberStatus.GROUNDED_IN_CITED;
        }
        if (allContextTokens.contains(token)) {
            return unitHasCitation ? NumberStatus.IN_OTHER_CHUNK : NumberStatus.UNCITED_IN_CONTEXT;
        }
        if (queryTokens.contains(token)) {
            return NumberStatus.FROM_QUERY;
        }
        return NumberStatus.UNGROUNDED;
    }

    public enum NumberStatus {
        // 인용이 붙어 있고, 그 인용된 청크 본문에 같은 수치가 있다 - 가장 엄격한 의미의 "근거 있음".
        GROUNDED_IN_CITED,
        // 인용은 붙어 있지만 수치는 인용하지 않은 다른 컨텍스트 청크에 있다 - 출처를 잘못 붙였다.
        IN_OTHER_CHUNK,
        // 컨텍스트에는 있는 수치인데 문장에 인용이 없다 - 출처 표시 누락.
        UNCITED_IN_CONTEXT,
        // 컨텍스트에는 없고 질문에 있던 수치를 되풀이했다(연도 등) - 집계 분모에서 뺀다.
        FROM_QUERY,
        // 컨텍스트 어디에도 없다 - 환각이거나, 원문과 다른 단위로 환산한 표기다(사람이 확인).
        UNGROUNDED
    }

    // sentenceCited: 이 수치가 들어 있는 문장에 유효한 인용([n])이 붙어 있었는가.
    public record NumberFinding(String number, NumberStatus status, boolean sentenceCited, String sentence) {
    }

    private boolean isTrivial(String token) {
        return token.length() == 1;
    }

    private Set<String> numberTokens(String text) {
        return new HashSet<>(orderedNumberTokens(text));
    }

    private List<String> orderedNumberTokens(String text) {
        String withoutDottedDates = DOTTED_DATE.matcher(text).replaceAll("$1 $2 $3");
        List<String> tokens = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(withoutDottedDates);
        while (matcher.find()) {
            tokens.add(normalizeNumber(matcher.group()));
        }
        return tokens;
    }

    // "1,500" -> "1500", "07" -> "7", "4.60" -> "4.6", "100.0" -> "100"
    private String normalizeNumber(String raw) {
        String value = raw.replace(",", "");
        String integerPart = value;
        String fraction = "";
        int dot = value.indexOf('.');
        if (dot >= 0) {
            integerPart = value.substring(0, dot);
            fraction = value.substring(dot + 1).replaceAll("0+$", "");
        }
        integerPart = integerPart.replaceFirst("^0+(?=\\d)", "");
        return fraction.isEmpty() ? integerPart : integerPart + "." + fraction;
    }

    // ---- 실패 원인 분류 ----

    // 답이 있는 질문 1건의 결과를 "검색이 문제였나, 생성(LLM)이 문제였나"로 나눈다.
    // - retrievalHit: 정답 공시의 청크가 LLM에 넘어간 컨텍스트(top-K) 안에 있었는가
    // - sourceHit: 답변이 실제로 정답 공시를 출처로 인용했는가
    // - factsMatched: 기대값이 답변에 들어 있는가(기대값이 없는 질의는 null)
    public Outcome classifyOutcome(boolean retrievalHit, boolean refused, boolean sourceHit, Boolean factsMatched) {
        if (!retrievalHit) {
            return refused ? Outcome.SEARCH_FAILURE_REFUSED : Outcome.SEARCH_FAILURE_ANSWERED_ANYWAY;
        }
        if (refused) {
            return Outcome.GENERATION_FAILURE_FALSE_REFUSAL;
        }
        if (!sourceHit) {
            return Outcome.GENERATION_FAILURE_WRONG_SOURCE;
        }
        if (Boolean.FALSE.equals(factsMatched)) {
            return Outcome.GENERATION_FAILURE_WRONG_ANSWER;
        }
        return Outcome.SUCCESS;
    }

    public enum Outcome {
        SUCCESS("성공", "성공"),
        // 정답이 컨텍스트에 없었고, 모델은 지어내지 않고 거절했다 - 검색의 실패이지만 LLM은 옳게 행동.
        SEARCH_FAILURE_REFUSED("검색 실패", "검색 실패 → 거절(정직)"),
        // 정답이 컨텍스트에 없었는데 다른 문서를 근거로 답했다 - 가장 위험한 경우.
        SEARCH_FAILURE_ANSWERED_ANYWAY("검색 실패", "검색 실패 → 다른 문서로 답변"),
        // 정답이 컨텍스트에 있었는데 "찾지 못했습니다"라고 했다.
        GENERATION_FAILURE_FALSE_REFUSAL("생성 실패", "생성 실패 → 잘못된 거절"),
        // 정답이 컨텍스트에 있었는데 정답 공시를 인용하지 않았다.
        GENERATION_FAILURE_WRONG_SOURCE("생성 실패", "생성 실패 → 정답 공시 미인용"),
        // 정답 공시를 인용했지만 기대값이 답변에 없다(틀렸거나 누락).
        GENERATION_FAILURE_WRONG_ANSWER("생성 실패", "생성 실패 → 기대값 불일치");

        private final String group;
        private final String label;

        Outcome(String group, String label) {
            this.group = group;
            this.label = label;
        }

        public String group() {
            return group;
        }

        public String label() {
            return label;
        }
    }
}
