package com.insightstock.insightstock.api;

import com.insightstock.insightstock.embedding.EmbeddingApiException;
import com.insightstock.insightstock.rag.RagApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// OpenAI/Elasticsearch/Postgres의 원본 에러 메시지를 그대로 클라이언트에 보내지 않는다 -
// 내부 구조나 API 키 관련 정보가 새 나갈 수 있어서, 서버 로그에는 전체 예외를 남기고
// 클라이언트에는 일반화된 문구만 준다. RFC 7807(ProblemDetail)을 기본 형식으로 쓰고
// errorCode 확장 필드로 프론트가 에러 종류를 분기할 수 있게 한다.
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return problemDetail(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", detail, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        String detail = ex.getConstraintViolations().stream()
                .map(violation -> violation.getMessage())
                .collect(Collectors.joining(", "));
        return problemDetail(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", detail, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException ex, HttpServletRequest request) {
        return problemDetail(
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getParameterName() + " 파라미터가 필요합니다.", request);
    }

    @ExceptionHandler(RagApiException.class)
    public ProblemDetail handleRagApi(RagApiException ex, HttpServletRequest request) {
        log.error("RAG 답변 생성 실패", ex);
        return problemDetail(
                HttpStatus.BAD_GATEWAY,
                "LLM_UNAVAILABLE",
                "답변 생성 중 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
                request);
    }

    @ExceptionHandler(EmbeddingApiException.class)
    public ProblemDetail handleEmbeddingApi(EmbeddingApiException ex, HttpServletRequest request) {
        log.error("검색용 임베딩 실패", ex);
        return problemDetail(
                HttpStatus.BAD_GATEWAY,
                "SEARCH_UNAVAILABLE",
                "검색 중 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
                request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("예상치 못한 오류", ex);
        return problemDetail(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "예상치 못한 오류가 발생했습니다.", request);
    }

    private ProblemDetail problemDetail(HttpStatus status, String errorCode, String detail, HttpServletRequest request) {
        return ProblemDetails.of(status, errorCode, detail, request);
    }
}
