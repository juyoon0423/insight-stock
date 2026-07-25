package com.insightstock.insightstock.api.dto;

import java.util.List;

public record CompanySearchResponse(List<CompanySummary> companies) {
}
