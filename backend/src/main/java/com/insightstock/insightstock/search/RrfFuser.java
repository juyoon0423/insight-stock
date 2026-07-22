package com.insightstock.insightstock.search;

import com.insightstock.insightstock.search.dto.KeywordMatch;
import com.insightstock.insightstock.search.dto.RrfScore;
import com.insightstock.insightstock.search.dto.VectorMatch;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

// RRF(Reciprocal Rank Fusion) - score(chunk) = sum(1 / (k + rank)), 각 검색에 등장한
// rank에 대해서만 더한다. 등장 안 한 검색의 기여는 0이라 별도 분기 없이 "한쪽에만 나온
// 청크"가 자연히 처리된다(design 논의 참고). DB/ES/API 의존성이 없는 순수 함수라
// 단위 테스트로 공식 자체를 검증할 수 있다.
@Component
public class RrfFuser {

    public List<RrfScore> fuse(List<KeywordMatch> keywordMatches, List<VectorMatch> vectorMatches, int k) {
        Map<Long, Integer> keywordRanks = keywordMatches.stream()
                .collect(Collectors.toMap(KeywordMatch::chunkId, KeywordMatch::rank));
        Map<Long, Integer> vectorRanks = vectorMatches.stream()
                .collect(Collectors.toMap(VectorMatch::chunkId, VectorMatch::rank));

        Map<Long, Double> scores = new HashMap<>();
        keywordRanks.forEach((chunkId, rank) -> scores.merge(chunkId, 1.0 / (k + rank), Double::sum));
        vectorRanks.forEach((chunkId, rank) -> scores.merge(chunkId, 1.0 / (k + rank), Double::sum));

        return scores.entrySet().stream()
                .map(entry -> new RrfScore(
                        entry.getKey(),
                        entry.getValue(),
                        keywordRanks.get(entry.getKey()),
                        vectorRanks.get(entry.getKey())))
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .toList();
    }
}
