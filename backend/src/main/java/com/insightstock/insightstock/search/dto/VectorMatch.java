package com.insightstock.insightstock.search.dto;

// pgvector 코사인 유사도 검색 결과 한 건. rank는 1부터 시작(거리 오름차순 = 유사도 내림차순).
// distance는 코사인 거리(<=>) 원값 - 0에 가까울수록 유사.
public record VectorMatch(Long chunkId, int rank, double distance) {
}
