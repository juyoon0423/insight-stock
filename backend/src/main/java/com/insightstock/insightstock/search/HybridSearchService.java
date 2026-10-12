package com.insightstock.insightstock.search;

import com.insightstock.insightstock.dart.DartUrls;
import com.insightstock.insightstock.domain.Chunk;
import com.insightstock.insightstock.domain.Disclosure;
import com.insightstock.insightstock.repository.ChunkRepository;
import com.insightstock.insightstock.search.dto.DuplicateDisclosureRef;
import com.insightstock.insightstock.search.dto.KeywordMatch;
import com.insightstock.insightstock.search.dto.RankedChunk;
import com.insightstock.insightstock.search.dto.RrfScore;
import com.insightstock.insightstock.search.dto.VectorMatch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// KeywordSearchService(BM25)와 VectorSearchService(cosine)를 병렬로 실행하고 RRF로
// 융합한다. 두 검색은 서로 독립이라 CompletableFuture로 동시에 실행해 지연시간을
// max(두 검색)로 줄인다 - 순차 실행이면 합(keyword + embed + vector)이 된다.
//
// RRF로 융합된 후보 전체(candidate-size 최대 2배 정도)를 hydrate한다 - 예전엔
// top-K만 hydrate했지만, 중복 제거(dedupByContent)를 하려면 top-K로 자르기 전에
// content가 있어야 판정이 가능해서 순서를 바꿨다(dart-findings.md 2026-07-23 참고 -
// 같은 문구가 여러 공시에 반복되는 사례를 발견). hydrate 시 Disclosure/Company까지
// 지연 로딩으로 접근하므로 메서드 전체를 읽기 전용 트랜잭션으로 묶어 세션을 유지한다.
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private final KeywordSearchService keywordSearchService;
    private final VectorSearchService vectorSearchService;
    private final RrfFuser rrfFuser;
    private final ChunkRepository chunkRepository;
    private final Executor searchExecutor;
    private final int candidateSize;
    private final int rrfK;
    private final boolean dedupEnabled;

    public HybridSearchService(
            KeywordSearchService keywordSearchService,
            VectorSearchService vectorSearchService,
            RrfFuser rrfFuser,
            ChunkRepository chunkRepository,
            @Qualifier("searchExecutor") Executor searchExecutor,
            @Value("${hybrid-search.candidate-size}") int candidateSize,
            @Value("${hybrid-search.rrf-k}") int rrfK,
            @Value("${hybrid-search.dedup.enabled}") boolean dedupEnabled) {
        this.keywordSearchService = keywordSearchService;
        this.vectorSearchService = vectorSearchService;
        this.rrfFuser = rrfFuser;
        this.chunkRepository = chunkRepository;
        this.searchExecutor = searchExecutor;
        this.candidateSize = candidateSize;
        this.rrfK = rrfK;
        this.dedupEnabled = dedupEnabled;
    }

    @Transactional(readOnly = true)
    public List<RankedChunk> search(String queryText, int topK) {
        CompletableFuture<List<KeywordMatch>> keywordFuture = CompletableFuture.supplyAsync(
                () -> keywordSearchService.search(queryText, candidateSize), searchExecutor);
        CompletableFuture<List<VectorMatch>> vectorFuture = CompletableFuture.supplyAsync(
                () -> vectorSearchService.search(queryText, candidateSize), searchExecutor);
        CompletableFuture.allOf(keywordFuture, vectorFuture).join();

        List<RrfScore> fused = rrfFuser.fuse(keywordFuture.join(), vectorFuture.join(), rrfK);

        List<Long> fusedIds = fused.stream().map(RrfScore::chunkId).toList();
        Map<Long, Chunk> chunkById = chunkRepository.findAllById(fusedIds).stream()
                .collect(Collectors.toMap(Chunk::getId, chunk -> chunk));

        // Elasticsearch에는 있는데 DB에는 없는 청크 ID가 나올 수 있다(재청킹으로 DB 청크는
        // 지워졌는데 ES 문서는 아직 남은 경우 등). 그대로 두면 null을 다루다 500이 되므로,
        // 그 청크만 결과에서 빼고 나머지로 계속 진행한다 - 본문이 없는 청크는 어차피 보여줄 수 없다.
        List<Long> missingIds = fusedIds.stream().filter(id -> !chunkById.containsKey(id)).toList();
        if (!missingIds.isEmpty()) {
            log.warn("검색 인덱스에는 있으나 DB에 없는 청크 {}건을 결과에서 제외: {}", missingIds.size(), missingIds);
        }

        List<RankedChunk> ranked = fused.stream()
                .filter(rrfScore -> chunkById.containsKey(rrfScore.chunkId()))
                .map(rrfScore -> toRankedChunk(rrfScore, chunkById.get(rrfScore.chunkId())))
                .toList();

        List<RankedChunk> deduped = dedupEnabled ? dedupByContent(ranked) : ranked;

        return deduped.subList(0, Math.min(topK, deduped.size()));
    }

    // ranked는 이미 RRF 점수 내림차순이라, 정규화된 content 기준으로 처음 등장하는
    // 청크가 곧 그 content의 최고 순위(대표) 청크다 - 별도 정렬이 필요 없다.
    // LinkedHashMap이 최초 등장 순서를 그대로 보존해 최종 결과도 RRF 순서를 유지한다.
    private List<RankedChunk> dedupByContent(List<RankedChunk> ranked) {
        Map<String, RankedChunk> representativeByContent = new LinkedHashMap<>();
        Map<String, List<DuplicateDisclosureRef>> duplicatesByContent = new LinkedHashMap<>();

        for (RankedChunk chunk : ranked) {
            String key = normalizeContent(chunk.content());
            if (representativeByContent.containsKey(key)) {
                duplicatesByContent.get(key).add(toDuplicateRef(chunk));
            } else {
                representativeByContent.put(key, chunk);
                duplicatesByContent.put(key, new ArrayList<>());
            }
        }

        List<RankedChunk> result = new ArrayList<>();
        representativeByContent.forEach((key, representative) -> {
            List<DuplicateDisclosureRef> duplicates = duplicatesByContent.get(key);
            result.add(duplicates.isEmpty() ? representative : withDuplicates(representative, duplicates));
        });
        return result;
    }

    // 공백/줄바꿈 차이만 정규화하고, 그 외(날짜·금액 등)는 건드리지 않는 보수적인 기준 -
    // 완전 일치만 중복으로 본다. 숫자를 무시하는 정규화는 회차별로 실제 다른 금액이
    // 적힌 섹션(예: 조달된 자금의 사용내역)을 잘못 같다고 판정할 위험이 있어 배제했다.
    private String normalizeContent(String content) {
        return content.strip().replaceAll("\\s+", " ");
    }

    private DuplicateDisclosureRef toDuplicateRef(RankedChunk chunk) {
        return new DuplicateDisclosureRef(
                chunk.rceptNo(),
                chunk.corpName(),
                chunk.reportNm(),
                chunk.sectionTitle(),
                DartUrls.viewerUrl(chunk.rceptNo()));
    }

    private RankedChunk withDuplicates(RankedChunk chunk, List<DuplicateDisclosureRef> duplicates) {
        return new RankedChunk(
                chunk.chunkId(),
                chunk.rrfScore(),
                chunk.keywordRank(),
                chunk.vectorRank(),
                chunk.rceptNo(),
                chunk.corpName(),
                chunk.reportNm(),
                chunk.sectionTitle(),
                chunk.chunkSource(),
                chunk.content(),
                duplicates);
    }

    private RankedChunk toRankedChunk(RrfScore rrfScore, Chunk chunk) {
        Disclosure disclosure = chunk.getDisclosure();
        return new RankedChunk(
                chunk.getId(),
                rrfScore.score(),
                rrfScore.keywordRank(),
                rrfScore.vectorRank(),
                disclosure.getRceptNo(),
                disclosure.getCompany().getCorpName(),
                disclosure.getReportNm(),
                chunk.getSectionTitle(),
                chunk.getChunkSource(),
                chunk.getContent(),
                List.of());
    }
}
