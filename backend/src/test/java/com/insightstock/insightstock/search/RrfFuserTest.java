package com.insightstock.insightstock.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.insightstock.insightstock.search.dto.KeywordMatch;
import com.insightstock.insightstock.search.dto.RrfScore;
import com.insightstock.insightstock.search.dto.VectorMatch;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

// RrfFuser는 DB/ES/API 의존성이 없는 순수 함수라 실제 인프라 없이 공식 자체를 검증한다.
class RrfFuserTest {

    private static final double DELTA = 1e-9;

    private final RrfFuser fuser = new RrfFuser();

    @Test
    void 두_검색에_다_나온_청크가_더_높은_점수를_받는다() {
        // chunk 1: 키워드 1위 + 벡터 5위 (양쪽 다 등장)
        // chunk 2: 키워드 2위만 (한쪽만 등장, 순위는 chunk 1보다 좋음에도)
        List<KeywordMatch> keywordMatches = List.of(
                new KeywordMatch(1L, 1, 10.0f),
                new KeywordMatch(2L, 2, 9.0f));
        List<VectorMatch> vectorMatches = List.of(new VectorMatch(1L, 5, 0.4));

        List<RrfScore> result = fuser.fuse(keywordMatches, vectorMatches, 60);

        Map<Long, RrfScore> byId = toMap(result);
        assertTrue(byId.get(1L).score() > byId.get(2L).score(),
                "양쪽에 등장한 chunk 1이 한쪽에만 등장한 chunk 2보다 점수가 높아야 한다");
        assertEquals(2L, result.get(0).chunkId(), "최종 정렬 1위는 chunk 1이어야 한다");
    }

    @Test
    void 한쪽에만_나온_청크도_정상적으로_결과에_포함된다() {
        List<KeywordMatch> keywordMatches = List.of(new KeywordMatch(10L, 1, 5.0f));
        List<VectorMatch> vectorMatches = List.of(new VectorMatch(20L, 1, 0.1));

        List<RrfScore> result = fuser.fuse(keywordMatches, vectorMatches, 60);

        assertEquals(2, result.size(), "합집합이므로 두 청크 모두 결과에 있어야 한다");

        Map<Long, RrfScore> byId = toMap(result);
        RrfScore keywordOnly = byId.get(10L);
        assertEquals(1, keywordOnly.keywordRank());
        assertNull(keywordOnly.vectorRank(), "벡터 검색에 등장 안 했으면 vectorRank는 null이어야 한다");

        RrfScore vectorOnly = byId.get(20L);
        assertEquals(1, vectorOnly.vectorRank());
        assertNull(vectorOnly.keywordRank(), "키워드 검색에 등장 안 했으면 keywordRank는 null이어야 한다");

        // 둘 다 각자 검색에서 1위이므로(k 동일) 점수도 같아야 한다.
        assertEquals(keywordOnly.score(), vectorOnly.score(), DELTA);
    }

    @Test
    void 점수가_RRF_공식대로_정확히_계산된다() {
        int k = 60;

        // 단일 검색(키워드 3위)만 등장 -> score = 1/(k+rank)
        List<RrfScore> singleSourceResult =
                fuser.fuse(List.of(new KeywordMatch(1L, 3, 1.0f)), List.of(), k);
        assertEquals(1.0 / (k + 3), singleSourceResult.get(0).score(), DELTA);

        // 양쪽 검색에 등장(키워드 1위 + 벡터 2위) -> score = 1/(k+1) + 1/(k+2)
        List<RrfScore> bothSourceResult = fuser.fuse(
                List.of(new KeywordMatch(2L, 1, 1.0f)),
                List.of(new VectorMatch(2L, 2, 0.1)),
                k);
        double expected = 1.0 / (k + 1) + 1.0 / (k + 2);
        assertEquals(expected, bothSourceResult.get(0).score(), DELTA);
    }

    private Map<Long, RrfScore> toMap(List<RrfScore> scores) {
        return scores.stream().collect(java.util.stream.Collectors.toMap(RrfScore::chunkId, Function.identity()));
    }
}
