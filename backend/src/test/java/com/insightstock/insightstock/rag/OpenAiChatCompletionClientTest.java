package com.insightstock.insightstock.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insightstock.insightstock.rag.dto.ChatCompletionResult;
import com.insightstock.insightstock.rag.dto.RagPrompt;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 실제 OpenAI를 호출하지 않는다(비용 0) - JDK 내장 HttpServer를 로컬 임의 포트에 띄워
// OpenAI 역할을 대신하게 하고, 클라이언트의 base-url만 그쪽으로 돌린다. 실제 HTTP 왕복을
// 거치므로 "429 응답이 어떤 예외로 올라오는지"까지 그대로 검증된다.
class OpenAiChatCompletionClientTest {

    private static final int MAX_RETRIES = 2;
    private static final String OK_BODY = """
            {"choices":[{"message":{"role":"assistant","content":"답변입니다[1]."}}],
             "usage":{"prompt_tokens":120,"completion_tokens":30}}""";
    private static final String RATE_LIMIT_BODY = """
            {"error":{"message":"Rate limit reached for gpt-4o-mini","type":"tokens","code":"rate_limit_exceeded"}}""";
    private static final String QUOTA_BODY = """
            {"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}""";

    private static final long READ_TIMEOUT_MS = 300;

    // delayMs: 응답을 보내기 전에 서버가 일부러 지연하는 시간(타임아웃 검증용).
    private record StubResponse(int status, String body, long delayMs) {

        StubResponse(int status, String body) {
            this(status, body, 0);
        }
    }

    private HttpServer server;
    private final Deque<StubResponse> scripted = new ArrayDeque<>();
    private final AtomicInteger callCount = new AtomicInteger();
    private OpenAiChatCompletionClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            callCount.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            // 준비한 응답을 순서대로 내보내고, 마지막 응답은 계속 반복한다.
            StubResponse response = scripted.size() > 1 ? scripted.poll() : scripted.peek();
            sleepQuietly(response.delayMs());
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        // 테스트가 느려지지 않도록 백오프는 10ms로 시작(10ms -> 20ms).
        client = new OpenAiChatCompletionClient(
                baseUrl, "test-key", 1000, READ_TIMEOUT_MS, "gpt-4o-mini", 0.0, MAX_RETRIES, 10);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 정상_응답이면_재시도_없이_결과를_반환한다() {
        scripted.add(new StubResponse(200, OK_BODY));

        ChatCompletionResult result = client.complete(prompt());

        assertThat(result.content()).isEqualTo("답변입니다[1].");
        assertThat(result.promptTokens()).isEqualTo(120);
        assertThat(result.completionTokens()).isEqualTo(30);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void 속도제한_429가_풀리면_재시도해서_성공한다() {
        scripted.add(new StubResponse(429, RATE_LIMIT_BODY));
        scripted.add(new StubResponse(429, RATE_LIMIT_BODY));
        scripted.add(new StubResponse(200, OK_BODY));

        ChatCompletionResult result = client.complete(prompt());

        assertThat(result.content()).isEqualTo("답변입니다[1].");
        assertThat(callCount.get()).isEqualTo(3);
    }

    @Test
    void 속도제한_429가_계속되면_재시도를_소진하고_RagRateLimitException을_던진다() {
        scripted.add(new StubResponse(429, RATE_LIMIT_BODY));

        assertThatThrownBy(() -> client.complete(prompt()))
                .isInstanceOf(RagRateLimitException.class)
                .hasMessageContaining("재시도 2회 소진");
        // 최초 1회 + 재시도 2회
        assertThat(callCount.get()).isEqualTo(1 + MAX_RETRIES);
    }

    @Test
    void 크레딧_소진_429는_재시도하지_않고_일반_장애로_취급한다() {
        scripted.add(new StubResponse(429, QUOTA_BODY));

        assertThatThrownBy(() -> client.complete(prompt()))
                .isInstanceOf(RagApiException.class)
                .isNotInstanceOf(RagRateLimitException.class)
                .hasMessageContaining("insufficient_quota");
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void 서버_에러_500은_재시도하지_않고_RagApiException을_던진다() {
        scripted.add(new StubResponse(500, "{\"error\":{\"message\":\"server error\"}}"));

        assertThatThrownBy(() -> client.complete(prompt()))
                .isInstanceOf(RagApiException.class)
                .isNotInstanceOf(RagRateLimitException.class);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void 응답이_read_timeout보다_늦으면_무기한_기다리지_않고_RagApiException을_던진다() {
        // 서버는 3초 뒤에야 응답하지만 클라이언트의 read timeout은 300ms다.
        scripted.add(new StubResponse(200, OK_BODY, 3000));

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> client.complete(prompt()))
                .isInstanceOf(RagApiException.class)
                .isNotInstanceOf(RagRateLimitException.class);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        // 서버 지연(3초)을 끝까지 기다리지 않고 타임아웃 근처에서 끊겼는지, 재시도는 없었는지.
        assertThat(elapsedMs).isLessThan(2000);
        assertThat(callCount.get()).isEqualTo(1);
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private RagPrompt prompt() {
        return new RagPrompt("시스템 프롬프트", "사용자 질문");
    }
}
