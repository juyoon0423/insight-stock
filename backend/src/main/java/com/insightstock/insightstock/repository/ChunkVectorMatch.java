package com.insightstock.insightstock.repository;

// ChunkRepository.findNearestByEmbedding()의 native query 결과 projection.
// distance는 pgvector의 코사인 거리(<=>) 값 - 0에 가까울수록 유사.
public interface ChunkVectorMatch {

    Long getId();

    Double getDistance();
}
