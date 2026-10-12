package com.insightstock.insightstock.rag.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightstock.insightstock.rag.AnswerService;
import com.insightstock.insightstock.rag.dto.AnswerResponse;
import com.insightstock.insightstock.rag.dto.SourceRef;
import com.insightstock.insightstock.rag.evaluation.AnswerQualityEvaluator.CitationValidity;
import com.insightstock.insightstock.rag.evaluation.AnswerQualityEvaluator.ExpectedFactResult;
import com.insightstock.insightstock.rag.evaluation.AnswerQualityEvaluator.NumberFinding;
import com.insightstock.insightstock.rag.evaluation.AnswerQualityEvaluator.NumberStatus;
import com.insightstock.insightstock.rag.evaluation.AnswerQualityEvaluator.Outcome;
import com.insightstock.insightstock.search.HybridSearchService;
import com.insightstock.insightstock.search.dto.RankedChunk;
import com.insightstock.insightstock.search.evaluation.EvaluationQuerySetLoader;
import com.insightstock.insightstock.search.evaluation.GroundTruthQuery;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

// RAG 답변 품질 정식 측정 - docs/answer-quality-eval.md.
//
// 검색 품질 측정(SearchQualityEvaluationManualIT)은 "정답 공시를 찾았는가"까지만 본다. 이 테스트는
// 그 다음 단계, 즉 LLM이 만든 최종 답변이 맞는지를 다섯 가지 지표로 본다:
//   1) 출처 적중률  2) 거절 정확도  3) 수치 근거율  4) 인용 유효성  5) 정답 일치
// 그리고 틀린 경우를 "검색 실패(정답이 컨텍스트에 없었다)"와 "생성 실패(있었는데 LLM이 못 맞혔다)"로
// 나눈다. 채점 규칙은 AnswerQualityEvaluator(단위 테스트로 검증됨)에 있고 여기서는 실행과 집계만 한다.
//
// 질의: 검색 품질 측정의 18개(정형 12 + 서술형 6, 정답 = groundTruth.rceptNo)를 그대로 재사용하고,
// 거절 정확도용으로 답할 수 없는 질문 8개(answer-quality-unanswerable-queries.json)를 더했다.
//
// 비용이 드는 수동 측정이다(@Tag("manual"), ./gradlew manualTest로만 실행): 질의 26개 x 실제
// gpt-4o-mini 호출 1회. 질의당 입력 약 4,500토큰이라 전체 약 $0.03 수준이다. 그 외에 질의 임베딩이
// 질의당 2회(아래 search() 1회 + AnswerService 내부 1회) 발생하지만 수십 토큰이라 사실상 0.
//
// 컨텍스트를 따로 한 번 더 검색하는 이유: AnswerResponse는 "인용된 출처"만 돌려주고 LLM에 넘어간
// 전체 컨텍스트는 돌려주지 않는다. 검색 실패/생성 실패를 나누려면 컨텍스트 전체가 필요해서, 같은
// 질의·같은 top-K로 HybridSearchService를 직접 한 번 더 호출한다. 두 검색 결과가 실제로 같았는지는
// 답변의 인용 번호가 가리키는 청크와 응답의 출처를 대조해 확인하고 결과에 표시한다(contextMismatch).
@Tag("manual")
@SpringBootTest
class AnswerQualityEvaluationManualIT {

    private static final String STRUCTURED_QUERY_SET = "evaluation/search-quality-queries.json";
    private static final String NARRATIVE_QUERY_SET = "evaluation/search-quality-queries-narrative.json";
    private static final String EXPECTED_FACTS = "evaluation/answer-quality-expected-facts.json";
    private static final String UNANSWERABLE_QUERY_SET = "evaluation/answer-quality-unanswerable-queries.json";
    private static final Path REPORT_PATH = Path.of("build/reports/answer-quality/answer-quality-result.md");

