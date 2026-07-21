package com.insightstock.insightstock.dart.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DartListResponse(
        String status,
        String message,
        Integer pageNo,
        Integer pageCount,
        Integer totalCount,
        Integer totalPage,
        List<DartDisclosureItem> list) {
}
