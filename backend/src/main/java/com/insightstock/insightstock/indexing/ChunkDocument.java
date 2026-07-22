package com.insightstock.insightstock.indexing;

import com.insightstock.insightstock.domain.ChunkSource;
import com.insightstock.insightstock.domain.ChunkType;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.InnerField;
import org.springframework.data.elasticsearch.annotations.MultiField;
import org.springframework.data.elasticsearch.annotations.Setting;

// chunks 테이블 1행 = ES 문서 1건. pgvector(chunks.embedding)와 역할을 분담한다 -
// 여기는 BM25 키워드 검색용이라 embedding은 넣지 않는다(erd.md 이중 저장 설계).
// _id는 Postgres chunk.id를 그대로 문자열로 써서, 같은 청크를 다시 색인해도
// upsert(덮어쓰기)라 중복 문서가 생기지 않는다(indexing/IndexingService 참고).
//
// Nori 사용자 사전(elasticsearch/chunk-index-settings.json)에 6개 단어만 추가했다 -
// dart-findings.md 2-8절에 각 단어를 넣은 이유(실제 _analyze 테스트로 확인한
// 오분석 사례)를 근거와 함께 기록해뒀다: 보고서/신고서/설명서(report_nm 복합어
// 접미사 18종 공통 해결), 특수관계인/감사인(자모 조각 발생), 이사회(의미 오분석
// "사회"로 잘못 쪼개짐).
@Document(indexName = "chunks")
@Setting(settingPath = "elasticsearch/chunk-index-settings.json")
@Getter
@Setter
@NoArgsConstructor
public class ChunkDocument {

    @Id
    private String id;

    @Field(type = FieldType.Long, name = "disclosure_id")
    private Long disclosureId;

    @Field(type = FieldType.Keyword, name = "rcept_no")
    private String rceptNo;

    @Field(type = FieldType.Keyword, name = "corp_code")
    private String corpCode;

    @MultiField(
            mainField = @Field(type = FieldType.Text, name = "corp_name", analyzer = "insight_nori"),
            otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword))
    private String corpName;

    @MultiField(
            mainField = @Field(type = FieldType.Text, name = "report_nm", analyzer = "insight_nori"),
            otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword))
    private String reportNm;

    // DART가 "yyyyMMdd" 문자열로 주고 기존 코드(Disclosure.rceptDt)도 그대로 String으로
    // 다루고 있어, 새 변환 로직을 추가하지 않고 keyword로 맞췄다. 8자리 고정폭이라
    // 사전식 range 쿼리(gte/lte)로도 날짜 범위 필터가 정확히 동작한다.
    @Field(type = FieldType.Keyword, name = "rcept_dt")
    private String rceptDt;

    @Field(type = FieldType.Text, name = "section_title", analyzer = "insight_nori")
    private String sectionTitle;

    @Field(type = FieldType.Keyword, name = "chunk_type")
    private ChunkType chunkType;

    @Field(type = FieldType.Keyword, name = "chunk_source")
    private ChunkSource chunkSource;

    @Field(type = FieldType.Integer, name = "chunk_index")
    private Integer chunkIndex;

    @Field(type = FieldType.Text, analyzer = "insight_nori")
    private String content;

    @Field(type = FieldType.Date, name = "created_at")
    private Instant createdAt;
}
