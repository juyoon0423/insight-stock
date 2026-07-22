package com.insightstock.insightstock.dart;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

// DART 원문 XML을 정제된 텍스트로 바꾼다. 3단계로 시도한다:
//   1) 엄격 파싱 - 대부분의 문서는 이걸로 끝난다.
//   2) 실패하면 well-formedness를 깨는 흔한 패턴(이스케이프 안 된 '&', <표1>/<개정 2009.2.3>
//      같은 가짜 태그 - dart-findings.md 2-2, 2-7)을 정제해서 재시도한다. 성공하면 DOM 경로를
//      그대로 타므로 TABLE 압축(appendTableRows)과 섹션 마커([[SECTION]])를 그대로 얻는다 -
//      정규식 폴백에서 표를 다시 압축하는 것보다 훨씬 정확하다.
//   3) 그래도 실패하면(예: HTML 보일러플레이트 자율공시, dart-findings.md 2-6) 정규식 태그
//      제거로 최종 폴백한다.
// TABLE은 행 단위로 "셀1 | 셀2" 형태로 직렬화해 라벨-값 관계가 깨지지 않게 한다(dart-findings.md 2-4).
@Slf4j
public final class XmlTextExtractor {

    private XmlTextExtractor() {
    }

    public static String extract(String xml) {
        Optional<Document> document = tryParse(xml);
        if (document.isEmpty()) {
            log.warn("XML 엄격 파싱 실패, 정제 후 재시도");
            document = tryParse(sanitize(xml));
        }
        if (document.isEmpty()) {
            log.warn("정제 후에도 파싱 실패, 정규식 태그 제거로 폴백");
            return normalizeWhitespace(regexStrip(xml));
        }

        StringBuilder text = new StringBuilder();
        walk(document.get().getDocumentElement(), text);
        return normalizeWhitespace(text.toString());
    }

    private static Optional<Document> tryParse(String xml) {
        try {
            return Optional.of(parse(xml));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            return Optional.empty();
        }
    }

    private static Document parse(String xml) throws ParserConfigurationException, SAXException, IOException {
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
    }

    // raw_content만으로는 원본 XML의 섹션 구조(TITLE)가 사라지므로, 청킹 단계에서 법정 서식
    // 섹션 경계를 복원할 수 있도록 고정 마커를 남긴다. "[[SECTION]]"은 국문 공시 본문에
    // 나올 일이 없는 문자열이라 충돌 위험이 없다.
    private static final String SECTION_MARKER = "[[SECTION]]";

    private static void walk(Element element, StringBuilder text) {
        if ("TABLE".equalsIgnoreCase(element.getTagName())) {
            appendTableRows(element, text);
            return;
        }
        if ("TITLE".equalsIgnoreCase(element.getTagName())) {
            text.append('\n').append(SECTION_MARKER).append('\n')
                    .append(element.getTextContent().strip()).append('\n');
            return;
        }

        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                text.append(child.getTextContent()).append(' ');
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                walk((Element) child, text);
            }
        }
        text.append('\n');
    }

    private static void appendTableRows(Element table, StringBuilder text) {
        NodeList rows = table.getElementsByTagName("TR");
        for (int r = 0; r < rows.getLength(); r++) {
            Element row = (Element) rows.item(r);
            List<String> cells = new ArrayList<>();
            NodeList cellNodes = row.getChildNodes();
            for (int c = 0; c < cellNodes.getLength(); c++) {
                Node cellNode = cellNodes.item(c);
                if (cellNode.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element cell = (Element) cellNode;
                if ("TD".equalsIgnoreCase(cell.getTagName()) || "TH".equalsIgnoreCase(cell.getTagName())) {
                    cells.add(cell.getTextContent().strip());
                }
            }
            if (!cells.isEmpty()) {
                text.append(String.join(" | ", cells)).append('\n');
            }
        }
    }

    // 이스케이프 안 된 '&'만 &amp;로 바꾼다 - 이미 유효한 엔티티/문자 참조(&amp; &lt; &#44; &#x2C; 등)는
    // 건드리지 않아야 이중 이스케이프(&amp;amp;)로 원문을 훼손하지 않는다.
    private static final Pattern BARE_AMPERSAND =
            Pattern.compile("&(?!(?:amp|lt|gt|apos|quot|#\\d+|#x[0-9a-fA-F]+);)");

    // <...> 덩어리 하나가 "진짜 태그처럼 생겼는지" 판별한다. DART4 스키마의 실제 태그는 전부
    // ASCII 문자로 시작하는 이름을 쓰므로(TABLE, TR, TD, DOCUMENT-NAME 등), <표1>이나
    // <자본시장과 금융투자업에 관한 법률>처럼 한글이 섞인 인용/서술 표기는 이 패턴에 애초에
    // 안 걸려 안전하게 "가짜"로 판별된다. XML 선언(<?xml ...?>)과 주석(<!--...-->)은 별도로
    // 허용하지 않으면 오탐(진짜 구조를 텍스트로 오인)이 나므로 명시적으로 포함한다.
    private static final Pattern REAL_TAG_LIKE = Pattern.compile(
            "(?:</?[A-Za-z][A-Za-z0-9\\-]*(?:\\s[^<>]*)?/?>|<\\?[^<>]*\\?>|<!--.*?-->)",
            Pattern.DOTALL);
    private static final Pattern ANGLE_BRACKET_CHUNK = Pattern.compile("<[^<>]*>");

    // well-formedness를 깨는 두 가지 흔한 패턴만 정제한다. "진짜 태그가 아닌 것으로 판별된
    // <...>"만 이스케이프하므로, 정상적인 구조(TABLE/TR/TD/TITLE 등)는 전혀 건드리지 않는다.
    private static String sanitize(String xml) {
        String withEscapedAmpersands = BARE_AMPERSAND.matcher(xml).replaceAll("&amp;");

        StringBuilder result = new StringBuilder();
        Matcher matcher = ANGLE_BRACKET_CHUNK.matcher(withEscapedAmpersands);
        int lastEnd = 0;
        while (matcher.find()) {
            result.append(withEscapedAmpersands, lastEnd, matcher.start());
            String chunk = matcher.group();
            if (REAL_TAG_LIKE.matcher(chunk).matches()) {
                result.append(chunk);
            } else {
                result.append(chunk.replace("<", "&lt;").replace(">", "&gt;"));
            }
            lastEnd = matcher.end();
        }
        result.append(withEscapedAmpersands, lastEnd, withEscapedAmpersands.length());
        return result.toString();
    }

    // head/style/script는 내용 자체가 태그가 아닌 CSS/JS 텍스트라 <[^>]+>로는 안 지워진다.
    // 여는/닫는 태그 이름이 같아야 매치되도록 백레퍼런스(\1)를 쓴다.
    private static final Pattern NOISE_BLOCK_PATTERN =
            Pattern.compile("<(head|style|script)\\b[^>]*>.*?</\\1\\s*>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static String regexStrip(String xml) {
        String withoutNoiseBlocks = NOISE_BLOCK_PATTERN.matcher(xml).replaceAll(" ");
        return withoutNoiseBlocks.replaceAll("<[^>]+>", " ");
    }

    private static String normalizeWhitespace(String text) {
        return text.replaceAll("[ \\t]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
