package com.insightstock.insightstock.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.insightstock.insightstock.auth.dto.TokenPair;
import com.insightstock.insightstock.domain.User;
import com.insightstock.insightstock.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

// 저장소만 대역으로 바꾸고 비밀번호 해시와 토큰 발급은 실제 구현을 쓴다(DB/컨텍스트 없음).
class AuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    // 테스트가 느려지지 않도록 BCrypt 강도만 최저(4)로 낮췄다 - 알고리즘은 운영과 같다.
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final JwtTokenProvider jwtTokenProvider =
            new JwtTokenProvider("test-secret-test-secret-test-secret-0123456789", 60_000, 120_000);
    private final AuthService authService = new AuthService(userRepository, passwordEncoder, jwtTokenProvider);

    // ---- 회원가입 ----

    @Test
    void 가입하면_이메일을_정규화하고_비밀번호는_해시로만_저장한다() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        TokenPair tokens = authService.signup("  User@Example.COM ", "password123", "닉네임");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("user@example.com");
        assertThat(saved.getPasswordHash()).isNotEqualTo("password123");
        assertThat(passwordEncoder.matches("password123", saved.getPasswordHash())).isTrue();
        assertThat(jwtTokenProvider.getUserId(tokens.accessToken())).isEqualTo(1L);
        assertThat(jwtTokenProvider.isRefreshToken(tokens.refreshToken())).isTrue();
    }

    @Test
    void 이미_가입된_이메일이면_저장하지_않고_거부한다() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.signup("user@example.com", "password123", "닉네임"))
                .isInstanceOf(DuplicateEmailException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void 동시_가입으로_유니크_제약에_걸려도_중복_이메일로_응답한다() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("uk_users_email"));

        assertThatThrownBy(() -> authService.signup("user@example.com", "password123", "닉네임"))
                .isInstanceOf(DuplicateEmailException.class);
    }

    // ---- 로그인 ----

    @Test
    void 비밀번호가_맞으면_토큰을_발급한다() {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user(7L, "password123")));

        TokenPair tokens = authService.login(" USER@example.com", "password123");

        assertThat(jwtTokenProvider.getUserId(tokens.accessToken())).isEqualTo(7L);
        assertThat(jwtTokenProvider.isRefreshToken(tokens.accessToken())).isFalse();
    }

    @Test
    void 비밀번호가_틀리면_거부한다() {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user(7L, "password123")));

        assertThatThrownBy(() -> authService.login("user@example.com", "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void 없는_이메일도_틀린_비밀번호와_같은_예외로_거부한다() {
        // 이메일 존재 여부를 응답으로 구분할 수 없어야 한다(계정 탐색 방지).
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("nobody@example.com", "password123"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    // ---- 토큰 갱신 ----

    @Test
    void refresh_토큰으로_새_access_토큰을_받는다() {
        when(userRepository.existsById(7L)).thenReturn(true);

        String accessToken = authService.refresh(jwtTokenProvider.generateRefreshToken(7L));

        assertThat(jwtTokenProvider.getUserId(accessToken)).isEqualTo(7L);
        assertThat(jwtTokenProvider.isRefreshToken(accessToken)).isFalse();
    }

    @Test
    void access_토큰으로는_갱신할_수_없다() {
        assertThatThrownBy(() -> authService.refresh(jwtTokenProvider.generateAccessToken(7L)))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void 위조되거나_형식이_틀린_토큰으로는_갱신할_수_없다() {
        assertThatThrownBy(() -> authService.refresh("not-a-jwt")).isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void 탈퇴_등으로_사용자가_없어졌으면_갱신할_수_없다() {
        when(userRepository.existsById(7L)).thenReturn(false);

        assertThatThrownBy(() -> authService.refresh(jwtTokenProvider.generateRefreshToken(7L)))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    private User user(Long id, String rawPassword) {
        User user = new User();
        user.setId(id);
        user.setEmail("user@example.com");
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setNickname("닉네임");
        return user;
    }
}
