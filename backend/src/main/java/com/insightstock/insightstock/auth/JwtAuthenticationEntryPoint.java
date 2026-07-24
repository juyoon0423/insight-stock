package com.insightstock.insightstock.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightstock.insightstock.api.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

// 인증이 필요한 경로에 토큰 없이(또는 무효한 토큰으로) 접근했을 때 401을 반환한다.
// GlobalExceptionHandler와 같은 ProblemDetail + errorCode 형식을 쓴다.
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        ProblemDetails.write(
                response, objectMapper, HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "로그인이 필요합니다.", request);
    }
}
