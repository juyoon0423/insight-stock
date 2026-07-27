package com.insightstock.insightstock.search.evaluation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

// src/test/resources/evaluation/search-quality-queries.json을 GroundTruthQuery
// 리스트로 읽어온다. DB/서비스 의존성이 없는 순수 파싱 로직이라 Spring 빈으로 등록하지
// 않고 ManualIT에서 직접 new해서 쓴다.
public class EvaluationQuerySetLoader {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<GroundTruthQuery> load(String classpathResource) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("질의 세트 파일을 찾을 수 없습니다: " + classpathResource);
            }
            QuerySetFile file = objectMapper.readValue(in, QuerySetFile.class);
            return file.queries().stream().map(this::toGroundTruthQuery).toList();
        } catch (IOException e) {
            throw new IllegalStateException("질의 세트 파일을 읽는 중 오류: " + classpathResource, e);
        }
    }

    private GroundTruthQuery toGroundTruthQuery(QueryCaseJson json) {
        return new GroundTruthQuery(
                json.id(),
                json.category(),
                json.theme(),
                json.query(),
                json.lexicalMismatch(),
                json.mismatchNote(),
                json.groundTruth().corpCode(),
                json.groundTruth().corpName(),
                json.groundTruth().reportNm(),
                json.groundTruth().rceptNo());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QuerySetFile(List<QueryCaseJson> queries) {
    }

    // category/theme 둘 다 optional - 파일에 없으면 null(GroundTruthQuery 참고).
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QueryCaseJson(
            String id,
            QueryCategory category,
            String theme,
            String query,
            boolean lexicalMismatch,
            String mismatchNote,
            GroundTruthJson groundTruth) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GroundTruthJson(String corpCode, String corpName, String reportNm, String rceptNo) {
    }
}
