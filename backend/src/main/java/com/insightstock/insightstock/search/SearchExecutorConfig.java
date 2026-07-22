package com.insightstock.insightstock.search;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

// ES BM25 검색과 pgvector 검색(임베딩 API 호출 포함)을 병렬로 실행하기 위한 전용
// 스레드풀. HybridSearchService 전용이라 이름 있는 빈으로 분리해뒀다.
@Configuration
public class SearchExecutorConfig {

    @Bean
    public Executor searchExecutor() {
        return Executors.newFixedThreadPool(4, new CustomizableThreadFactory("search-"));
    }
}
