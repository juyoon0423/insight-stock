package com.insightstock.insightstock.rag.dto;

import java.util.List;

// citedNumbers: 답변 본문의 [N] 중 실제 컨텍스트 범위(1~컨텍스트 청크 수) 안에 있어 출처로
// 인정된 번호만, 본문에 처음 등장한 순서대로 담는다. sources와 길이·순서가 같아서
// citedNumbers[i]번 인용이 sources[i]를 가리킨다. 여기 없는 대괄호 숫자(모델이 지어낸
// 범위 밖 번호, 본문의 "[2024]" 같은 표기)는 출처가 아니므로 프론트가 배지로 만들면 안 된다 -
// 프론트가 번호를 스스로 추측하지 않도록 서버가 판정 결과를 그대로 내려준다.
public record AnswerResponse(String query, String answer, List<SourceRef> sources, List<Integer> citedNumbers) {
}
