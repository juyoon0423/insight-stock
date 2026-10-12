package com.insightstock.insightstock.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insightstock.insightstock.domain.Chunk;
import com.insightstock.insightstock.domain.ChunkSource;
import com.insightstock.insightstock.domain.Company;
import com.insightstock.insightstock.domain.Disclosure;
import com.insightstock.insightstock.repository.ChunkRepository;
import com.insightstock.insightstock.search.dto.KeywordMatch;
import com.insightstock.insightstock.search.dto.RankedChunk;
import com.insightstock.insightstock.search.dto.VectorMatch;
import java.util.List;
import org.junit.jupiter.api.Test;

// 검색 두 갈래(ES/pgvector)와 DB 조회를 대역으로 바꿔 융합 이후의 조립 로직만 검증한다(비용 0).
class HybridSearchServiceTest {

    private final KeywordSearchService keywordSearchService = mock(KeywordSearchService.class);
    private final VectorSearchService vectorSearchService = mock(VectorSearchService.class);
    private final ChunkRepository chunkRepository = mock(ChunkRepository.class);
    // 같은 스레드에서 바로 실행 - 병렬성 자체는 이 테스트의 관심사가 아니다.
    private final HybridSearchService hybridSearchService = new HybridSearchService(
            keywordSearchService, vectorSearchService, new RrfFuser(), chunkRepository, Runnable::run, 50, 60, true);

    @Test
    void 검색_인덱스에만_있고_DB에_없는_청크는_빼고_나머지로_응답한다() {
        // ES가 1, 2번을 돌려줬는데 DB에는 1번만 있다(2번은 재청킹 등으로 지워진 상태).
        when(keywordSearchService.search(anyString(), anyInt()))
                .thenReturn(List.of(new KeywordMatch(2L, 1, 9.0f), new KeywordMatch(1L, 2, 8.0f)));
        when(vectorSearchService.search(anyString(), anyInt())).thenReturn(List.of());
        when(chunkRepository.findAllById(anyIterable())).thenReturn(List.of(chunk(1L, "남아 있는 청크")));

        List<RankedChunk> results = hybridSearchService.search("질의", 10);

        assertThat(results).extracting(RankedChunk::chunkId).containsExactly(1L);
        assertThat(results.get(0).keywordRank()).isEqualTo(2);
    }

    @Test
    void 내용이_같은_청크는_대표_하나로_합치고_나머지는_duplicateSources에_남긴다() {
        when(keywordSearchService.search(anyString(), anyInt()))
                .thenReturn(List.of(new KeywordMatch(1L, 1, 9.0f), new KeywordMatch(2L, 2, 8.0f)));
        when(vectorSearchService.search(anyString(), anyInt())).thenReturn(List.of(new VectorMatch(3L, 1, 0.1)));
        when(chunkRepository.findAllById(anyIterable()))
                .thenReturn(List.of(chunk(1L, "같은  문구"), chunk(2L, "같은 문구"), chunk(3L, "다른 문구")));

        List<RankedChunk> results = hybridSearchService.search("질의", 10);

        assertThat(results).hasSize(2);
        RankedChunk merged = results.stream().filter(r -> !r.duplicateSources().isEmpty()).findFirst().orElseThrow();
        assertThat(merged.duplicateSources()).hasSize(1);
    }

    private Chunk chunk(long id, String content) {
        Company company = new Company();
        company.setCorpName("회사" + id);
        Disclosure disclosure = new Disclosure();
        disclosure.setCompany(company);
        disclosure.setRceptNo("rcept-" + id);
        disclosure.setReportNm("보고서");
        Chunk chunk = new Chunk();
        chunk.setId(id);
        chunk.setDisclosure(disclosure);
        chunk.setChunkSource(ChunkSource.SECTION);
        chunk.setContent(content);
        return chunk;
    }
}
