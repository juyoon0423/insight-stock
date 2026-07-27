package com.insightstock.insightstock.search.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.insightstock.insightstock.search.evaluation.SearchQualityEvaluator.EvaluationResult;
import com.insightstock.insightstock.search.evaluation.SearchQualityEvaluator.RankedItem;
import java.util.List;
import org.junit.jupiter.api.Test;

// SearchQualityEvaluator는 DB/ES/API 의존성이 없는 순수 함수라 실제 인프라 없이
// Recall@K/MRR 공식 자체를 검증한다.
class SearchQualityEvaluatorTest {

    private static final double DELTA = 1e-9;
    private static final String TARGET = "TARGET_RCEPT_NO";

    private final SearchQualityEvaluator evaluator = new SearchQualityEvaluator();

    @Test
    void 정답이_1위면_모든_recall이_참이고_MRR은_1이다() {
        List<RankedItem> results = List.of(
                new RankedItem(1L, TARGET),
                new RankedItem(2L, "OTHER"));

        EvaluationResult result = evaluator.evaluate(results, TARGET);

        assertEquals(1, result.firstHitRank());
        assertEquals(1.0, result.mrr(), DELTA);
        assertTrue(result.recallAt5());
        assertTrue(result.recallAt10());
        assertTrue(result.recallAt20());
    }

    @Test
    void 정답이_15위면_recallAt20만_참이고_MRR은_1_15다() {
        List<RankedItem> results = buildResultsWithTargetAt(15, 20);

        EvaluationResult result = evaluator.evaluate(results, TARGET);

        assertEquals(15, result.firstHitRank());
        assertEquals(1.0 / 15, result.mrr(), DELTA);
        assertFalse(result.recallAt5());
        assertFalse(result.recallAt10());
        assertTrue(result.recallAt20());
    }

    @Test
    void top20_안에서_못_찾으면_모든_recall이_거짓이고_MRR은_0이다() {
        List<RankedItem> results = buildResultsWithTargetAt(-1, 20); // 정답이 아예 없는 20개

        EvaluationResult result = evaluator.evaluate(results, TARGET);

        assertEquals(-1, result.firstHitRank());
        assertEquals(0.0, result.mrr(), DELTA);
        assertFalse(result.recallAt5());
        assertFalse(result.recallAt10());
        assertFalse(result.recallAt20());
        assertEquals(0, result.hitCountInTop20());
    }

    @Test
    void 정답_청크가_여러_개면_첫_등장만_MRR에_반영되고_hitCount는_전부_센다() {
        // 3위와 9위에 둘 다 정답 공시의 청크가 있는 경우 - MRR은 3위 기준이어야 하고,
        // 9위가 더 있다고 recall/MRR이 더 좋아지지도 나빠지지도 않는다.
        List<RankedItem> results = new java.util.ArrayList<>(buildResultsWithTargetAt(3, 10));
        results.set(8, new RankedItem(900L, TARGET)); // 9위(index 8)에도 정답 삽입

        EvaluationResult result = evaluator.evaluate(results, TARGET);

        assertEquals(3, result.firstHitRank(), "가장 먼저 나온 순위를 써야 한다");
        assertEquals(1.0 / 3, result.mrr(), DELTA);
        assertEquals(2, result.hitCountInTop20(), "참고용 hitCount는 여러 개를 전부 센다");
    }

    private List<RankedItem> buildResultsWithTargetAt(int targetRank, int totalSize) {
        List<RankedItem> results = new java.util.ArrayList<>();
        for (int i = 1; i <= totalSize; i++) {
            results.add(new RankedItem(i, i == targetRank ? TARGET : "OTHER_" + i));
        }
        return results;
    }
}
