package com.insightstock.insightstock.embedding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "embedding.collector", name = "enabled", havingValue = "true", matchIfMissing = false)
public class EmbeddingScheduler {

    private final EmbeddingService embeddingService;
    private final int batchSize;

    public EmbeddingScheduler(
            EmbeddingService embeddingService,
            @Value("${embedding.collector.batch-size}") int batchSize) {
        this.embeddingService = embeddingService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${embedding.collector.fixed-delay-ms}")
    public void collect() {
        try {
            embeddingService.embedPending(batchSize);
        } catch (Exception e) {
            log.error("임베딩 배치 실패", e);
        }
    }
}
