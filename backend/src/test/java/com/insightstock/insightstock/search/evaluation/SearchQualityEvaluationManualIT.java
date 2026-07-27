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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 검색 품질 정식 측정 - docs/progress.md "검색 품질 측정" 항목, 질의 세트는
// src/test/resources/evaluation/search-quality-queries.json(12개, 정답 corp_code+
// report_nm으로 유일성 확인된 것만 채택 - JSON _readme 참고).
//
// 후보 풀(candidate=50)을 키워드/벡터/하이브리드 세 방식 동일하게 두고 각각 호출한다.
// 하이브리드는 HybridSearchService.search()를 그대로 호출해 RRF+dedup까지 포함한
// 실제 production 경로를 그대로 측정한다(순수 RRF만 떼어보는 게 아니라 "지금 배포된
// 세 옵션"을 비교하는 게 목적). 벡터 임베딩은 vectorSearchService 단독 호출 1회 +
// hybridSearchService 내부 1회, 질의당 2회 발생 - 질의 12개 × 2 = 24회, 질의당
// 수십 토큰이라 비용은 사실상 0(@Tag("manual")로 기본 test에서는 제외).
@Tag("manual")
@SpringBootTest
class SearchQualityEvaluationManualIT {

    private static final int CANDIDATE_SIZE = 50;
    private static final String QUERY_SET_RESOURCE = "evaluation/search-quality-queries.json";

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

    @Test
    void 검색_품질_측정() {
        List<GroundTruthQuery> queries = loader.load(QUERY_SET_RESOURCE);
        List<QueryEvaluation> evaluations = new ArrayList<>();

        for (GroundTruthQuery q : queries) {
            evaluations.add(evaluateOne(q));
        }

        printPerQueryTable(evaluations);
        printAggregate("전체 12개", evaluations);
        printAggregate(
                "정확일치 8개 (lexicalMismatch=false)",
                evaluations.stream().filter(e -> !e.query().lexicalMismatch()).toList());
        printAggregate(
                "어휘 미스매치 4개 (lexicalMismatch=true)",
                evaluations.stream().filter(e -> e.query().lexicalMismatch()).toList());
        printAggregate(
                "고유명사형 (KEYWORD_FRIENDLY)",
                evaluations.stream().filter(e -> e.query().category() == QueryCategory.KEYWORD_FRIENDLY).toList());
        printAggregate(
                "사건형 (EVENT)",
                evaluations.stream().filter(e -> e.query().category() == QueryCategory.EVENT).toList());
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

    private void printPerQueryTable(List<QueryEvaluation> evaluations) {
        System.out.println();
        System.out.println("=== 질의별 3방식 결과 (정답 공시가 처음 나온 순위, '-'는 top-50 밖) ===");
        System.out.printf(
                "%-4s %-18s %-6s %-30s %-10s %-10s %-10s %-10s%n",
                "id", "category", "mismatch", "query", "keyword", "vector", "hybrid", "dedup밀림");
        for (QueryEvaluation e : evaluations) {
            System.out.printf(
                    "%-4s %-18s %-6s %-30s %-10s %-10s %-10s %-10s%n",
                    e.query().id(),
                    e.query().category(),
                    e.query().lexicalMismatch() ? "O" : "-",
                    truncate(e.query().query(), 28),
                    rankLabel(e.keywordResult()),
                    rankLabel(e.vectorResult()),
                    rankLabel(e.hybridResult()),
                    e.hybridDedupSuppressed() ? "O" : "-");
            System.out.printf(
                    "     targetChunkSource=%s%n", e.targetChunkSources());
        }
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
            String system, List<QueryEvaluation> group, java.util.function.Function<QueryEvaluation, EvaluationResult> extractor) {
        int n = group.size();
        double recallAt5 = group.stream().filter(e -> extractor.apply(e).recallAt5()).count() / (double) n;
        double recallAt10 = group.stream().filter(e -> extractor.apply(e).recallAt10()).count() / (double) n;
        double recallAt20 = group.stream().filter(e -> extractor.apply(e).recallAt20()).count() / (double) n;
        double mrr = group.stream().mapToDouble(e -> extractor.apply(e).mrr()).average().orElse(0.0);
        System.out.printf("%-10s %-12.3f %-12.3f %-12.3f %-8.3f%n", system, recallAt5, recallAt10, recallAt20, mrr);
    }

    private String rankLabel(EvaluationResult result) {
        return result.firstHitRank() == -1 ? "-" : String.valueOf(result.firstHitRank());
    }

    private String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "..";
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
