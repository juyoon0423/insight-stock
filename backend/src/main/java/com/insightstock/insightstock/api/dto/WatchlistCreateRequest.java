package com.insightstock.insightstock.api.dto;

import jakarta.validation.constraints.NotNull;

public record WatchlistCreateRequest(@NotNull(message = "companyId가 필요합니다.") Long companyId) {
}
