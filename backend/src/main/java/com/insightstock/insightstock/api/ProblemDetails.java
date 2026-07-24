package com.insightstock.insightstock.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

// GlobalExceptionHandler(컨트롤러 예외)와 Spring Security 필터(인증/인가 예외 - 컨트롤러
// 밖에서 발생해 @RestControllerAdvice가 못 잡음)가 같은 RFC 7807 + errorCode 형식을
// 쓰도록 생성 로직을 공유한다.
public final class ProblemDetails {

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatus status, String errorCode, String detail, HttpServletRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("errorCode", errorCode);
        return problemDetail;
    }

    // 필터 단계는 DispatcherServlet 이전이라 @RestControllerAdvice의 자동 직렬화 혜택을
    // 못 받는다 - 응답 작성까지 직접 해야 한다. getWriter()는 컨테이너 기본 인코딩
    // (ISO-8859-1)을 타 한글이 깨지므로, Jackson이 UTF-8로 직접 쓰는 OutputStream을 쓴다.
    public static void write(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            HttpStatus status,
            String errorCode,
            String detail,
            HttpServletRequest request) throws IOException {
        ProblemDetail problemDetail = of(status, errorCode, detail, request);
        response.setStatus(status.value());
        response.setContentType("application/problem+json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problemDetail);
    }
}
