package com.insightstock.insightstock.search;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

// ES BM25 검색과 pgvector 검색(임베딩 API 호출 포함)을 병렬로 실행하기 위한 전용
// 스레드풀. HybridSearchService 전용이라 이름 있는 빈으로 분리해뒀다. 풀 크기 산정
// 근거는 application.yml의 hybrid-search.executor.pool-size 주석과 docs/loadtest.md 참고.
@Configuration
public class SearchExecutorConfig {

    @Bean
    public Executor searchExecutor(@Value("${hybrid-search.executor.pool-size}") int poolSize) {
        return Executors.newFixedThreadPool(poolSize, new CustomizableThreadFactory("search-"));
    }
}
