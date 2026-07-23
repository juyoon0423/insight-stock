package com.insightstock.insightstock.api.dto;

import com.insightstock.insightstock.search.dto.DuplicateDisclosureRef;
import java.util.List;

// rrfScore/keywordRank/vectorRank를 그대로 노출한다 - 이 프로젝트의 핵심 가치인
// 하이브리드 검색(BM25+벡터+RRF)이 실제로 어떻게 순위를 매기는지 프론트에서
// 시각적으로 보여줄 수 있게 하기 위함이다.
//
// duplicateSources: hybrid-search.dedup.enabled가 true일 때만 채워진다. content가
// 완전히 동일해 최종 결과에서는 대표 1건만 남고 빠진 다른 공시들의 출처 - "관련 공시
// N건"으로 표시하되 출처 자체는 감추지 않기 위함(dart-findings.md 참고).
public record SearchResultItem(
        String corpName,
        String reportNm,
        String sectionTitle,
        String content,
        double rrfScore,
        Integer keywordRank,
        Integer vectorRank,
        List<DuplicateDisclosureRef> duplicateSources) {
}
