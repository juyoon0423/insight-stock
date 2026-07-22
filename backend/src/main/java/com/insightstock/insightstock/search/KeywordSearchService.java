package com.insightstock.insightstock.search;

import com.insightstock.insightstock.indexing.ChunkDocument;
import com.insightstock.insightstock.search.dto.KeywordMatch;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Service;

// ElasticSearch BM25 키워드 검색. ChunkDocument.content는 insight_nori 분석기로 색인되어
// 있고(IndexingService 참고), 별도 유사도 설정을 안 했으므로 ES 기본값인 BM25가 그대로 쓰인다.
@Service
public class KeywordSearchService {

    private final ElasticsearchOperations elasticsearchOperations;

    public KeywordSearchService(ElasticsearchOperations elasticsearchOperations) {
        this.elasticsearchOperations = elasticsearchOperations;
    }

    public List<KeywordMatch> search(String queryText, int limit) {
        Query query = NativeQuery.builder()
                .withQuery(q -> q.match(m -> m.field("content").query(queryText)))
                .withPageable(PageRequest.of(0, limit))
                .build();

        SearchHits<ChunkDocument> hits = elasticsearchOperations.search(query, ChunkDocument.class);

        List<KeywordMatch> matches = new ArrayList<>();
        int rank = 1;
        for (SearchHit<ChunkDocument> hit : hits.getSearchHits()) {
            Long chunkId = Long.valueOf(hit.getContent().getId());
            matches.add(new KeywordMatch(chunkId, rank, hit.getScore()));
            rank++;
        }
        return matches;
    }
}
