package com.insightstock.insightstock.search.dto;

import com.insightstock.insightstock.domain.ChunkSource;

// 하이브리드 검색 최종 결과 한 건. keywordRank/vectorRank를 그대로 남겨서 이 청크가
// 어느 검색에서(또는 둘 다에서) 얼마나 좋은 순위였는지 결과만 보고도 알 수 있게 한다.
public record RankedChunk(
        Long chunkId,
        double rrfScore,
        Integer keywordRank,
        Integer vectorRank,
        String rceptNo,
        String corpName,
        String reportNm,
        String sectionTitle,
        ChunkSource chunkSource,
        String content) {
}
