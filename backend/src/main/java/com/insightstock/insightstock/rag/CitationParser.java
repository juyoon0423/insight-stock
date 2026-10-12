package com.insightstock.insightstock.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

// 모델 답변 텍스트에서 "[1]", "[3]" 같은 인용 번호를 추출하는 순수 함수.
// DB/API 의존성이 없어 RrfFuser와 같은 방식으로 단위 테스트만으로 검증한다.
@Component
public class CitationParser {

    // 자릿수를 9자리로 제한한다 - 제한이 없으면 "[99999999999]" 같은 텍스트에서
    // Integer.parseInt가 NumberFormatException을 던져 답변 전체가 500이 된다. 9자리까지는
    // int 범위 안이고, 실제 인용 번호(최대 50)에는 한참 여유가 있다.
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d{1,9})]");

    // 답변에 처음 등장한 순서대로, 중복 없이 인용 번호를 반환한다.
    public List<Integer> extractCitedNumbers(String answerText) {
        LinkedHashSet<Integer> citedNumbers = new LinkedHashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(answerText);
        while (matcher.find()) {
            citedNumbers.add(Integer.parseInt(matcher.group(1)));
        }
        return new ArrayList<>(citedNumbers);
    }
}