    // OpenAI TPM 한도(200,000)를 건드리지 않도록 호출 사이에 둔다 - 26건 x 약 4,500토큰을 한꺼번에
    // 보내면 한도의 절반을 넘는다. 간격을 두면 분당 약 15건(약 7만 토큰) 수준이다.
    private static final long PAUSE_BETWEEN_CALLS_MS = 1500;

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private AnswerService answerService;

    @Value("${hybrid-search.top-k}")
    private int topK;

    private final AnswerQualityEvaluator evaluator = new AnswerQualityEvaluator();
    private final EvaluationQuerySetLoader loader = new EvaluationQuerySetLoader();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void RAG_답변_품질_측정() throws Exception {
        Map<String, List<List<String>>> expectedFacts = loadExpectedFacts();
        List<UnanswerableQuery> unanswerableQueries = loadUnanswerableQueries();

        List<AnswerableResult> structured = evaluateAnswerable(loader.load(STRUCTURED_QUERY_SET), "정형", expectedFacts);
        List<AnswerableResult> narrative = evaluateAnswerable(loader.load(NARRATIVE_QUERY_SET), "서술형", expectedFacts);
        List<UnanswerableResult> unanswerable = new ArrayList<>();
        for (UnanswerableQuery query : unanswerableQueries) {
            unanswerable.add(evaluateUnanswerable(query));
        }

        List<AnswerableResult> answerable = new ArrayList<>(structured);
        answerable.addAll(narrative);

        String report = buildReport(answerable, unanswerable);
        System.out.println(report);
        Files.createDirectories(REPORT_PATH.getParent());
        Files.writeString(REPORT_PATH, report, StandardCharsets.UTF_8);
        System.out.println("결과 파일: " + REPORT_PATH.toAbsolutePath());
    }

    // ---- 실행 ----

    private List<AnswerableResult> evaluateAnswerable(
            List<GroundTruthQuery> queries, String setLabel, Map<String, List<List<String>>> expectedFacts)
            throws InterruptedException {
        List<AnswerableResult> results = new ArrayList<>();
        for (GroundTruthQuery query : queries) {
            results.add(evaluateAnswerableOne(query, setLabel, expectedFacts.get(query.id())));
            Thread.sleep(PAUSE_BETWEEN_CALLS_MS);
        }
        return results;
    }

    private AnswerableResult evaluateAnswerableOne(GroundTruthQuery query, String setLabel, List<List<String>> facts) {
        List<RankedChunk> context = hybridSearchService.search(query.query(), topK);
        AnswerResponse response = answerService.answer(query.query());

        int targetRank = firstRankOf(context, query.targetRceptNo());
        boolean retrievalHit = targetRank > 0;
        boolean refused = evaluator.isExactRefusal(response.answer());
        boolean sourceHit =
                response.sources().stream().anyMatch(source -> source.rceptNo().equals(query.targetRceptNo()));
        ExpectedFactResult factResult = facts == null ? null : evaluator.checkExpectedFacts(response.answer(), facts);
        Boolean factsMatched = factResult == null ? null : factResult.allPresent();

        return new AnswerableResult(
                query,
                setLabel,
                response,
                context,
                targetRank,
                refused,
                sourceHit,
                factResult,
                evaluator.citationValidity(response.answer(), response.citedNumbers()),
                analyzeNumbers(response, context, query.query()),
                evaluator.classifyOutcome(retrievalHit, refused, sourceHit, factsMatched),
                isContextMismatch(response, context));
    }

    private UnanswerableResult evaluateUnanswerable(UnanswerableQuery query) throws InterruptedException {
        List<RankedChunk> context = hybridSearchService.search(query.query(), topK);
        AnswerResponse response = answerService.answer(query.query());
        Thread.sleep(PAUSE_BETWEEN_CALLS_MS);

        boolean exactRefusal = evaluator.isExactRefusal(response.answer()) && response.sources().isEmpty();
        return new UnanswerableResult(
                query,
                response,
                exactRefusal,
                evaluator.containsRefusal(response.answer()),
                evaluator.citationValidity(response.answer(), response.citedNumbers()),
                analyzeNumbers(response, context, query.query()),
                isContextMismatch(response, context));
    }

