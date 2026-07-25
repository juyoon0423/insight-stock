package com.insightstock.insightstock.api.dto;

import java.time.Instant;

public record WatchlistItemResponse(Long id, CompanySummary company, Instant createdAt) {
}
