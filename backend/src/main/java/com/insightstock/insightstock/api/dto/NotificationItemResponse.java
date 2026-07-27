package com.insightstock.insightstock.api.dto;

import java.time.Instant;

// rceptNo/dartUrl은 rag.dto.SourceRef와 같은 필드명 - 클릭 시 DART 원문으로 이동하는
// 프론트 링크(SourceCard와 동일 패턴)에 그대로 쓴다.
public record NotificationItemResponse(
        Long id,
        CompanySummary company,
        String reportNm,
        String rceptNo,
        String dartUrl,
        Instant createdAt,
        boolean isRead) {
}
