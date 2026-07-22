package com.insightstock.insightstock.search;

import com.insightstock.insightstock.embedding.EmbeddingClient;
import com.insightstock.insightstock.repository.ChunkRepository;
import com.insightstock.insightstock.repository.ChunkVectorMatch;
import com.insightstock.insightstock.search.dto.VectorMatch;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

// pgvector 코사인 유사도 검색. 질의를 색인 때와 같은 모델(EmbeddingClient가 내부적으로
// 보장 - embedding/OpenAiEmbeddingClient 참고)로 임베딩한 뒤, HNSW(vector_cosine_ops)
// 인덱스를 쓰는 <=> 연산자로 가장 가까운 청크를 찾는다.
@Service
public class VectorSearchService {

    private final EmbeddingClient embeddingClient;
    private final ChunkRepository chunkRepository;

    public VectorSearchService(EmbeddingClient embeddingClient, ChunkRepository chunkRepository) {
        this.embeddingClient = embeddingClient;
        this.chunkRepository = chunkRepository;
    }

    public List<VectorMatch> search(String queryText, int limit) {
        float[] queryVector = embeddingClient.embed(List.of(queryText)).embeddings().get(0);
        String vectorLiteral = toVectorLiteral(queryVector);

        List<ChunkVectorMatch> rows = chunkRepository.findNearestByEmbedding(vectorLiteral, limit);

        List<VectorMatch> matches = new ArrayList<>();
        int rank = 1;
        for (ChunkVectorMatch row : rows) {
            matches.add(new VectorMatch(row.getId(), rank, row.getDistance()));
            rank++;
        }
        return matches;
    }

    private String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
