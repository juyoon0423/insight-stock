package com.insightstock.insightstock.dart;

import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.web.client.RestClient;

// DART API용 RestClient 생성 로직. 세 클라이언트(목록/원문/회사코드)가 같은 타임아웃 설정을 쓴다.
//
// 타임아웃을 지정하지 않으면 RestClient는 무기한 기다린다. DART 호출은 전부 스케줄러 스레드에서
// 일어나는데, 호출 하나가 응답 없이 멈추면 그 스레드가 영영 돌아오지 않아 그 스케줄러의 다음
// 주기가 다시는 오지 않는다(예외가 아니라 "멈춤"이라 로그에도 안 남는다). OpenAI 클라이언트
// (OpenAiChatCompletionClient 등)에 넣은 것과 같은 방식으로 막는다.
final class DartRestClients {

    private DartRestClients() {
    }

    static RestClient create(String baseUrl, long connectTimeoutMs, long readTimeoutMs) {
        ClientHttpRequestFactorySettings timeouts = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(timeouts))
                .baseUrl(baseUrl)
                .build();
    }
}
