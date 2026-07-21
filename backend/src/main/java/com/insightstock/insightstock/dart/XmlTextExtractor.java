package com.insightstock.insightstock.dart;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
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

// DART 원문 XML을 정제된 텍스트로 바꾼다. 일부 문서는 본문에 이스케이프되지 않은 '<' 문자가
// 섞여 있어 well-formed XML이 아닐 수 있으므로(dart-findings.md 2-2), 엄격한 파싱이 실패하면
// 정규식 기반 태그 제거로 폴백한다. TABLE은 행 단위로 "셀1 | 셀2" 형태로 직렬화해 라벨-값
// 관계가 깨지지 않게 한다(dart-findings.md 2-4).
@Slf4j
public final class XmlTextExtractor {

    private XmlTextExtractor() {
    }

    public static String extract(String xml) {
        Document document;
        try {
            document = parse(xml);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            log.warn("XML 엄격 파싱 실패, 정규식 태그 제거로 폴백: {}", e.getMessage());
            return normalizeWhitespace(regexStrip(xml));
        }

        StringBuilder text = new StringBuilder();
        walk(document.getDocumentElement(), text);
        return normalizeWhitespace(text.toString());
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

    private static void walk(Element element, StringBuilder text) {
        if ("TABLE".equalsIgnoreCase(element.getTagName())) {
            appendTableRows(element, text);
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

    private static String regexStrip(String xml) {
        return xml.replaceAll("<[^>]+>", " ");
    }

    private static String normalizeWhitespace(String text) {
        return text.replaceAll("[ \\t]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
