package com.insightstock.insightstock.dart;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// document API 응답은 정상일 때 ZIP, 실패일 때 순수 XML 에러 바디로 형식 자체가 다르므로
// 여기서는 바이트 그대로 반환하고, ZIP 여부 판별·해석은 호출부(DocumentCollectorService)에서 한다.
@Component
public class DartDocumentClient {

    private final RestClient restClient;
    private final String apiKey;

    public DartDocumentClient(
            @Value("${dart.api.base-url}") String baseUrl,
            @Value("${dart.api.key}") String apiKey) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    public byte[] fetchDocument(String rceptNo) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/document.xml")
                        .queryParam("crtfc_key", apiKey)
                        .queryParam("rcept_no", rceptNo)
                        .build())
                .retrieve()
                .body(byte[].class);
    }
}
