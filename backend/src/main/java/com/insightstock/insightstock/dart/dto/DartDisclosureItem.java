package com.insightstock.insightstock.dart.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

// DART list API 응답의 개별 공시 항목. 필드명은 DART 원본 스키마(snake_case)를 그대로 따른다.
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DartDisclosureItem(
        String corpCode,
        String corpName,
        String stockCode,
        String corpCls,
        String reportNm,
        String rceptNo,
        String flrNm,
        String rceptDt,
        String rm) {
}
