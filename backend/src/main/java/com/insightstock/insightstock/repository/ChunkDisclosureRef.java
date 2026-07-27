package com.insightstock.insightstock.repository;

import com.insightstock.insightstock.domain.ChunkSource;

// ChunkRepository.findDisclosureRefsByChunkIds()의 projection.
// 청크 ID 목록을 rcept_no/chunk_source로 한 번에 해석할 때 쓴다 - 매번
// chunkRepository.findById().getDisclosure()로 지연 로딩을 여는 대신,
// 검색 품질 측정처럼 chunkId 리스트를 rceptNo로 일괄 변환해야 하는 경우 전용.
public interface ChunkDisclosureRef {

    Long getChunkId();

    String getRceptNo();

    ChunkSource getChunkSource();
}
