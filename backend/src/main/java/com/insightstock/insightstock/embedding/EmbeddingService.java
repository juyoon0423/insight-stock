package com.insightstock.insightstock.embedding;

import com.insightstock.insightstock.domain.Chunk;
import com.insightstock.insightstock.repository.ChunkRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// embedding IS NULL인 청크를 OpenAI 임베딩 API로 처리한다. ChunkingService/IndexingService와
// 같은 이유로(progress.md 참고) 여러 건을 하나의 트랜잭션으로 묶지 않는다 - 다만 처리 단위가
// Disclosure가 아니라 "OpenAI에 한 번에 보낼 청크 sub-batch"라는 점이 다르다: 비용 절감을
// 위해 여러 청크를 한 API 요청으로 묶어야 해서 청크 1개 단위로는 더 쪼갤 수 없다.
// HTTP 호출(embeddingClient.embed)은 트랜잭션 밖에서 실행하고, DB 저장만 sub-batch 단위
// 트랜잭션으로 짧게 묶는다.
@Slf4j
@Service
public class EmbeddingService {

    private static final int MAX_INPUT_TOKENS = 8191;
    private static final double PRICE_PER_MILLION_TOKENS = 0.02;

    private final ChunkRepository chunkRepository;
    private final EmbeddingClient embeddingClient;
    private final TokenCounter tokenCounter;
    private final TransactionTemplate transactionTemplate;
    private final String model;
    private final int requestBatchSize;

    public EmbeddingService(
            ChunkRepository chunkRepository,
            EmbeddingClient embeddingClient,
            TokenCounter tokenCounter,
            PlatformTransactionManager transactionManager,
            @Value("${embedding.model}") String model,
            @Value("${embedding.request-batch-size}") int requestBatchSize) {
        this.chunkRepository = chunkRepository;
        this.embeddingClient = embeddingClient;
        this.tokenCounter = tokenCounter;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.model = model;
        this.requestBatchSize = requestBatchSize;
    }

    public void embedPending(int limit) {
        List<Chunk> targets =
                chunkRepository.findByEmbeddingIsNull(PageRequest.of(0, limit, Sort.by("id")));

        if (targets.isEmpty()) {
            log.info("임베딩할 청크가 없습니다.");
            return;
        }

        log.info("청크 {}건 임베딩 시작 (sub-batch 크기={})", targets.size(), requestBatchSize);

        int totalEmbedded = 0;
        long totalTokens = 0;
        int failedChunks = 0;
        List<List<Chunk>> subBatches = partition(targets, requestBatchSize);

        for (int i = 0; i < subBatches.size(); i++) {
            List<Chunk> subBatch = subBatches.get(i);
            SubBatchResult result = embedSubBatch(subBatch);
            totalEmbedded += result.embeddedCount();
            totalTokens += result.tokens();
            failedChunks += subBatch.size() - result.embeddedCount();

            double cumulativeCost = totalTokens / 1_000_000.0 * PRICE_PER_MILLION_TOKENS;
            log.info(
                    "진행 상황: sub-batch {}/{}, 누적 처리 {}건, 누적 토큰 {}개, 누적 예상 비용 ${}",
                    i + 1, subBatches.size(), totalEmbedded, totalTokens, "%.6f".formatted(cumulativeCost));
        }

        if (failedChunks > 0) {
            log.warn("임베딩 실패/스킵된 청크 {}건 - embedding IS NULL로 남아있어 다음 실행 때 재시도됨", failedChunks);
        }

        double totalCost = totalTokens / 1_000_000.0 * PRICE_PER_MILLION_TOKENS;
        log.info(
                "임베딩 종료: 대상 {}건 중 {}건 처리, 총 토큰 {}개, 예상 비용 ${}",
                targets.size(), totalEmbedded, totalTokens, "%.6f".formatted(totalCost));
    }

    private SubBatchResult embedSubBatch(List<Chunk> subBatch) {
        List<Chunk> embeddable = new ArrayList<>();
        List<Integer> tokenCounts = new ArrayList<>();

        for (Chunk chunk : subBatch) {
            int tokens = tokenCounter.count(chunk.getContent());
            if (tokens > MAX_INPUT_TOKENS) {
                log.error(
                        "청크 id={} 토큰 수 {}개로 한도({}) 초과 - 이번 실행에서 스킵",
                        chunk.getId(), tokens, MAX_INPUT_TOKENS);
                continue;
            }
            embeddable.add(chunk);
            tokenCounts.add(tokens);
        }

        if (embeddable.isEmpty()) {
            return new SubBatchResult(0, 0);
        }

        List<String> texts = embeddable.stream().map(Chunk::getContent).toList();

        EmbeddingBatchResult result;
        try {
            result = embeddingClient.embed(texts);
        } catch (EmbeddingApiException e) {
            log.error(
                    "sub-batch 임베딩 실패 (청크 {}건, id={}~{})",
                    embeddable.size(),
                    embeddable.get(0).getId(),
                    embeddable.get(embeddable.size() - 1).getId(),
                    e);
            return new SubBatchResult(0, 0);
        }

        if (result.embeddings().size() != embeddable.size()) {
            log.error(
                    "sub-batch 응답 개수 불일치: 요청 {}건, 응답 {}건 - 저장 스킵",
                    embeddable.size(), result.embeddings().size());
            return new SubBatchResult(0, 0);
        }

        saveEmbeddings(embeddable, result.embeddings(), tokenCounts);

        double cost = result.totalTokens() / 1_000_000.0 * PRICE_PER_MILLION_TOKENS;
        log.info(
                "sub-batch 완료: 청크 {}건, 토큰 {}개, 예상 비용 ${}",
                embeddable.size(), result.totalTokens(), "%.6f".formatted(cost));

        return new SubBatchResult(embeddable.size(), result.totalTokens());
    }

    private void saveEmbeddings(List<Chunk> chunks, List<float[]> embeddings, List<Integer> tokenCounts) {
        transactionTemplate.executeWithoutResult(status -> {
            for (int i = 0; i < chunks.size(); i++) {
                Chunk chunk = chunks.get(i);
                chunk.setEmbedding(embeddings.get(i));
                chunk.setEmbeddingModel(model);
                chunk.setTokenCount(tokenCounts.get(i));
            }
            chunkRepository.saveAll(chunks);
        });
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }

    private record SubBatchResult(int embeddedCount, int tokens) {
    }
}
