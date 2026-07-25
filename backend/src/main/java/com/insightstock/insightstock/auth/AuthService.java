package com.insightstock.insightstock.auth;

import com.insightstock.insightstock.auth.dto.TokenPair;
import com.insightstock.insightstock.domain.User;
import com.insightstock.insightstock.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(
            UserRepository userRepository, PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Transactional
    public TokenPair signup(String rawEmail, String rawPassword, String nickname) {
        String email = normalizeEmail(rawEmail);
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setNickname(nickname);
        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // 동시 가입 경합 대비 - existsByEmail 통과 후에도 uk_users_email 제약이 걸릴 수 있다.
            throw new DuplicateEmailException();
        }
        return issueTokens(user);
    }

    public TokenPair login(String rawEmail, String rawPassword) {
        String email = normalizeEmail(rawEmail);
        User user = userRepository.findByEmail(email).orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return issueTokens(user);
    }

    // 엄격 모드: access token 만료 시에만 이 메서드를 타고, refresh token 자체는 재발급하지
    // 않는다(Stateless라 rotate해도 탈취 탐지 이점이 없어 복잡도만 늘어남 - 3단계 설계 논의 참고).
    public String refresh(String refreshToken) {
        Long userId;
        try {
            if (!jwtTokenProvider.isRefreshToken(refreshToken)) {
                throw new InvalidRefreshTokenException();
            }
            userId = jwtTokenProvider.getUserId(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidRefreshTokenException();
        }
        if (!userRepository.existsById(userId)) {
            throw new InvalidRefreshTokenException();
        }
        return jwtTokenProvider.generateAccessToken(userId);
    }

    private TokenPair issueTokens(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());
        return new TokenPair(accessToken, refreshToken, user);
    }

    // 대소문자/앞뒤 공백만 다른 같은 주소가 다른 계정으로 취급되지 않도록 한다. local part(예:
    // dot/plus 무시)까지 건드리면 실제로 다른 주소를 같다고 오판할 위험이 있어 보수적으로 이 정도만.
    private static String normalizeEmail(String raw) {
        return raw.strip().toLowerCase(Locale.ROOT);
    }
}
