package com.insightstock.insightstock.search;

import com.insightstock.insightstock.domain.Chunk;
import com.insightstock.insightstock.repository.ChunkRepository;
import com.insightstock.insightstock.search.dto.KeywordMatch;
import com.insightstock.insightstock.search.dto.RankedChunk;
import com.insightstock.insightstock.search.dto.VectorMatch;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 실제 ES/Postgres/OpenAI를 그대로 쓰는 수동 검증용 - 정기 CI 대상이 아니라
// 하이브리드 검색을 구현하면서 단계별로 결과를 눈으로 확인하기 위한 도구다.
// vectorSearchPreview/hybridSearchPreview는 질의마다 OpenAI 임베딩 API를 실제로
// 호출한다(건당 비용은 미미하지만 0은 아님) - @Tag("manual")로 표시해 기본
// `./gradlew test`에서 제외되고 `./gradlew manualTest`로만 실행되게 한다
// (build.gradle 참고). 질의 3종은 실제 DB에 있는 회사/공시를 근거로 골랐다:
// - 키워드 지향: 정확한 회사명+보고서명 조합이라 BM25가 유리할 것으로 예상
// - 의미 지향: "차입금"이라는 단어 없이 "빌린 돈"으로 패러프레이즈 - 기도산업의
//   실제 청크(총차입금 규모 추이)에 벡터 검색이 도달하는지 확인하는 목적
// - 일반: 회사명+상품명 조합 - 두 검색 다 무난히 맞아야 하는 케이스
@Tag("manual")
@SpringBootTest
class HybridSearchManualIT {

    private static final String QUERY_KEYWORD = "신영증권 증권발행실적보고서";
    private static final String QUERY_SEMANTIC = "기도산업이 최근 은행 등에서 빌린 돈의 규모는 어떻게 변화했나요?";
    private static final String QUERY_GENERAL = "한국투자증권이 발행한 주가연계파생결합사채 상품 설명";

    @Autowired
    private KeywordSearchService keywordSearchService;

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private ChunkRepository chunkRepository;

    @Test
    @Transactional
    void keywordSearchPreview() {
        printKeywordResults("키워드 지향", QUERY_KEYWORD);
        printKeywordResults("의미 지향", QUERY_SEMANTIC);
        printKeywordResults("일반", QUERY_GENERAL);
    }

    @Test
    @Transactional
    void vectorSearchPreview() {
        printVectorResults("키워드 지향", QUERY_KEYWORD);
        printVectorResults("의미 지향", QUERY_SEMANTIC);
        printVectorResults("일반", QUERY_GENERAL);
    }

    @Test
    void hybridSearchPreview() {
        printHybridResults("키워드 지향", QUERY_KEYWORD);
        printHybridResults("의미 지향", QUERY_SEMANTIC);
        printHybridResults("일반", QUERY_GENERAL);
    }

    private void printHybridResults(String label, String queryText) {
        List<RankedChunk> results = hybridSearchService.search(queryText, 10);

        System.out.println();
        System.out.println("=== [" + label + "] \"" + queryText + "\" - 하이브리드(RRF) 결과 " + results.size() + "건 ===");
        for (RankedChunk r : results) {
            System.out.printf(
                    "rrf=%-8.5f kwRank=%-4s vecRank=%-4s corp=%-10s report=%-20s source=%-14s content=%s%n",
                    r.rrfScore(),
                    r.keywordRank() == null ? "-" : r.keywordRank().toString(),
                    r.vectorRank() == null ? "-" : r.vectorRank().toString(),
                    r.corpName(),
                    truncate(r.reportNm(), 18),
                    r.chunkSource(),
                    preview(r.content()));
        }
    }

    private void printKeywordResults(String label, String queryText) {
        List<KeywordMatch> matches = keywordSearchService.search(queryText, 10);

        System.out.println();
        System.out.println("=== [" + label + "] \"" + queryText + "\" - 키워드(BM25) 결과 " + matches.size() + "건 ===");
        for (KeywordMatch match : matches) {
            Chunk chunk = chunkRepository.findById(match.chunkId()).orElseThrow();
            System.out.printf(
                    "rank=%-2d score=%-8.3f corp=%-10s report=%-20s source=%-14s content=%s%n",
                    match.rank(),
                    match.score(),
                    chunk.getDisclosure().getCompany().getCorpName(),
                    truncate(chunk.getDisclosure().getReportNm(), 18),
                    chunk.getChunkSource(),
                    preview(chunk.getContent()));
        }
    }

    private void printVectorResults(String label, String queryText) {
        List<VectorMatch> matches = vectorSearchService.search(queryText, 10);

        System.out.println();
        System.out.println("=== [" + label + "] \"" + queryText + "\" - 벡터(cosine) 결과 " + matches.size() + "건 ===");
        for (VectorMatch match : matches) {
            Chunk chunk = chunkRepository.findById(match.chunkId()).orElseThrow();
            System.out.printf(
                    "rank=%-2d id=%-6d distance=%-8.4f corp=%-10s report=%-20s source=%-14s content=%s%n",
                    match.rank(),
                    match.chunkId(),
                    match.distance(),
                    chunk.getDisclosure().getCompany().getCorpName(),
                    truncate(chunk.getDisclosure().getReportNm(), 18),
                    chunk.getChunkSource(),
                    preview(chunk.getContent()));
        }
    }

    private String preview(String content) {
        String oneLine = content.replace("\n", " ").strip();
        return oneLine.substring(0, Math.min(60, oneLine.length()));
    }

    private String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
