package com.insightstock.insightstock.rag.dto;

// 답변에서 실제로 인용된([N]) 출처만 담는다 - 컨텍스트에 넣었지만 모델이 안 쓴 근거까지
// 보여주면 오히려 "이게 왜 여기 있지" 하는 혼란을 준다.
public record SourceRef(String rceptNo, String corpName, String reportNm, String sectionTitle, String dartUrl) {
}