    private List<NumberFinding> analyzeNumbers(AnswerResponse response, List<RankedChunk> context, String query) {
        if (evaluator.isExactRefusal(response.answer())) {
            return List.of();
        }
        return evaluator.analyzeNumbers(
                response.answer(),
                response.citedNumbers(),
                context.stream().map(RankedChunk::content).toList(),
                query);
    }

    // 정답 공시의 청크가 컨텍스트 몇 번째에 있었는가(1부터, 없으면 -1). 중복 제거로 대표 청크에
    // 합쳐진 경우(duplicateSources)도 "컨텍스트에 그 내용이 있었다"로 본다.
    private int firstRankOf(List<RankedChunk> context, String targetRceptNo) {
        for (int i = 0; i < context.size(); i++) {
            RankedChunk chunk = context.get(i);
            boolean isTarget = chunk.rceptNo().equals(targetRceptNo)
                    || chunk.duplicateSources().stream().anyMatch(dup -> dup.rceptNo().equals(targetRceptNo));
            if (isTarget) {
                return i + 1;
            }
        }
        return -1;
    }

    // 따로 검색한 컨텍스트가 AnswerService가 실제로 쓴 컨텍스트와 같았는지 확인한다: 답변의
    // [n]이 가리키는 우리 쪽 청크의 공시가 응답의 출처와 같아야 한다.
    private boolean isContextMismatch(AnswerResponse response, List<RankedChunk> context) {
        List<Integer> citedNumbers = response.citedNumbers();
        List<SourceRef> sources = response.sources();
        for (int i = 0; i < citedNumbers.size(); i++) {
            int number = citedNumbers.get(i);
            if (number > context.size() || !context.get(number - 1).rceptNo().equals(sources.get(i).rceptNo())) {
                return true;
            }
        }
        return false;
    }

    // ---- 리포트 ----

    private String buildReport(List<AnswerableResult> answerable, List<UnanswerableResult> unanswerable) {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAG 답변 품질 측정 결과 (원본 출력)\n\n");
        sb.append("- 답이 있는 질의 ").append(answerable.size()).append("개, 답이 없는 질의 ")
                .append(unanswerable.size()).append("개, top-K=").append(topK).append("\n");
        long mismatches = answerable.stream().filter(AnswerableResult::contextMismatch).count()
                + unanswerable.stream().filter(UnanswerableResult::contextMismatch).count();
        sb.append("- 컨텍스트 재검색 불일치(contextMismatch): ").append(mismatches).append("건\n\n");

        appendPerQueryTable(sb, answerable);
        appendOutcomeSummary(sb, "전체", answerable);
        appendOutcomeSummary(sb, "정형", filter(answerable, r -> r.setLabel().equals("정형")));
        appendOutcomeSummary(sb, "서술형", filter(answerable, r -> r.setLabel().equals("서술형")));
        appendSourceAndFactSummary(sb, answerable);
        appendRefusalSummary(sb, answerable, unanswerable);
        appendCitationSummary(sb, answerable, unanswerable);
        appendNumberSummary(sb, answerable, unanswerable);
        appendDetails(sb, answerable, unanswerable);
        return sb.toString();
    }

    private void appendPerQueryTable(StringBuilder sb, List<AnswerableResult> results) {
        sb.append("## 질의별 결과 (답이 있는 질의)\n\n");
        sb.append("| id | 회사 | 정답 순위(top-").append(topK)
                .append(") | 출처 적중 | 정답 일치 | 인용 수 | 범위 밖 인용 | 수치(근거/전체) | 판정 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (AnswerableResult r : results) {
            NumberCounts counts = NumberCounts.of(r.numbers());
            sb.append("| ").append(r.query().id())
                    .append(" | ").append(r.query().targetCorpName())
                    .append(" | ").append(r.targetRank() > 0 ? r.targetRank() : "-")
                    .append(" | ").append(mark(r.sourceHit()))
                    .append(" | ").append(r.factResult() == null ? "(기대값 없음)" : mark(r.factResult().allPresent()))
                    .append(" | ").append(r.citationValidity().validCitationCount())
                    .append(" | ").append(r.citationValidity().outOfRangeNumbers().isEmpty()
                            ? "-" : r.citationValidity().outOfRangeNumbers())
                    .append(" | ").append(r.refused() ? "(거절)" : counts.groundedInCited() + "/" + counts.denominator())
                    .append(" | ").append(r.outcome().label())
                    .append(" |\n");
        }
        sb.append('\n');
    }

