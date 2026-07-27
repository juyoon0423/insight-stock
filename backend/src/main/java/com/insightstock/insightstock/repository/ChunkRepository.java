package com.insightstock.insightstock.repository;

import com.insightstock.insightstock.domain.Chunk;
import com.insightstock.insightstock.domain.ChunkSource;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChunkRepository extends JpaRepository<Chunk, Long> {

    List<Chunk> findByEmbeddingIsNull(Pageable pageable);

    // queryVector는 "[0.01,0.02,...]" 형태의 pgvector 텍스트 리터럴. <=>는 코사인 거리 -
    // idx_chunks_embedding_hnsw(vector_cosine_ops)와 연산자가 일치해야 인덱스가 쓰인다.
    @Query(
            value = """
                    SELECT id, (embedding <=> CAST(:queryVector AS vector)) AS distance
                    FROM chunks
                    WHERE embedding IS NOT NULL
                    ORDER BY embedding <=> CAST(:queryVector AS vector)
                    LIMIT :limit
                    """,
            nativeQuery = true)
    List<ChunkVectorMatch> findNearestByEmbedding(@Param("queryVector") String queryVector, @Param("limit") int limit);

    // 검색 품질 측정용 - 키워드/벡터 단독 검색 결과(chunkId 목록)를 rcept_no로 해석해
    // 정답 공시와 비교할 때 쓴다. chunkId마다 findById().getDisclosure()를 여는 대신
    // 한 번의 쿼리로 일괄 처리(N+1 방지).
    @Query("SELECT c.id AS chunkId, c.disclosure.rceptNo AS rceptNo, c.chunkSource AS chunkSource "
            + "FROM Chunk c WHERE c.id IN :chunkIds")
    List<ChunkDisclosureRef> findDisclosureRefsByChunkIds(@Param("chunkIds") List<Long> chunkIds);

    // 검색 품질 측정 리포트에서 "정답 공시가 어떤 chunk_source로 구성돼 있는지"를
    // 보여주기 위함(SECTION/WHOLE_DOCUMENT/SLIDING_WINDOW).
    @Query("SELECT DISTINCT c.chunkSource FROM Chunk c WHERE c.disclosure.rceptNo = :rceptNo")
    List<ChunkSource> findDistinctChunkSourcesByRceptNo(@Param("rceptNo") String rceptNo);
}
