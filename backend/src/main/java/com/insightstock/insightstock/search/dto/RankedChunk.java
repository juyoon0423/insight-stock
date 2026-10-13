package com.insightstock.insightstock.search.dto;

import com.insightstock.insightstock.domain.ChunkSource;
import java.util.List;

// 하이브리드 검색 최종 결과 한 건. keywordRank/vectorRank를 그대로 남겨서 이 청크가
// 어느 검색에서(또는 둘 다에서) 얼마나 좋은 순위였는지 결과만 보고도 알 수 있게 한다.
//
// duplicateSources: hybrid-search.dedup.enabled가 true일 때, content가 이 청크와
// 완전히 동일했던 다른 청크들의 출처(대표로 뽑히지 못해 최종 목록에서는 빠진 것들).
// 비활성 상태거나 중복이 없으면 빈 리스트.
//
// rceptDt: 공시 접수일자(DART가 주는 "yyyyMMdd" 문자열 그대로). 답변 생성 시 컨텍스트에 함께
// 넣어, 같은 사안의 공시가 여러 개일 때 모델이 어느 것이 더 최근인지 알 수 있게 한다.
public record RankedChunk(
        Long chunkId,
        double rrfScore,
        Integer keywordRank,
        Integer vectorRank,
        String rceptNo,
        String rceptDt,
        String corpName,
        String reportNm,
        String sectionTitle,
        ChunkSource chunkSource,
        String content,
        List<DuplicateDisclosureRef> duplicateSources) {
}