    private void appendOutcomeSummary(StringBuilder sb, String label, List<AnswerableResult> results) {
        sb.append("## 판정 집계: ").append(label).append(" (n=").append(results.size()).append(")\n\n");
        sb.append("| 판정 | 건수 | 비율 |\n|---|---|---|\n");
        for (Outcome outcome : Outcome.values()) {
            long count = results.stream().filter(r -> r.outcome() == outcome).count();
            sb.append("| ").append(outcome.label()).append(" | ").append(count)
                    .append(" | ").append(ratio(count, results.size())).append(" |\n");
        }
        long success = results.stream().filter(r -> r.outcome() == Outcome.SUCCESS).count();
        long searchFailure = results.stream().filter(r -> r.outcome().group().equals("검색 실패")).count();
        long generationFailure = results.stream().filter(r -> r.outcome().group().equals("생성 실패")).count();
        sb.append("\n- 성공 ").append(success).append(" / 검색 실패 ").append(searchFailure)
                .append(" / 생성 실패 ").append(generationFailure).append("\n\n");
    }

    private void appendSourceAndFactSummary(StringBuilder sb, List<AnswerableResult> results) {
        List<AnswerableResult> retrieved = filter(results, r -> r.targetRank() > 0);
        List<AnswerableResult> withFacts = filter(results, r -> r.factResult() != null);
        List<AnswerableResult> retrievedWithFacts = filter(retrieved, r -> r.factResult() != null);

        sb.append("## 지표 1. 출처 적중률 / 지표 5. 정답 일치\n\n");
        sb.append("| 지표 | 분자/분모 | 값 |\n|---|---|---|\n");
        row(sb, "컨텍스트 적중(정답 공시가 top-" + topK + "에 있음)", retrieved.size(), results.size());
        row(sb, "출처 적중률(전체)", count(results, AnswerableResult::sourceHit), results.size());
        row(sb, "출처 적중률(컨텍스트 적중한 질의만)", count(retrieved, AnswerableResult::sourceHit), retrieved.size());
        row(sb, "정답 일치(기대값 있는 질의 전체)",
                count(withFacts, r -> r.factResult().allPresent()), withFacts.size());
        row(sb, "정답 일치(컨텍스트 적중한 질의만)",
                count(retrievedWithFacts, r -> r.factResult().allPresent()), retrievedWithFacts.size());
        sb.append('\n');

        sb.append("정답 불일치 상세:\n\n");
        for (AnswerableResult r : withFacts) {
            if (!r.factResult().allPresent()) {
                sb.append("- ").append(r.query().id()).append(": 빠진 기대값 ").append(r.factResult().missingFacts())
                        .append(" (").append(r.outcome().label()).append(")\n");
            }
        }
        sb.append('\n');
    }

    private void appendRefusalSummary(
            StringBuilder sb, List<AnswerableResult> answerable, List<UnanswerableResult> unanswerable) {
        sb.append("## 지표 2. 거절 정확도 (답이 없는 질의 ").append(unanswerable.size()).append("개)\n\n");
        sb.append("| id | type | 질의 | 정확한 거절 | 거절 문구 포함 | 출처 수 |\n|---|---|---|---|---|---|\n");
        for (UnanswerableResult r : unanswerable) {
            sb.append("| ").append(r.query().id()).append(" | ").append(r.query().type())
                    .append(" | ").append(r.query().query())
                    .append(" | ").append(mark(r.exactRefusal()))
                    .append(" | ").append(mark(r.containsRefusal()))
                    .append(" | ").append(r.response().sources().size()).append(" |\n");
        }
        sb.append("\n| 지표 | 분자/분모 | 값 |\n|---|---|---|\n");
        row(sb, "거절 정확도(고정 문구 그대로 + 출처 0건)",
                count(unanswerable, UnanswerableResult::exactRefusal), unanswerable.size());
        row(sb, "답을 지어낸 비율(거절 문구 자체가 없음)",
                count(unanswerable, r -> !r.containsRefusal()), unanswerable.size());
        row(sb, "잘못된 거절(답이 있는 질의인데 거절)", count(answerable, AnswerableResult::refused), answerable.size());
        List<AnswerableResult> retrieved = filter(answerable, r -> r.targetRank() > 0);
        row(sb, "잘못된 거절(컨텍스트 적중한 질의만)", count(retrieved, AnswerableResult::refused), retrieved.size());
        sb.append('\n');
    }

