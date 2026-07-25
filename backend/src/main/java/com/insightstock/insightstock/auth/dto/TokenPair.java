package com.insightstock.insightstock.auth.dto;

import com.insightstock.insightstock.domain.User;

public record TokenPair(String accessToken, String refreshToken, User user) {
}
