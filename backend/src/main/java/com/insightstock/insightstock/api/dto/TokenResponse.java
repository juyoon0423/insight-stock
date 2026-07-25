package com.insightstock.insightstock.api.dto;

public record TokenResponse(String accessToken, String refreshToken, UserSummary user) {
}
