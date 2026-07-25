package com.insightstock.insightstock.api;

import com.insightstock.insightstock.api.dto.AccessTokenResponse;
import com.insightstock.insightstock.api.dto.LoginRequest;
import com.insightstock.insightstock.api.dto.RefreshRequest;
import com.insightstock.insightstock.api.dto.SignupRequest;
import com.insightstock.insightstock.api.dto.TokenResponse;
import com.insightstock.insightstock.api.dto.UserSummary;
import com.insightstock.insightstock.auth.AuthService;
import com.insightstock.insightstock.auth.dto.TokenPair;
import com.insightstock.insightstock.domain.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse signup(@Valid @RequestBody SignupRequest request) {
        return toTokenResponse(authService.signup(request.email(), request.password(), request.nickname()));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return toTokenResponse(authService.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    public AccessTokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return new AccessTokenResponse(authService.refresh(request.refreshToken()));
    }

    private TokenResponse toTokenResponse(TokenPair tokenPair) {
        User user = tokenPair.user();
        return new TokenResponse(
                tokenPair.accessToken(),
                tokenPair.refreshToken(),
                new UserSummary(user.getId(), user.getEmail(), user.getNickname()));
    }
}
