package com.insightstock.insightstock.search.dto;

// content가 완전히 동일한 다른 청크의 출처 정보. HybridSearchService의 중복 제거가
// 대표 청크 하나만 최종 결과에 남기면서, 나머지 출처를 잃지 않고 여기 보존한다
// (dart-findings.md 참고 - 일괄신고/다회차 발행 관행으로 같은 문구가 여러 공시에
// 반복 등장하는 게 흔함).
public record DuplicateDisclosureRef(
        String rceptNo, String corpName, String reportNm, String sectionTitle, String dartUrl) {
}
