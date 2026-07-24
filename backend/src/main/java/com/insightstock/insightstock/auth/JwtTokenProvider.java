package com.insightstock.insightstock.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 발급자와 검증자가 같은 단일 백엔드라 HS256(대칭키)을 쓴다 - 여러 서비스가 독립적으로
// 검증해야 하는 구조가 아니라면 RS256 비대칭키는 불필요한 복잡도다(3단계 설계 논의 참고).
@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long accessTokenExpiryMs;
    private final long refreshTokenExpiryMs;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiry-ms}") long accessTokenExpiryMs,
            @Value("${jwt.refresh-token-expiry-ms}") long refreshTokenExpiryMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiryMs = accessTokenExpiryMs;
        this.refreshTokenExpiryMs = refreshTokenExpiryMs;
    }

    public String generateAccessToken(Long userId) {
        return generateToken(userId, accessTokenExpiryMs, TokenType.ACCESS);
    }

    public String generateRefreshToken(Long userId) {
        return generateToken(userId, refreshTokenExpiryMs, TokenType.REFRESH);
    }

    // 토큰 subject는 이메일이 아니라 userId - 이메일이 나중에 바뀌어도 토큰 발급 로직이
    // 영향받지 않도록 안정적인 식별자를 쓴다.
    public Long getUserId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    public boolean isRefreshToken(String token) {
        return TokenType.REFRESH.name().equals(parseClaims(token).get("type", String.class));
    }

    private String generateToken(Long userId, long expiryMs, TokenType type) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", type.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expiryMs)))
                .signWith(key)
                .compact();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