    private void appendCitationSummary(
            StringBuilder sb, List<AnswerableResult> answerable, List<UnanswerableResult> unanswerable) {
        List<CitationValidity> answered = new ArrayList<>();
        answerable.stream().filter(r -> !r.refused()).forEach(r -> answered.add(r.citationValidity()));
        unanswerable.stream().filter(r -> !r.exactRefusal()).forEach(r -> answered.add(r.citationValidity()));

        sb.append("## 지표 4. 인용 유효성 (거절이 아닌 답변 ").append(answered.size()).append("건)\n\n");
        sb.append("| 지표 | 분자/분모 | 값 |\n|---|---|---|\n");
        row(sb, "범위 밖 인용이 1개 이상 있는 답변", count(answered, CitationValidity::hasOutOfRange), answered.size());
        row(sb, "유효한 인용이 0건인 답변", count(answered, CitationValidity::hasNoValidCitation), answered.size());
        sb.append('\n');
    }

    private void appendNumberSummary(
            StringBuilder sb, List<AnswerableResult> answerable, List<UnanswerableResult> unanswerable) {
        sb.append("## 지표 3. 수치 근거율\n\n");
        appendNumberTable(sb, "답이 있는 질의 - 전체",
                flatten(answerable.stream().map(AnswerableResult::numbers).toList()));
        appendNumberTable(sb, "답이 있는 질의 - 판정이 '성공'인 답변만",
                flatten(filter(answerable, r -> r.outcome() == Outcome.SUCCESS).stream()
                        .map(AnswerableResult::numbers).toList()));
        appendNumberTable(sb, "답이 있는 질의 - 검색 실패인데 답변한 경우만",
                flatten(filter(answerable, r -> r.outcome() == Outcome.SEARCH_FAILURE_ANSWERED_ANYWAY).stream()
                        .map(AnswerableResult::numbers).toList()));
        appendNumberTable(sb, "답이 없는 질의 - 거절하지 않고 답한 경우",
                flatten(unanswerable.stream().map(UnanswerableResult::numbers).toList()));

        sb.append("### 근거를 확인하지 못한 수치 전체 목록 (사람이 확인할 후보)\n\n");
        sb.append("| id | 수치 | 분류 | 문장 |\n|---|---|---|---|\n");
        for (AnswerableResult r : answerable) {
            appendSuspectNumbers(sb, r.query().id(), r.numbers());
        }
        for (UnanswerableResult r : unanswerable) {
            appendSuspectNumbers(sb, r.query().id(), r.numbers());
        }
        sb.append('\n');
    }

