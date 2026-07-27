package com.insightstock.insightstock.search.evaluation;

import com.insightstock.insightstock.domain.ChunkSource;
import com.insightstock.insightstock.repository.ChunkDisclosureRef;
import com.insightstock.insightstock.repository.ChunkRepository;
import com.insightstock.insightstock.search.HybridSearchService;
import com.insightstock.insightstock.search.KeywordSearchService;
import com.insightstock.insightstock.search.VectorSearchService;
import com.insightstock.insightstock.search.dto.KeywordMatch;
import com.insightstock.insightstock.search.dto.RankedChunk;
import com.insightstock.insightstock.search.dto.VectorMatch;
import com.insightstock.insightstock.search.evaluation.SearchQualityEvaluator.EvaluationResult;
import com.insightstock.insightstock.search.evaluation.SearchQualityEvaluator.RankedItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 검색 품질 정식 측정 - docs/progress.md "검색 품질 측정" 항목.
//
// 두 질의 세트를 같은 로직(loadAndEvaluate)으로 돌린다:
// - search-quality-queries.json(정형 12개) - corp_code+report_nm 유일성으로 뽑다 보니
//   chunk_source가 WHOLE_DOCUMENT(라벨+숫자 위주)로 쏠린 선택 편향이 있음(문서화됨).
// - search-quality-queries-narrative.json(서술형 6개) - 그 편향을 보완하기 위해
//   SECTION/SLIDING_WINDOW 위주 장문 서사 문서에서 정답을 rcept_no로 직접 고정해
//   골랐다. 테마(차입금/매출·실적/합병목적) x 조건(EXACT/MISMATCH) 짝으로 구성돼
//   "문서 유형이 변수인가, 어휘 조건이 변수인가"를 분리해서 볼 수 있다.
//
// 후보 풀(candidate=50)을 키워드/벡터/하이브리드 세 방식 동일하게 두고 각각 호출한다.
// 하이브리드는 HybridSearchService.search()를 그대로 호출해 RRF+dedup까지 포함한
// 실제 production 경로를 그대로 측정한다. 비용은 질의 임베딩(벡터 단독 1회 +
// 하이브리드 내부 1회, 질의당 2회) 뿐이라 사실상 0(@Tag("manual")로 기본 test 제외).
//
// @TestInstance(PER_CLASS) + @TestMethodOrder로 정형 결과를 인스턴스 필드에 저장해
// 서술형 테스트에서 재사용한다 - 정형 12개를 두 번 돌리는 낭비(임베딩 재호출)를 피함.
@Tag("manual")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SearchQualityEvaluationManualIT {

    private static final int CANDIDATE_SIZE = 50;
    private static final String STRUCTURED_QUERY_SET = "evaluation/search-quality-queries.json";
    private static final String NARRATIVE_QUERY_SET = "evaluation/search-quality-queries-narrative.json";

    @Autowired
    private KeywordSearchService keywordSearchService;

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private ChunkRepository chunkRepository;

    private final EvaluationQuerySetLoader loader = new EvaluationQuerySetLoader();
    private final SearchQualityEvaluator evaluator = new SearchQualityEvaluator();

    private List<QueryEvaluation> structuredEvaluations;

    @Test
    @Order(1)
    void 정형_12개_검색_품질_측정() {
        structuredEvaluations = loadAndEvaluate(STRUCTURED_QUERY_SET);

        printPerQueryTable("정형 12개", structuredEvaluations);
        printAggregate("전체 12개", structuredEvaluations);
        printAggregate(
                "정확일치 (lexicalMismatch=false)",
                filterByMismatch(structuredEvaluations, false));
        printAggregate(
                "어휘 미스매치 (lexicalMismatch=true)",
                filterByMismatch(structuredEvaluations, true));
        printAggregate(
                "고유명사형 (KEYWORD_FRIENDLY)",
                structuredEvaluations.stream()
                        .filter(e -> e.query().category() == QueryCategory.KEYWORD_FRIENDLY)
                        .toList());
        printAggregate(
                "사건형 (EVENT)",
                structuredEvaluations.stream()
                        .filter(e -> e.query().category() == QueryCategory.EVENT)
                        .toList());
    }

    @Test
    @Order(2)
    void 서술형_6개_검색_품질_측정_및_정형과_비교() {
        List<QueryEvaluation> narrativeEvaluations = loadAndEvaluate(NARRATIVE_QUERY_SET);

        printPerQueryTable("서술형 6개", narrativeEvaluations);
        printAggregate("전체 6개", narrativeEvaluations);
        printAggregate("정확일치 (lexicalMismatch=false)", filterByMismatch(narrativeEvaluations, false));
        printAggregate("어휘 미스매치 (lexicalMismatch=true)", filterByMismatch(narrativeEvaluations, true));

        for (String theme : distinctThemes(narrativeEvaluations)) {
            printAggregate(
                    "테마: " + theme,
                    narrativeEvaluations.stream().filter(e -> theme.equals(e.query().theme())).toList());
        }

        if (structuredEvaluations == null) {
            System.out.println();
            System.out.println("=== 정형 벤치마크 결과가 없어 비교표를 생략합니다"
                    + "(정형_12개_검색_품질_측정()을 먼저 실행해야 함) ===");
            return;
        }
        printComparisonTable("정형 12개", structuredEvaluations, "서술형 6개", narrativeEvaluations);
    }

    private List<QueryEvaluation> filterByMismatch(List<QueryEvaluation> evaluations, boolean mismatch) {
        return evaluations.stream().filter(e -> e.query().lexicalMismatch() == mismatch).toList();
    }

    private Set<String> distinctThemes(List<QueryEvaluation> evaluations) {
        Set<String> themes = new LinkedHashSet<>();
        evaluations.forEach(e -> {
            if (e.query().theme() != null) {
                themes.add(e.query().theme());
            }
        });
        return themes;
    }

    private List<QueryEvaluation> loadAndEvaluate(String resourcePath) {
        List<GroundTruthQuery> queries = loader.load(resourcePath);
        List<QueryEvaluation> evaluations = new ArrayList<>();
        for (GroundTruthQuery q : queries) {
            evaluations.add(evaluateOne(q));
        }
        return evaluations;
    }

    private QueryEvaluation evaluateOne(GroundTruthQuery q) {
        List<KeywordMatch> keywordMatches = keywordSearchService.search(q.query(), CANDIDATE_SIZE);
        List<VectorMatch> vectorMatches = vectorSearchService.search(q.query(), CANDIDATE_SIZE);
        List<RankedChunk> hybridResults = hybridSearchService.search(q.query(), CANDIDATE_SIZE);

        Map<Long, ChunkDisclosureRef> refById = resolveRefs(keywordMatches, vectorMatches);

        List<RankedItem> keywordItems =
                keywordMatches.stream().map(m -> toRankedItem(m.chunkId(), refById)).toList();
        List<RankedItem> vectorItems =
                vectorMatches.stream().map(m -> toRankedItem(m.chunkId(), refById)).toList();
        List<RankedItem> hybridItems = hybridResults.stream()
                .map(r -> new RankedItem(r.chunkId(), r.rceptNo()))
                .toList();

        EvaluationResult keywordResult = evaluator.evaluate(keywordItems, q.targetRceptNo());
        EvaluationResult vectorResult = evaluator.evaluate(vectorItems, q.targetRceptNo());
        EvaluationResult hybridResult = evaluator.evaluate(hybridItems, q.targetRceptNo());

        boolean dedupSuppressed = hybridResults.stream()
                .anyMatch(r -> !r.rceptNo().equals(q.targetRceptNo())
                        && r.duplicateSources().stream()
                                .anyMatch(dup -> dup.rceptNo().equals(q.targetRceptNo())));

        List<ChunkSource> targetChunkSources = chunkRepository.findDistinctChunkSourcesByRceptNo(q.targetRceptNo());

        return new QueryEvaluation(
                q, keywordResult, vectorResult, hybridResult, targetChunkSources, dedupSuppressed);
    }

    // 키워드/벡터 결과에 등장한 chunkId 전체를 한 번에 rcept_no로 해석한다(N+1 방지).
    private Map<Long, ChunkDisclosureRef> resolveRefs(List<KeywordMatch> keywordMatches, List<VectorMatch> vectorMatches) {
        List<Long> ids = new ArrayList<>();
        keywordMatches.forEach(m -> ids.add(m.chunkId()));
        vectorMatches.forEach(m -> ids.add(m.chunkId()));

        Map<Long, ChunkDisclosureRef> refById = new HashMap<>();
        chunkRepository.findDisclosureRefsByChunkIds(ids).forEach(ref -> refById.put(ref.getChunkId(), ref));
        return refById;
    }

    private RankedItem toRankedItem(Long chunkId, Map<Long, ChunkDisclosureRef> refById) {
        ChunkDisclosureRef ref = refById.get(chunkId);
        return new RankedItem(chunkId, ref == null ? "UNKNOWN" : ref.getRceptNo());
    }

    private void printPerQueryTable(String label, List<QueryEvaluation> evaluations) {
        System.out.println();
        System.out.println("=== [" + label + "] 질의별 3방식 결과 (정답 공시가 처음 나온 순위, '-'는 top-50 밖) ===");
        System.out.printf(
                "%-4s %-18s %-6s %-30s %-10s %-10s %-10s %-10s%n",
                "id", "category/theme", "mismatch", "query", "keyword", "vector", "hybrid", "dedup밀림");
        for (QueryEvaluation e : evaluations) {
            System.out.printf(
                    "%-4s %-18s %-6s %-30s %-10s %-10s %-10s %-10s%n",
                    e.query().id(),
                    categoryOrThemeLabel(e.query()),
                    e.query().lexicalMismatch() ? "O" : "-",
                    truncate(e.query().query(), 28),
                    rankLabel(e.keywordResult()),
                    rankLabel(e.vectorResult()),
                    rankLabel(e.hybridResult()),
                    e.hybridDedupSuppressed() ? "O" : "-");
            System.out.printf("     targetChunkSource=%s%n", e.targetChunkSources());
        }
    }

    private String categoryOrThemeLabel(GroundTruthQuery q) {
        if (q.category() != null) {
            return q.category().toString();
        }
        return q.theme() != null ? q.theme() : "-";
    }

    private void printAggregate(String label, List<QueryEvaluation> group) {
        System.out.println();
        System.out.println("=== 집계: " + label + " (n=" + group.size() + ") ===");
        if (group.isEmpty()) {
            System.out.println("  (해당 그룹 질의 없음)");
            return;
        }
        System.out.printf(
                "%-10s %-12s %-12s %-12s %-8s%n", "system", "Recall@5", "Recall@10", "Recall@20", "MRR");
        printAggregateRow("keyword", group, QueryEvaluation::keywordResult);
        printAggregateRow("vector", group, QueryEvaluation::vectorResult);
        printAggregateRow("hybrid", group, QueryEvaluation::hybridResult);
    }

    private void printAggregateRow(
            String system, List<QueryEvaluation> group, Function<QueryEvaluation, EvaluationResult> extractor) {
        AggregateStats stats = computeAggregate(group, extractor);
        System.out.printf(
                "%-10s %-12.3f %-12.3f %-12.3f %-8.3f%n",
                system, stats.recallAt5(), stats.recallAt10(), stats.recallAt20(), stats.mrr());
    }

    private AggregateStats computeAggregate(
            List<QueryEvaluation> group, Function<QueryEvaluation, EvaluationResult> extractor) {
        int n = group.size();
        double recallAt5 = group.stream().filter(e -> extractor.apply(e).recallAt5()).count() / (double) n;
        double recallAt10 = group.stream().filter(e -> extractor.apply(e).recallAt10()).count() / (double) n;
        double recallAt20 = group.stream().filter(e -> extractor.apply(e).recallAt20()).count() / (double) n;
        double mrr = group.stream().mapToDouble(e -> extractor.apply(e).mrr()).average().orElse(0.0);
        return new AggregateStats(recallAt5, recallAt10, recallAt20, mrr);
    }

    private void printComparisonTable(
            String label1, List<QueryEvaluation> group1, String label2, List<QueryEvaluation> group2) {
        System.out.println();
        System.out.println("=== 대조표: " + label1 + " vs " + label2 + " (Recall@20 / MRR) ===");
        System.out.printf("%-10s %-22s %-22s%n", "system", label1, label2);
        printComparisonRow("keyword", group1, group2, QueryEvaluation::keywordResult);
        printComparisonRow("vector", group1, group2, QueryEvaluation::vectorResult);
        printComparisonRow("hybrid", group1, group2, QueryEvaluation::hybridResult);
    }

    private void printComparisonRow(
            String system,
            List<QueryEvaluation> group1,
            List<QueryEvaluation> group2,
            Function<QueryEvaluation, EvaluationResult> extractor) {
        AggregateStats stats1 = computeAggregate(group1, extractor);
        AggregateStats stats2 = computeAggregate(group2, extractor);
        System.out.printf(
                "%-10s %-22s %-22s%n",
                system,
                String.format("%.3f / %.3f", stats1.recallAt20(), stats1.mrr()),
                String.format("%.3f / %.3f", stats2.recallAt20(), stats2.mrr()));
    }

    private String rankLabel(EvaluationResult result) {
        return result.firstHitRank() == -1 ? "-" : String.valueOf(result.firstHitRank());
    }

    private String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "..";
    }

    private record AggregateStats(double recallAt5, double recallAt10, double recallAt20, double mrr) {
    }

    private record QueryEvaluation(
            GroundTruthQuery query,
            EvaluationResult keywordResult,
            EvaluationResult vectorResult,
            EvaluationResult hybridResult,
            List<ChunkSource> targetChunkSources,
            boolean hybridDedupSuppressed) {
    }
}
