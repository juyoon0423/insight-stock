package com.insightstock.insightstock.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

// 토큰 발급/검증은 인증 전체가 기대는 바닥이라 순수 단위 테스트로 고정한다(DB/컨텍스트 없음).
class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-0123456789";
    private static final String OTHER_SECRET = "other-secret-other-secret-other-secret-9876543210";

    private final JwtTokenProvider provider = new JwtTokenProvider(SECRET, 60_000, 120_000);

    @Test
    void access_토큰에서_userId를_꺼낼_수_있고_refresh_토큰이_아니다() {
        String token = provider.generateAccessToken(42L);

        assertThat(provider.getUserId(token)).isEqualTo(42L);
        assertThat(provider.isRefreshToken(token)).isFalse();
    }

    @Test
    void refresh_토큰은_type으로_구분된다() {
        String token = provider.generateRefreshToken(42L);

        assertThat(provider.getUserId(token)).isEqualTo(42L);
        assertThat(provider.isRefreshToken(token)).isTrue();
    }

    @Test
    void 만료된_토큰은_거부한다() {
        JwtTokenProvider alreadyExpired = new JwtTokenProvider(SECRET, -1_000, -1_000);
        String token = alreadyExpired.generateAccessToken(42L);

        assertThatThrownBy(() -> provider.getUserId(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void 다른_키로_서명한_토큰은_거부한다() {
        String forged = new JwtTokenProvider(OTHER_SECRET, 60_000, 120_000).generateAccessToken(42L);

        assertThatThrownBy(() -> provider.getUserId(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void 내용을_바꾼_토큰은_거부한다() {
        String token = provider.generateAccessToken(42L);
        String[] parts = token.split("\\.");
        // 서명은 그대로 두고 payload만 다른 사용자의 것으로 바꿔치기한다.
        String otherPayload = provider.generateAccessToken(43L).split("\\.")[1];
        String tampered = parts[0] + "." + otherPayload + "." + parts[2];

        assertThatThrownBy(() -> provider.getUserId(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void 토큰_형식이_아닌_문자열은_거부한다() {
        assertThatThrownBy(() -> provider.getUserId("not-a-jwt")).isInstanceOf(JwtException.class);
    }
}