    private void appendNumberTable(StringBuilder sb, String label, List<NumberFinding> findings) {
        NumberCounts counts = NumberCounts.of(findings);
        sb.append("### ").append(label).append(" (수치 ").append(counts.denominator()).append("개");
        sb.append(", 질문에서 온 수치 ").append(counts.fromQuery()).append("개 제외)\n\n");
        sb.append("| 분류 | 개수 | 비율 |\n|---|---|---|\n");
        row(sb, "인용된 청크에 있음(엄격한 근거)", counts.groundedInCited(), counts.denominator());
        row(sb, "인용은 있으나 다른 청크의 수치(잘못된 인용)", counts.inOtherChunk(), counts.denominator());
        row(sb, "컨텍스트에 있으나 인용 없음(인용 누락)", counts.uncitedInContext(), counts.denominator());
        row(sb, "컨텍스트 어디에도 없음(환각 또는 단위 환산)", counts.ungrounded(), counts.denominator());
        sb.append("\n| 요약 지표 | 분자/분모 | 값 |\n|---|---|---|\n");
        row(sb, "인용 동반율(수치가 유효한 인용과 함께 쓰임)", counts.cited(), counts.denominator());
        row(sb, "수치 근거율 - 엄격(인용된 청크에 실제로 있음)", counts.groundedInCited(), counts.denominator());
        row(sb, "수치 근거율 - 느슨(컨텍스트 어딘가에 있음)",
                counts.groundedInCited() + counts.inOtherChunk() + counts.uncitedInContext(), counts.denominator());
        sb.append('\n');
    }

    private void appendSuspectNumbers(StringBuilder sb, String id, List<NumberFinding> findings) {
        for (NumberFinding finding : findings) {
            if (finding.status() != NumberStatus.GROUNDED_IN_CITED && finding.status() != NumberStatus.FROM_QUERY) {
                sb.append("| ").append(id).append(" | ").append(finding.number())
                        .append(" | ").append(finding.status())
                        .append(" | ").append(oneLine(finding.sentence())).append(" |\n");
            }
        }
    }

    private void appendDetails(
            StringBuilder sb, List<AnswerableResult> answerable, List<UnanswerableResult> unanswerable) {
        sb.append("## 답변 원문\n\n");
        for (AnswerableResult r : answerable) {
            sb.append("### ").append(r.query().id()).append(" [").append(r.outcome().label()).append("] ")
                    .append(r.query().query()).append("\n\n");
            sb.append("- 정답 공시: ").append(r.query().targetRceptNo()).append(" (").append(r.query().targetCorpName())
                    .append(" / ").append(r.query().targetReportNm()).append("), 컨텍스트 순위: ")
                    .append(r.targetRank() > 0 ? r.targetRank() : "없음").append(", 컨텍스트 ")
                    .append(r.context().size()).append("건, contextMismatch=").append(r.contextMismatch()).append("\n");
            appendAnswer(sb, r.response());
            appendContext(sb, r.context(), r.query().targetRceptNo());
        }
        for (UnanswerableResult r : unanswerable) {
            sb.append("### ").append(r.query().id()).append(" [").append(r.exactRefusal() ? "정확한 거절" : "거절 실패")
                    .append("] ").append(r.query().query()).append("\n\n");
            appendAnswer(sb, r.response());
        }
    }

    // "정답 순위"는 공시 단위 판정이라, 정답 공시의 청크가 컨텍스트에 있어도 그 청크에 답이 없을 수
    // 있다(수백 개 청크로 쪼개진 긴 문서). 사람이 청크 단위로 확인할 수 있도록 컨텍스트를 남긴다.
    private void appendContext(StringBuilder sb, List<RankedChunk> context, String targetRceptNo) {
        sb.append("컨텍스트(★ = 정답 공시의 청크):\n\n");
        for (int i = 0; i < context.size(); i++) {
            RankedChunk chunk = context.get(i);
            sb.append("- ").append(chunk.rceptNo().equals(targetRceptNo) ? "★" : "").append('[').append(i + 1)
                    .append("] chunkId=").append(chunk.chunkId()).append(" ").append(chunk.corpName()).append(" / ")
                    .append(chunk.reportNm()).append(" / ").append(chunk.sectionTitle()).append(" | ")
                    .append(oneLine(chunk.content().substring(0, Math.min(120, chunk.content().length()))))
                    .append("\n");
        }
        sb.append('\n');
    }

    private void appendAnswer(StringBuilder sb, AnswerResponse response) {
        sb.append("- 인용 번호: ").append(response.citedNumbers()).append(", 출처: ");
        sb.append(response.sources().stream()
                .map(source -> source.corpName() + "/" + source.reportNm() + "(" + source.rceptNo() + ")")
                .toList());
        sb.append("\n\n```\n").append(response.answer()).append("\n```\n\n");
    }

