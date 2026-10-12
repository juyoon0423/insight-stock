package com.insightstock.insightstock.dart;

import com.insightstock.insightstock.dart.dto.DartListResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DartListClient {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final RestClient restClient;
    private final String apiKey;

    public DartListClient(
            @Value("${dart.api.base-url}") String baseUrl,
            @Value("${dart.api.key}") String apiKey,
            @Value("${dart.api.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${dart.api.read-timeout-ms}") long readTimeoutMs) {
        this.restClient = DartRestClients.create(baseUrl, connectTimeoutMs, readTimeoutMs);
        this.apiKey = apiKey;
    }

    public DartListResponse fetchList(LocalDate beginDate, LocalDate endDate, int pageNo, int pageCount) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/list.json")
                        .queryParam("crtfc_key", apiKey)
                        .queryParam("bgn_de", beginDate.format(DATE_FORMAT))
                        .queryParam("end_de", endDate.format(DATE_FORMAT))
                        .queryParam("page_no", pageNo)
                        .queryParam("page_count", pageCount)
                        .queryParam("sort", "date")
                        .queryParam("sort_mth", "asc")
                        .build())
                .retrieve()
                .body(DartListResponse.class);
    }
}
