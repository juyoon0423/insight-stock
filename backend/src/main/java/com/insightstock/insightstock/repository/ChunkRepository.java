package com.insightstock.insightstock.repository;

import com.insightstock.insightstock.domain.Chunk;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChunkRepository extends JpaRepository<Chunk, Long> {

    List<Chunk> findByEmbeddingIsNull(Pageable pageable);
}
