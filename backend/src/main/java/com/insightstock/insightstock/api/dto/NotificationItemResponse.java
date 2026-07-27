package com.insightstock.insightstock.api.dto;

import java.time.Instant;

public record NotificationItemResponse(
        Long id, CompanySummary company, String reportNm, Instant createdAt, boolean isRead) {
}
