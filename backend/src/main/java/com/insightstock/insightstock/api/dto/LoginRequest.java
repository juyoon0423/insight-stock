package com.insightstock.insightstock.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "이메일을 입력해주세요.") @Email(message = "올바른 이메일 형식이 아닙니다.") String email,
        @NotBlank(message = "비밀번호를 입력해주세요.") String password) {

    // SignupRequest와 같은 이유 - 앞뒤 공백이 있으면 @Email 검증이 정규화 전에 막아버린다.
    public LoginRequest {
        email = email == null ? null : email.strip();
    }
}
