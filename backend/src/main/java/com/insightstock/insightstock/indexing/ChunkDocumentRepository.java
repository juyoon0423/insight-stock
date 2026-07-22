package com.insightstock.insightstock.indexing;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface ChunkDocumentRepository extends ElasticsearchRepository<ChunkDocument, String> {

    // 재색인 시 오래된 문서를 먼저 정리한다 - 재청킹으로 chunk_index/개수가 바뀌면
    // 예전 chunk_id로 색인됐던 문서가 새 목록에 없는 채로 남아 고아가 될 수 있다.
    void deleteByDisclosureId(Long disclosureId);
}
