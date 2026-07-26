package com.insightstock.insightstock.collector;

import com.insightstock.insightstock.dart.DartApiException;
import com.insightstock.insightstock.dart.DartCorpCodeClient;
import com.insightstock.insightstock.dart.dto.CorpCodeRecord;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

// DART가 제공하는 전체 회사 목록(corpCode.xml)을 companies 테이블과 동기화한다.
// list/document API와 달리 페이징 없는 전체 스냅샷(실측 118,535건, dart-findings.md 2-11)이라
// 증분 수집이 아니라 "가끔(하루 한 번 정도) 전체를 다시 훑는" 성격의 배치다.
//
// corp_cls(법인구분)는 corpCode.xml에 없는 필드라(2-11) UPSERT 구문에서 아예 참조하지 않는다
// - SQL 구조 자체가 기존 값을 절대 건드리지 않으므로, 이미 채워진 corp_cls는 그대로 보존되고
// 아직 없는 회사는 나중에 DisclosureCollectorService가 실제 공시를 만날 때 채운다.
@Slf4j
@Service
public class CorpCodeSyncService {

    private static final String ZIP_ENTRY_NAME = "CORPCODE.xml";
    private static final int UPSERT_BATCH_SIZE = 1000;

    // corp_cls는 의도적으로 SET 절에 없다 - 주석 참고.
    private static final String UPSERT_SQL = """
            INSERT INTO companies (corp_code, corp_name, stock_code, created_at, updated_at)
            VALUES (?, ?, ?, now(), now())
            ON CONFLICT (corp_code) DO UPDATE SET
                corp_name = EXCLUDED.corp_name,
                stock_code = EXCLUDED.stock_code,
                updated_at = now()
            """;

    private final DartCorpCodeClient dartCorpCodeClient;
    private final JdbcTemplate jdbcTemplate;

    public CorpCodeSyncService(DartCorpCodeClient dartCorpCodeClient, JdbcTemplate jdbcTemplate) {
        this.dartCorpCodeClient = dartCorpCodeClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void syncAll() {
        List<CorpCodeRecord> records = fetchAndParse();
        int processed = upsert(records);
        log.info("corpCode.xml 동기화 완료: {}건 처리", processed);
    }

    // 파일럿 검증(소규모 subList upsert)을 위해 fetch/parse와 upsert를 분리해둔다.
    List<CorpCodeRecord> fetchAndParse() {
        byte[] zipBytes = dartCorpCodeClient.fetchCorpCode();
        String xml = extractXmlFromZip(zipBytes);
        return parse(xml);
    }

    int upsert(List<CorpCodeRecord> records) {
        int processed = 0;
        for (int i = 0; i < records.size(); i += UPSERT_BATCH_SIZE) {
            List<CorpCodeRecord> chunk = records.subList(i, Math.min(i + UPSERT_BATCH_SIZE, records.size()));
            List<Object[]> args = chunk.stream()
                    .map(r -> new Object[] {r.corpCode(), r.corpName(), r.stockCode()})
                    .toList();
            jdbcTemplate.batchUpdate(UPSERT_SQL, args);
            processed += chunk.size();
            log.info("corpCode.xml upsert 진행: {}/{}", processed, records.size());
        }
        return processed;
    }

    private String extractXmlFromZip(byte[] zipBytes) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(ZIP_ENTRY_NAME)) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new DartApiException("corpCode.xml ZIP 압축 해제 실패", e);
        }
        throw new DartApiException("ZIP 안에 %s 항목이 없습니다.".formatted(ZIP_ENTRY_NAME));
    }

    private List<CorpCodeRecord> parse(String xml) {
        Document document = parseXml(xml);
        NodeList listNodes = document.getElementsByTagName("list");
        List<CorpCodeRecord> records = new ArrayList<>(listNodes.getLength());
        for (int i = 0; i < listNodes.getLength(); i++) {
            Element item = (Element) listNodes.item(i);
            String corpCode = textOf(item, "corp_code");
            String corpName = textOf(item, "corp_name");
            String stockCode = blankToNull(textOf(item, "stock_code"));
            records.add(new CorpCodeRecord(corpCode, corpName, stockCode));
        }
        return records;
    }

    private String textOf(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        // .strip()이 탭/개행 등 공백 전반을 제거한다 - 실측(2-11)에서 corp_name에 탭 문자가
        // 섞인 사례를 발견해 trim이 필수임을 확인했다.
        return nodes.item(0).getTextContent().strip();
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    // corpCode.xml은 document API 원문과 달리 실측(2-11)에서 전량 well-formed였으므로
    // XmlTextExtractor처럼 정제/정규식 폴백 단계 없이 엄격 파싱만 사용한다.
    private Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE 방지: DOCTYPE 선언과 외부 엔티티 참조를 비활성화한다.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new DartApiException("corpCode.xml 파싱 실패", e);
        }
    }
}