    // ---- 보조 ----

    private void row(StringBuilder sb, String label, long numerator, long denominator) {
        sb.append("| ").append(label).append(" | ").append(numerator).append("/").append(denominator)
                .append(" | ").append(ratio(numerator, denominator)).append(" |\n");
    }

    private String ratio(long numerator, long denominator) {
        return denominator == 0 ? "-" : "%.3f".formatted(numerator / (double) denominator);
    }

    private String mark(boolean value) {
        return value ? "O" : "X";
    }

    private String oneLine(String text) {
        return text.replace("|", "/").replaceAll("\\s+", " ");
    }

    private <T> List<T> filter(List<T> items, Predicate<T> predicate) {
        return items.stream().filter(predicate).toList();
    }

    private <T> long count(List<T> items, Predicate<T> predicate) {
        return items.stream().filter(predicate).count();
    }

    private List<NumberFinding> flatten(List<List<NumberFinding>> lists) {
        return lists.stream().flatMap(List::stream).toList();
    }

    private Map<String, List<List<String>>> loadExpectedFacts() throws IOException {
        try (InputStream in = resource(EXPECTED_FACTS)) {
            JsonNode root = objectMapper.readTree(in);
            return objectMapper.convertValue(
                    root.get("expectedFacts"), new TypeReference<LinkedHashMap<String, List<List<String>>>>() {});
        }
    }

    private List<UnanswerableQuery> loadUnanswerableQueries() throws IOException {
        try (InputStream in = resource(UNANSWERABLE_QUERY_SET)) {
            JsonNode root = objectMapper.readTree(in);
            return objectMapper.convertValue(root.get("queries"), new TypeReference<List<UnanswerableQuery>>() {});
        }
    }

    private InputStream resource(String classpathResource) {
        InputStream in = getClass().getClassLoader().getResourceAsStream(classpathResource);
        if (in == null) {
            throw new IllegalStateException("평가용 파일을 찾을 수 없습니다: " + classpathResource);
        }
        return in;
    }

    private record UnanswerableQuery(String id, String type, String query) {
    }

    private record AnswerableResult(
            GroundTruthQuery query,
            String setLabel,
            AnswerResponse response,
            List<RankedChunk> context,
            int targetRank,
            boolean refused,
            boolean sourceHit,
            ExpectedFactResult factResult,
            CitationValidity citationValidity,
            List<NumberFinding> numbers,
            Outcome outcome,
            boolean contextMismatch) {
    }

    private record UnanswerableResult(
            UnanswerableQuery query,
            AnswerResponse response,
            boolean exactRefusal,
            boolean containsRefusal,
            CitationValidity citationValidity,
            List<NumberFinding> numbers,
            boolean contextMismatch) {
    }

    // denominator는 질문에서 온 수치(FROM_QUERY)를 뺀 개수다.
    private record NumberCounts(
            long groundedInCited, long inOtherChunk, long uncitedInContext, long ungrounded, long cited,
            long fromQuery) {

        static NumberCounts of(List<NumberFinding> findings) {
            return new NumberCounts(
                    countOf(findings, NumberStatus.GROUNDED_IN_CITED),
                    countOf(findings, NumberStatus.IN_OTHER_CHUNK),
                    countOf(findings, NumberStatus.UNCITED_IN_CONTEXT),
                    countOf(findings, NumberStatus.UNGROUNDED),
                    // 근거 확인 여부와 무관하게, 유효한 인용이 붙은 문장에 있던 수치의 개수.
                    findings.stream()
                            .filter(f -> f.status() != NumberStatus.FROM_QUERY && f.sentenceCited())
                            .count(),
                    countOf(findings, NumberStatus.FROM_QUERY));
        }

        long denominator() {
            return groundedInCited + inOtherChunk + uncitedInContext + ungrounded;
        }

        private static long countOf(List<NumberFinding> findings, NumberStatus status) {
            return findings.stream().filter(f -> f.status() == status).count();
        }
    }
}
