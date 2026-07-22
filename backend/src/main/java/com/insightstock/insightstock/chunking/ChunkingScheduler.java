package com.insightstock.insightstock.chunking;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// chunking.collector.enabled=false로 끄면 자동 배치 없이 청킹을 멈출 수 있다
// (예: 청크 폭증 문제처럼 기존 PARSED 공시를 건드리지 않고 잠시 조사만 하고 싶을 때).
@Slf4j
@Component
@ConditionalOnProperty(prefix = "chunking.collector", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ChunkingScheduler {

    private final ChunkingService chunkingService;
    private final int batchSize;

    public ChunkingScheduler(
            ChunkingService chunkingService,
            @Value("${chunking.collector.batch-size}") int batchSize) {
        this.chunkingService = chunkingService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${chunking.collector.fixed-delay-ms}")
    public void collect() {
        try {
            chunkingService.chunkPending(batchSize);
        } catch (Exception e) {
            log.error("청킹 배치 실패", e);
        }
    }
}
