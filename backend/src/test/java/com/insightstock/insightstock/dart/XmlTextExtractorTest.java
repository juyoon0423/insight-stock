package com.insightstock.insightstock.dart;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class XmlTextExtractorTest {

    @Test
    void 정상_XML은_1단계_엄격_파싱만으로_추출된다() {
        String xml = """
                <DOC>
                <TITLE>1. 발행회사에 관한 사항</TITLE>
                <TABLE><TR><TD>회사명</TD><TD>주식회사 큐리오시스</TD></TR></TABLE>
                </DOC>
                """;

        String result = XmlTextExtractor.extract(xml);

        assertThat(result).contains("[[SECTION]]", "1. 발행회사에 관한 사항", "회사명 | 주식회사 큐리오시스");
    }

    @Test
    void 이스케이프_안된_앰퍼샌드가_있어도_2단계_정제로_복구되고_문자는_그대로_보존된다() {
        String xml = """
                <DOC>
                <TITLE>수수료 안내</TITLE>
                <TABLE><TR><TD>평가사</TD><TD>NICE P&I</TD></TR></TABLE>
                </DOC>
                """;

        String result = XmlTextExtractor.extract(xml);

        assertThat(result).contains("[[SECTION]]", "NICE P&I");
        assertThat(result).doesNotContain("P&amp;I");
    }

    @Test
    void 가짜_태그처럼_보이는_한글_인용구는_삭제되지_않고_텍스트로_보존된다() {
        String xml = """
                <DOC>
                <TITLE>정의</TITLE>
                <P>제9조 (그 밖의 용어의 정의) <개정 2009.2.3> 이 법에서 다음 각 호를 말한다.</P>
                <P>참고 <표1> 신용환산율 은 아래와 같다.</P>
                </DOC>
                """;

        String result = XmlTextExtractor.extract(xml);

        assertThat(result).contains("<개정 2009.2.3>", "<표1>");
    }

    @Test
    void 이미_유효한_엔티티는_이중으로_이스케이프되지_않는다() {
        String xml = """
                <DOC>
                <P>수익률은 A &amp; B의 합산 &#44; 세전 기준이다.</P>
                </DOC>
                """;

        String result = XmlTextExtractor.extract(xml);

        assertThat(result).contains("A & B", "합산 , 세전");
        assertThat(result).doesNotContain("&amp;amp;", "&amp;#44;");
    }

    @Test
    void html_보일러플레이트처럼_정제로도_복구_안되는_문서는_정규식_폴백으로_내려간다() {
        String xml = """
                <html>
                 <head>
                  <meta content="gdi" http-equiv="X-UA-TextLayoutMetrics">
                  <STYLE>
                .xforms * { font-family: 돋움체;}
                  </STYLE>
                 </head>
                 <body><table><tr><td>1. 증권의 종류</td><td>기명식 보통주</td></tr></table></body>
                </html>
                """;

        String result = XmlTextExtractor.extract(xml);

        assertThat(result).contains("1. 증권의 종류", "기명식 보통주");
        assertThat(result).doesNotContain(".xforms", "font-family");
    }
}
