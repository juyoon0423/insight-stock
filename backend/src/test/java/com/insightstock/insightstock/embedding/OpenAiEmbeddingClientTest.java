package com.insightstock.insightstock.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 실제 OpenAI를 호출하지 않는다(비용 0) - OpenAiChatCompletionClientTest와 같은 방식으로
// JDK 내장 HttpServer가 OpenAI 역할을 대신한다. 단일(질의)/배치 경로에 서로 다른 read
// timeout이 실제로 적용되는지를 검증하는 게 목적이다.
class OpenAiEmbeddingClientTest {

    // 실제 값(단일 10초 / 배치 60초)의 관계만 유지한 채 테스트용으로 축소했다.
    private static final long SINGLE_READ_TIMEOUT_MS = 300;
    private static final long BATCH_READ_TIMEOUT_MS = 2000;

    // index 순서를 일부러 뒤집어 보낸다 - 클라이언트가 index 기준으로 다시 정렬해야 한다.
    private static final String TWO_ITEMS_BODY = """
            {"data":[{"index":1,"embedding":[0.3,0.4]},{"index":0,"embedding":[0.1,0.2]}],
             "usage":{"prompt_tokens":7,"total_tokens":7}}""";
    private static final String ONE_ITEM_BODY = """
            {"data":[{"index":0,"embedding":[0.1,0.2]}],"usage":{"prompt_tokens":3,"total_tokens":3}}""";

    private HttpServer server;
    private final AtomicInteger callCount = new AtomicInteger();
    private volatile String responseBody = ONE_ITEM_BODY;
    private volatile int responseStatus = 200;
    // 응답을 보내기 전에 서버가 일부러 지연하는 시간.
    private volatile long responseDelayMs = 0;
    private OpenAiEmbeddingClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            callCount.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            sleepQuietly(responseDelayMs);
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new OpenAiEmbeddingClient(
                baseUrl, "test-key", 1000, SINGLE_READ_TIMEOUT_MS, BATCH_READ_TIMEOUT_MS,
                "text-embedding-3-small", 3, 10);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 단일_경로는_질의_벡터_하나를_반환한다() {
        float[] vector = client.embedQuery("삼성전자 유상증자");

        assertThat(vector).containsExactly(0.1f, 0.2f);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void 배치_경로는_index_순서대로_정렬해_반환한다() {
        responseBody = TWO_ITEMS_BODY;

        EmbeddingBatchResult result = client.embed(List.of("첫번째", "두번째"));

        assertThat(result.embeddings()).hasSize(2);
        assertThat(result.embeddings().get(0)).containsExactly(0.1f, 0.2f);
        assertThat(result.embeddings().get(1)).containsExactly(0.3f, 0.4f);
        assertThat(result.totalTokens()).isEqualTo(7);
    }

    @Test
    void 단일_경로는_read_timeout을_넘기면_재시도_없이_중단한다() {
        // 1초 지연: 단일 타임아웃(300ms)은 넘고 배치 타임아웃(2초)에는 못 미친다.
        responseDelayMs = 1000;

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> client.embedQuery("질의")).isInstanceOf(EmbeddingApiException.class);
        long elapsedMs = elapsedMsSince(startedAt);

        // 서버 응답(1초)을 끝까지 기다리지 않고 그 전에 끊겼는지(느린 CI를 감안한 여유 포함).
        assertThat(elapsedMs).isLessThan(900);
        // 타임아웃은 429와 달리 재시도 대상이 아니다.
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void 같은_지연이라도_배치_경로는_더_긴_타임아웃이라_성공한다() {
        // 위 테스트와 똑같은 1초 지연 - 경로별 타임아웃이 실제로 분리돼 있다는 증거.
        responseDelayMs = 1000;
        responseBody = TWO_ITEMS_BODY;

        EmbeddingBatchResult result = client.embed(List.of("첫번째", "두번째"));

        assertThat(result.embeddings()).hasSize(2);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void 배치_경로도_자기_read_timeout을_넘기면_재시도_없이_중단한다() {
        // 4초 지연: 배치 타임아웃(2초)도 넘는다.
        responseDelayMs = 4000;

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> client.embed(List.of("첫번째", "두번째")))
                .isInstanceOf(EmbeddingApiException.class);
        long elapsedMs = elapsedMsSince(startedAt);

        // 단일 타임아웃(300ms)에 끊긴 게 아니라 배치 타임아웃(2초)까지는 기다렸고,
        // 서버 응답(4초)을 끝까지 기다리지는 않았는지.
        assertThat(elapsedMs).isBetween(1800L, 3700L);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void 속도제한_429가_계속되면_재시도를_소진하고_EmbeddingRateLimitException을_던진다() {
        responseStatus = 429;
        responseBody = "{\"error\":{\"message\":\"Rate limit reached\",\"code\":\"rate_limit_exceeded\"}}";

        assertThatThrownBy(() -> client.embedQuery("질의"))
                .isInstanceOf(EmbeddingRateLimitException.class)
                .hasMessageContaining("재시도 3회 소진");
        // 최초 1회 + 재시도 3회
        assertThat(callCount.get()).isEqualTo(4);
    }

    @Test
    void 크레딧_소진_429는_재시도하지_않고_일반_장애로_취급한다() {
        responseStatus = 429;
        responseBody = "{\"error\":{\"message\":\"You exceeded your current quota\",\"code\":\"insufficient_quota\"}}";

        assertThatThrownBy(() -> client.embedQuery("질의"))
                .isInstanceOf(EmbeddingApiException.class)
                .isNotInstanceOf(EmbeddingRateLimitException.class)
                .hasMessageContaining("insufficient_quota");
        assertThat(callCount.get()).isEqualTo(1);
    }

    private static long elapsedMsSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
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
}
