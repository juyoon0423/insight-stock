package com.insightstock.insightstock.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightstock.insightstock.api.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

// 인증은 됐지만 권한이 없는 접근(지금은 역할 구분이 없어 실사용 경로는 아직 없지만,
// authorizeHttpRequests가 이 핸들러를 요구하므로 GlobalExceptionHandler와 같은 형식으로
// 맞춰둔다 - 나중에 역할 기반 접근 제어가 생기면 바로 쓰인다).
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JwtAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        ProblemDetails.write(
                response, objectMapper, HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다.", request);
    }
}
