package com.insightstock.insightstock.rag;

import com.insightstock.insightstock.rag.dto.AnswerResponse;
import com.insightstock.insightstock.rag.dto.SourceRef;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 실제 OpenAI Chat Completions API를 호출하는 수동 검증용 - 비용이 드므로 CI/자동 실행
// 대상이 아니다. 확인할 세 가지:
// 1) 컨텍스트에 답이 있는 질문 -> 정확히 답하고 출처가 맞는가
// 2) 컨텍스트에 답이 없는 질문(DART와 무관한 회사) -> "찾지 못했습니다"라고 하는가
// 3) 출처 링크(dartUrl)가 실제 DART 원문 뷰어 URL 형식인가
@SpringBootTest
class AnswerServiceManualIT {

    @Autowired
    private AnswerService answerService;

    @Test
    void 컨텍스트에_답이_있는_질문() {
        AnswerResponse response = answerService.answer("한국투자증권이 발행한 주가연계파생결합사채 상품은 어떤 내용인가요?");
        print("답이 있는 질문", response);
    }

    @Test
    void 컨텍스트에_답이_없는_질문() {
        AnswerResponse response = answerService.answer("테슬라의 최근 자율주행 기술 개발 현황은 어떤가요?");
        print("답이 없는 질문(DART 무관)", response);
    }

    private void print(String label, AnswerResponse response) {
        System.out.println();
        System.out.println("=== [" + label + "] ===");
        System.out.println("답변:\n" + response.answer());
        System.out.println("\n출처 " + response.sources().size() + "건:");
        for (SourceRef source : response.sources()) {
            System.out.printf(
                    "- %s / %s / %s -> %s%n",
                    source.corpName(), source.reportNm(), source.sectionTitle(), source.dartUrl());
        }
    }
}
