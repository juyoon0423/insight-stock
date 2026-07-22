package com.insightstock.insightstock.repository;

import com.insightstock.insightstock.domain.Chunk;
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
}
