package com.insightstock.insightstock.api.dto;

import java.util.List;

public record SearchResponse(String query, List<SearchResultItem> results) {
}
