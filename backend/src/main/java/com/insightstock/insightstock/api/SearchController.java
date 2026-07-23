package com.insightstock.insightstock.api;

import com.insightstock.insightstock.api.dto.SearchResponse;
import com.insightstock.insightstock.api.dto.SearchResultItem;
import com.insightstock.insightstock.search.HybridSearchService;
import com.insightstock.insightstock.search.dto.RankedChunk;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// LLM 호출 없이 하이브리드 검색 결과만 반환한다 - RRF 순위 정보를 그대로 노출해
// 프론트에서 하이브리드 검색(BM25+벡터+RRF)이 실제로 어떻게 동작하는지 보여줄 수 있고,
// 비용 없이 반복 호출 가능한 미리보기/디버깅 용도로도 쓴다.
@Validated
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final HybridSearchService hybridSearchService;
    private final int defaultTopK;

    public SearchController(
            HybridSearchService hybridSearchService, @Value("${hybrid-search.top-k}") int defaultTopK) {
        this.hybridSearchService = hybridSearchService;
        this.defaultTopK = defaultTopK;
    }

    @GetMapping
    public SearchResponse search(
            @RequestParam("q") @NotBlank(message = "검색어를 입력해주세요.") String query,
            @RequestParam(value = "topK", required = false)
                    @Min(value = 1, message = "topK는 1 이상이어야 합니다.")
                    @Max(value = 50, message = "topK는 50 이하여야 합니다.")
                    Integer topK) {
        long startedAt = System.currentTimeMillis();
        int effectiveTopK = topK != null ? topK : defaultTopK;

        List<RankedChunk> chunks = hybridSearchService.search(query, effectiveTopK);
        SearchResponse response = new SearchResponse(query, chunks.stream().map(this::toItem).toList());

        log.info(
                "GET /api/v1/search 처리 시간: {}ms, 결과 {}건",
                System.currentTimeMillis() - startedAt, chunks.size());
        return response;
    }

    private SearchResultItem toItem(RankedChunk chunk) {
        return new SearchResultItem(
                chunk.corpName(),
                chunk.reportNm(),
                chunk.sectionTitle(),
                chunk.content(),
                chunk.rrfScore(),
                chunk.keywordRank(),
                chunk.vectorRank(),
                chunk.duplicateSources());
    }
}
