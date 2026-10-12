package com.insightstock.insightstock.api.dto;

import com.insightstock.insightstock.api.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "이메일을 입력해주세요.") @Email(message = "올바른 이메일 형식이 아닙니다.") String email,
        @NotBlank(message = "비밀번호를 입력해주세요.")
                @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
                // BCrypt는 72바이트까지만 받는다 - 넘으면 BCryptPasswordEncoder가
                // IllegalArgumentException을 던져 500이 된다. 글자 수(@Size)가 아니라
                // 바이트로 막아야 한다(한글은 글자당 3바이트).
                @MaxUtf8Bytes(value = 72, message = "비밀번호가 너무 깁니다(영문 기준 72자, 한글 기준 24자 이하).")
                String password,
        @NotBlank(message = "닉네임을 입력해주세요.")
                @Size(min = 2, max = 20, message = "닉네임은 2~20자여야 합니다.")
                String nickname) {

    // 앞뒤 공백이 있으면 @Email 정규식 자체가 실패해서 AuthService의 정규화(trim+lowercase)
    // 전에 400으로 막혀버린다 - 검증 시점에 이미 trim된 값을 보게 compact constructor에서 처리.
    public SignupRequest {
        email = email == null ? null : email.strip();
    }
}
