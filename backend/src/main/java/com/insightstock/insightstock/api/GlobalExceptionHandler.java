package com.insightstock.insightstock.api;

import com.insightstock.insightstock.auth.DuplicateEmailException;
import com.insightstock.insightstock.auth.InvalidCredentialsException;
import com.insightstock.insightstock.auth.InvalidRefreshTokenException;
import com.insightstock.insightstock.embedding.EmbeddingApiException;
import com.insightstock.insightstock.embedding.EmbeddingRateLimitException;
import com.insightstock.insightstock.notification.NotificationNotFoundException;
import com.insightstock.insightstock.rag.RagApiException;
import com.insightstock.insightstock.rag.RagRateLimitException;
import com.insightstock.insightstock.watchlist.CompanyNotFoundException;
import com.insightstock.insightstock.watchlist.DuplicateWatchlistException;
import com.insightstock.insightstock.watchlist.WatchlistNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
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

    // OpenAI rate limit(429)으로 재시도까지 소진한 경우는 "실제 장애"(아래 502)와 구분해
    // 429로 응답한다 - 클라이언트가 상태 코드만으로 "잠시 후 다시 시도하면 되는 상황"임을
    // 알 수 있어야 하고, 서버 쪽에서도 이걸 장애로 집계하면 알람이 오탐된다(그래서 로그
    // 레벨도 error가 아니라 warn). RagRateLimitException이 RagApiException의 하위 타입이라
    // Spring이 더 구체적인 이 핸들러를 먼저 고른다.
    @ExceptionHandler(RagRateLimitException.class)
    public ProblemDetail handleRagRateLimit(RagRateLimitException ex, HttpServletRequest request) {
        log.warn("RAG 답변 생성 실패 - OpenAI rate limit: {}", ex.getMessage());
        return problemDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "LLM_RATE_LIMITED",
                "지금 요청이 몰려 답변을 생성하지 못했습니다. 잠시 후 다시 시도해주세요.",
                request);
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

    // 질의 임베딩의 rate limit(429) 재시도 소진 - 위 handleRagRateLimit과 같은 이유로
    // "실제 장애"(아래 502)와 구분해 429로 응답하고 로그도 warn으로 남긴다.
    @ExceptionHandler(EmbeddingRateLimitException.class)
    public ProblemDetail handleEmbeddingRateLimit(EmbeddingRateLimitException ex, HttpServletRequest request) {
        log.warn("검색용 임베딩 실패 - OpenAI rate limit: {}", ex.getMessage());
        return problemDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "SEARCH_RATE_LIMITED",
                "지금 요청이 몰려 검색하지 못했습니다. 잠시 후 다시 시도해주세요.",
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

    // HybridSearchService는 키워드/벡터 검색을 CompletableFuture로 병렬 실행하고 join()하므로,
    // 그 안에서 난 예외(질의 임베딩 실패 등)는 CompletionException에 감싸져 올라온다. Spring은
    // 바깥 예외 타입부터 핸들러를 찾는데 CompletionException은 맨 아래 Exception 핸들러에
    // 걸리므로, 따로 풀어주지 않으면 원인과 무관하게 전부 500/INTERNAL_ERROR가 된다
    // (2026-10-12 SearchPathErrorMappingTest로 재현). 감싼 껍질을 벗겨 원래 예외의 핸들러로
    // 다시 보낸다. 여기서 분류하지 않는 원인(ES 장애 등)은 기존대로 500이다.
    @ExceptionHandler({CompletionException.class, ExecutionException.class})
    public ProblemDetail handleAsyncWrapped(Exception ex, HttpServletRequest request) {
        Throwable cause = unwrapAsync(ex);
        if (cause instanceof EmbeddingRateLimitException embeddingRateLimit) {
            return handleEmbeddingRateLimit(embeddingRateLimit, request);
        }
        if (cause instanceof EmbeddingApiException embeddingApi) {
            return handleEmbeddingApi(embeddingApi, request);
        }
        if (cause instanceof RagRateLimitException ragRateLimit) {
            return handleRagRateLimit(ragRateLimit, request);
        }
        if (cause instanceof RagApiException ragApi) {
            return handleRagApi(ragApi, request);
        }
        return handleUnexpected(ex, request);
    }

    // 비동기 래퍼가 여러 겹일 수 있어(예: allOf 안의 future) 래퍼가 아닌 예외가 나올 때까지 벗긴다.
    private Throwable unwrapAsync(Throwable ex) {
        Throwable current = ex;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ProblemDetail handleDuplicateEmail(DuplicateEmailException ex, HttpServletRequest request) {
        return problemDetail(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "이미 가입된 이메일입니다.", request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex, HttpServletRequest request) {
        return problemDetail(
                HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.", request);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ProblemDetail handleInvalidRefreshToken(InvalidRefreshTokenException ex, HttpServletRequest request) {
        return problemDetail(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "유효하지 않은 refresh 토큰입니다.", request);
    }

    @ExceptionHandler(CompanyNotFoundException.class)
    public ProblemDetail handleCompanyNotFound(CompanyNotFoundException ex, HttpServletRequest request) {
        return problemDetail(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND", "존재하지 않는 회사입니다.", request);
    }

    @ExceptionHandler(DuplicateWatchlistException.class)
    public ProblemDetail handleDuplicateWatchlist(DuplicateWatchlistException ex, HttpServletRequest request) {
        return problemDetail(HttpStatus.CONFLICT, "WATCHLIST_ALREADY_EXISTS", "이미 관심 목록에 등록된 회사입니다.", request);
    }

    @ExceptionHandler(WatchlistNotFoundException.class)
    public ProblemDetail handleWatchlistNotFound(WatchlistNotFoundException ex, HttpServletRequest request) {
        return problemDetail(HttpStatus.NOT_FOUND, "WATCHLIST_NOT_FOUND", "존재하지 않는 관심 목록 항목입니다.", request);
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    public ProblemDetail handleNotificationNotFound(NotificationNotFoundException ex, HttpServletRequest request) {
        return problemDetail(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "존재하지 않는 알림입니다.", request);
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
