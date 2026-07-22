package com.insightstock.insightstock.search.dto;

// ElasticSearch BM25 검색 결과 한 건. rank는 1부터 시작(ES가 이미 _score 내림차순으로 반환).
public record KeywordMatch(Long chunkId, int rank, float score) {
}
