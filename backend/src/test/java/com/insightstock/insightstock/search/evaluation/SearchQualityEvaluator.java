package com.insightstock.insightstock.search.evaluation;

import java.util.List;

// 순위가 매겨진 결과 목록과 정답 rcept_no 하나를 비교해 Recall@5/10/20과 MRR을 계산한다.
// DB/ES/API 의존성이 없는 순수 함수 - RrfFuser와 같은 이유로 단위 테스트로 공식 자체를
// 검증할 수 있다.
//
// 정답 공시가 여러 청크로 쪼개져 있다는 점(평균 47개/공시)을 어떻게 다루는지가 핵심:
// - Recall@K: OR 판정("top-K 안에 정답 공시의 청크가 하나라도 있으면 1") - 정답 공시가
//   1건뿐이라 분모가 항상 1이므로, 이 정의에서 Recall@K는 수학적으로 Hit@K(Success@K)와
//   같다. 몇 개가 걸렸는지는 세지 않는다.
// - MRR: "가장 먼저(=가장 높은 순위로) 나온" 정답 청크의 순위만 쓴다 - 두 번째, 세 번째
//   정답 청크가 더 있어도 MRR에는 영향이 없다(RAG는 인용 1개면 충분하다는 실사용 패턴과
//   일치, progress.md 참고).
public class SearchQualityEvaluator {

    // 결과 목록 한 건. rank는 1부터 시작(리스트 내 등장 순서 그대로).
    public record RankedItem(long chunkId, String rceptNo) {
    }

    public record EvaluationResult(
            int firstHitRank, // 못 찾았으면 -1
            double mrr,
            boolean recallAt5,
            boolean recallAt10,
            boolean recallAt20,
            int hitCountInTop20) {
    }

    public EvaluationResult evaluate(List<RankedItem> rankedResults, String targetRceptNo) {
        int firstHitRank = -1;
        int hitCountInTop20 = 0;

        for (int i = 0; i < rankedResults.size(); i++) {
            int rank = i + 1;
            if (rankedResults.get(i).rceptNo().equals(targetRceptNo)) {
                if (firstHitRank == -1) {
                    firstHitRank = rank;
                }
                if (rank <= 20) {
                    hitCountInTop20++;
                }
            }
        }

        double mrr = firstHitRank == -1 ? 0.0 : 1.0 / firstHitRank;
        return new EvaluationResult(
                firstHitRank,
                mrr,
                firstHitRank != -1 && firstHitRank <= 5,
                firstHitRank != -1 && firstHitRank <= 10,
                firstHitRank != -1 && firstHitRank <= 20,
                hitCountInTop20);
    }
}
