package com.insightstock.insightstock.domain;

/**
 * 청크가 어떤 방식으로 만들어졌는지 구분한다. section_title의 null 여부만으로는
 * "작아서 안 쪼갠 문서"와 "구조를 몰라 슬라이딩 윈도우만 적용한 문서"를 구분할 수
 * 없어서 별도로 둔다 (검색 품질을 방식별로 비교하기 위함).
 */
public enum ChunkSource {
    // char_length가 임계치 미만이라 통째로 1개 청크로 저장
    WHOLE_DOCUMENT,
    // raw_content에 [[SECTION]] 마커가 있어 구조 기반으로 분할 (섹션 내부 슬라이딩 윈도우 포함)
    SECTION,
    // 마커가 없어(XML 정제가 정규식 폴백을 탄 경우) 구조를 모른 채 길이 기준으로만 분할
    SLIDING_WINDOW
}
