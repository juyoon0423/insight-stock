package com.insightstock.insightstock.search.dto;

// RRF 융합 결과 한 건. keywordRank/vectorRank는 해당 검색에 등장 안 했으면 null -
// HybridSearchService가 최종 결과에 "어느 쪽에서 몇 위였는지"를 그대로 보여주는 데 쓴다.
public record RrfScore(Long chunkId, double score, Integer keywordRank, Integer vectorRank) {
}
