package com.insightstock.insightstock.collector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DocumentCollectorScheduler {

    private final DocumentCollectorService documentCollectorService;
    private final int batchSize;

    public DocumentCollectorScheduler(
            DocumentCollectorService documentCollectorService,
            @Value("${dart.document-collector.batch-size}") int batchSize) {
        this.documentCollectorService = documentCollectorService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${dart.document-collector.fixed-delay-ms}")
    public void collect() {
        try {
            documentCollectorService.collectPending(batchSize);
        } catch (Exception e) {
            log.error("document 수집 배치 실패", e);
        }
    }
}
