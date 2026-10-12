package com.insightstock.insightstock.rag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightstock.insightstock.search.evaluation.EvaluationQuerySetLoader;
import com.insightstock.insightstock.search.evaluation.GroundTruthQuery;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

// 답변 품질 측정용 데이터 파일의 형식을 비용 0으로 미리 검증한다. 유료 측정
// (AnswerQualityEvaluationManualIT)이 파일 오타 때문에 호출 도중에 실패하면 그때까지의 호출 비용만
// 버리게 되므로, 기본 test에서 먼저 잡는다.
class AnswerQualityQuerySetTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 기대값의_키는_전부_실제_질의_id이고_빈_사실이_없다() throws Exception {
        Set<String> queryIds = new HashSet<>();
        EvaluationQuerySetLoader loader = new EvaluationQuerySetLoader();
        List<GroundTruthQuery> queries = new ArrayList<>(loader.load("evaluation/search-quality-queries.json"));
        queries.addAll(loader.load("evaluation/search-quality-queries-narrative.json"));
        queries.forEach(query -> queryIds.add(query.id()));
        assertThat(queryIds).hasSize(18);

        JsonNode expectedFacts = read("evaluation/answer-quality-expected-facts.json").get("expectedFacts");
        assertThat(expectedFacts.size()).isEqualTo(14);
        expectedFacts.fieldNames().forEachRemaining(id -> {
            assertThat(queryIds).as("기대값 키 %s", id).contains(id);
            JsonNode facts = expectedFacts.get(id);
            assertThat(facts.size()).as("%s의 사실 수", id).isPositive();
            facts.forEach(alternatives -> {
                assertThat(alternatives.size()).as("%s의 허용 표기 수", id).isPositive();
                alternatives.forEach(alt -> assertThat(alt.asText()).isNotBlank());
            });
        });
    }

    @Test
    void 답이_없는_질의는_8개이고_id가_겹치지_않는다() throws Exception {
        JsonNode queries = read("evaluation/answer-quality-unanswerable-queries.json").get("queries");

        Set<String> ids = new HashSet<>();
        queries.forEach(query -> {
            ids.add(query.get("id").asText());
            assertThat(query.get("type").asText()).isNotBlank();
            assertThat(query.get("query").asText()).isNotBlank();
        });
        assertThat(queries.size()).isEqualTo(8);
        assertThat(ids).hasSize(8);
    }

    private JsonNode read(String classpathResource) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            assertThat(in).as(classpathResource).isNotNull();
            return objectMapper.readTree(in);
        }
    }
}
