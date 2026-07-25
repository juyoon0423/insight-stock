package com.insightstock.insightstock.api.dto;

import java.util.List;

public record WatchlistListResponse(List<WatchlistItemResponse> watchlist) {
}
