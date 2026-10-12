package com.insightstock.insightstock.dart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

// 실제 DART를 호출하지 않는다 - JDK 내장 HttpServer가 DART 역할을 대신한다(OpenAI 클라이언트
// 테스트와 같은 방식). DART 클라이언트가 응답 없는 서버를 무기한 기다리지 않는지를 검증한다:
// 이 호출은 스케줄러 스레드에서 일어나므로, 멈추면 그 스케줄러가 다시는 돌지 않는다.
class DartClientTimeoutTest {

    private static final long READ_TIMEOUT_MS = 300;

    private HttpServer server;
    private volatile long responseDelayMs = 0;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(responseDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "{\"status\":\"013\",\"message\":\"조회된 데이타가 없습니다.\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 제때_응답하면_정상적으로_받는다() {
        DartListClient client = new DartListClient(baseUrl, "test-key", 1000, READ_TIMEOUT_MS);

        assertThat(client.fetchList(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12), 1, 100).status())
                .isEqualTo("013");
    }

    @Test
    void 목록_API가_응답하지_않으면_read_timeout에서_끊는다() {
        responseDelayMs = 2000;
        DartListClient client = new DartListClient(baseUrl, "test-key", 1000, READ_TIMEOUT_MS);

        assertTimesOutQuickly(
                () -> client.fetchList(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12), 1, 100));
    }

    @Test
    void 원문_API가_응답하지_않으면_read_timeout에서_끊는다() {
        responseDelayMs = 2000;
        DartDocumentClient client = new DartDocumentClient(baseUrl, "test-key", 1000, READ_TIMEOUT_MS);

        assertTimesOutQuickly(() -> client.fetchDocument("20260724800076"));
    }

    @Test
    void 회사코드_API가_응답하지_않으면_read_timeout에서_끊는다() {
        responseDelayMs = 2000;
        DartCorpCodeClient client = new DartCorpCodeClient(baseUrl, "test-key", 1000, READ_TIMEOUT_MS);

        assertTimesOutQuickly(client::fetchCorpCode);
    }

    private void assertTimesOutQuickly(Runnable call) {
        long startedAt = System.nanoTime();
        assertThatThrownBy(call::run).isInstanceOf(ResourceAccessException.class);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        // 서버 응답(2초)을 끝까지 기다리지 않고 그 전에 끊겼는지(느린 CI를 감안한 여유 포함).
        assertThat(elapsedMs).isLessThan(1500);
    }
}
