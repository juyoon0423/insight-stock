package com.insightstock.insightstock.dart;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// list/document API와 달리 페이징이 없다 - DART에 등록된 회사 전체를 ZIP 하나로 통째로 준다
// (실측: dart-findings.md 2-11, 118,535건). document API와 같은 이유로 바이트 그대로 반환하고
// ZIP 해제/파싱은 호출부(CorpCodeSyncService)에서 한다.
@Component
public class DartCorpCodeClient {

    private final RestClient restClient;
    private final String apiKey;

    public DartCorpCodeClient(
            @Value("${dart.api.base-url}") String baseUrl,
            @Value("${dart.api.key}") String apiKey) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    public byte[] fetchCorpCode() {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/corpCode.xml")
                        .queryParam("crtfc_key", apiKey)
                        .build())
                .retrieve()
                .body(byte[].class);
    }
}
