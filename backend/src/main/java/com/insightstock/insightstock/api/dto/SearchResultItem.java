package com.insightstock.insightstock.api.dto;

// rrfScore/keywordRank/vectorRank를 그대로 노출한다 - 이 프로젝트의 핵심 가치인
// 하이브리드 검색(BM25+벡터+RRF)이 실제로 어떻게 순위를 매기는지 프론트에서
// 시각적으로 보여줄 수 있게 하기 위함이다.
public record SearchResultItem(
        String corpName,
        String reportNm,
        String sectionTitle,
        String content,
        double rrfScore,
        Integer keywordRank,
        Integer vectorRank) {
}
