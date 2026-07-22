package com.insightstock.insightstock.search;

import com.insightstock.insightstock.domain.Chunk;
import com.insightstock.insightstock.domain.Disclosure;
import com.insightstock.insightstock.repository.ChunkRepository;
import com.insightstock.insightstock.search.dto.KeywordMatch;
import com.insightstock.insightstock.search.dto.RankedChunk;
import com.insightstock.insightstock.search.dto.RrfScore;
import com.insightstock.insightstock.search.dto.VectorMatch;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// KeywordSearchService(BM25)와 VectorSearchService(cosine)를 병렬로 실행하고 RRF로
// 융합한다. 두 검색은 서로 독립이라 CompletableFuture로 동시에 실행해 지연시간을
// max(두 검색)로 줄인다 - 순차 실행이면 합(keyword + embed + vector)이 된다.
//
// 최종 Top-K만 ChunkRepository로 hydrate한다(후보군 전체가 아니라) - RRF 융합까지는
// chunk_id와 순위만 있으면 되고, content 등 실제 데이터는 화면에 보여줄 결과에만
// 필요하기 때문이다. hydrate 시 Disclosure/Company까지 지연 로딩으로 접근하므로
// 메서드 전체를 읽기 전용 트랜잭션으로 묶어 세션을 유지한다.
@Service
public class HybridSearchService {

    private final KeywordSearchService keywordSearchService;
    private final VectorSearchService vectorSearchService;
    private final RrfFuser rrfFuser;
    private final ChunkRepository chunkRepository;
    private final Executor searchExecutor;
    private final int candidateSize;
    private final int rrfK;

    public HybridSearchService(
            KeywordSearchService keywordSearchService,
            VectorSearchService vectorSearchService,
            RrfFuser rrfFuser,
            ChunkRepository chunkRepository,
            @Qualifier("searchExecutor") Executor searchExecutor,
            @Value("${hybrid-search.candidate-size}") int candidateSize,
            @Value("${hybrid-search.rrf-k}") int rrfK) {
        this.keywordSearchService = keywordSearchService;
        this.vectorSearchService = vectorSearchService;
        this.rrfFuser = rrfFuser;
        this.chunkRepository = chunkRepository;
        this.searchExecutor = searchExecutor;
        this.candidateSize = candidateSize;
        this.rrfK = rrfK;
    }

    @Transactional(readOnly = true)
    public List<RankedChunk> search(String queryText, int topK) {
        CompletableFuture<List<KeywordMatch>> keywordFuture = CompletableFuture.supplyAsync(
                () -> keywordSearchService.search(queryText, candidateSize), searchExecutor);
        CompletableFuture<List<VectorMatch>> vectorFuture = CompletableFuture.supplyAsync(
                () -> vectorSearchService.search(queryText, candidateSize), searchExecutor);
        CompletableFuture.allOf(keywordFuture, vectorFuture).join();

        List<RrfScore> fused = rrfFuser.fuse(keywordFuture.join(), vectorFuture.join(), rrfK);
        List<RrfScore> topScores = fused.subList(0, Math.min(topK, fused.size()));

        List<Long> topIds = topScores.stream().map(RrfScore::chunkId).toList();
        Map<Long, Chunk> chunkById = chunkRepository.findAllById(topIds).stream()
                .collect(Collectors.toMap(Chunk::getId, chunk -> chunk));

        return topScores.stream()
                .map(rrfScore -> toRankedChunk(rrfScore, chunkById.get(rrfScore.chunkId())))
                .toList();
    }

    private RankedChunk toRankedChunk(RrfScore rrfScore, Chunk chunk) {
        Disclosure disclosure = chunk.getDisclosure();
        return new RankedChunk(
                chunk.getId(),
                rrfScore.score(),
                rrfScore.keywordRank(),
                rrfScore.vectorRank(),
                disclosure.getCompany().getCorpName(),
                disclosure.getReportNm(),
                chunk.getSectionTitle(),
                chunk.getChunkSource(),
                chunk.getContent());
    }
}
