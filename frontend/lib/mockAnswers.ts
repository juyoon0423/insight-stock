import { ApiError } from "./api";
import type { AnswerResponse } from "./types";

// NEXT_PUBLIC_USE_MOCK_ANSWERS=true일 때 쓰는 목업. 화면 작업 중 실제
// /api/v1/answers(호출당 약 $0.0015) 호출을 없애기 위한 상시 개발 도구다 -
// 다음 화면 작업 때도 계속 쓰므로 지우지 않는다.
// 예시 질문 칩(components/QuestionForm.tsx의 EXAMPLE_QUERIES)에 대해서만 그럴듯한
// 답변을 매칭한다 - 무관한 질문에 엉뚱한 회사 답변이 나오는 걸 막기 위해 fallback은
// 항상 "찾지 못했습니다"로 통일한다(실제 검색 결과 없음과 같은 정직한 신호).

const NOT_FOUND: Omit<AnswerResponse, "query"> = {
  answer: "제공된 공시 자료에서는 이 질문에 답할 수 있는 내용을 찾지 못했습니다.",
  sources: [],
  citedNumbers: [],
};

// 2026-07-23 실 API 호출로 검증된 응답 형태를 그대로 반영(입력 4,837토큰/출력
// 293토큰, $0.000901). 인용 [3][4] → sources 2건 매핑까지 실제와 동일한 구조.
const KIS_ELS: Omit<AnswerResponse, "query"> = {
  answer:
    "한국투자증권이 발행한 주가연계파생결합사채(ELS)는 기초자산 가격 변동 구간에 따라 수익률이 결정되는 파생결합사채입니다[1]. 발행은 일괄신고 방식으로 진행되며, 세부 발행조건은 매 회차 추가서류로 공시됩니다[2].",
  sources: [
    {
      rceptNo: "20260721000155",
      corpName: "한국투자증권",
      reportNm: "투자설명서(일괄신고)",
      sectionTitle: "상품 개요",
      dartUrl: "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260721000155",
    },
    {
      rceptNo: "20260721000104",
      corpName: "한국투자증권",
      reportNm: "일괄신고추가서류(파생결합사채-주가연계파생결합사채)",
      sectionTitle: null,
      dartUrl: "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260721000104",
    },
  ],
  citedNumbers: [1, 2],
};

// 2026-07-23 실 API 호출로 검증(입력 4,399토큰/출력 232토큰, $0.000799). 실제
// 응답은 출처 4건(같은 공시의 다른 청크, 인용 [1][3][4][8])이었으나 목업은
// 화면 확인 목적상 2건으로 단순화했다.
const PARATAXIS_MERGER: Omit<AnswerResponse, "query"> = {
  answer:
    "파라택시스코리아㈜는 회사합병을 결정했으며, 합병비율은 본질가치평가법에 따라 산정됩니다[1]. 합병 완료 전 파라택시스코리아㈜ 보통주가 상장폐지되는 경우 비상장회사로 전환된 상태로 합병이 진행됩니다[2].",
  sources: [
    {
      rceptNo: "20260720000352",
      corpName: "파라택시스코리아",
      reportNm: "[기재정정]주요사항보고서(회사합병결정)",
      sectionTitle: "합병 비율 산정 근거",
      dartUrl: "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260720000352",
    },
    {
      rceptNo: "20260720000352",
      corpName: "파라택시스코리아",
      reportNm: "[기재정정]주요사항보고서(회사합병결정)",
      sectionTitle: "상장폐지 관련 사항",
      dartUrl: "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260720000352",
    },
  ],
  citedNumbers: [1, 2],
};

// 예시 칩 질문에만 매칭 - 국내 DART 공시 범위 밖 질문(테슬라 등)은 항상 NOT_FOUND.
const QUERY_MOCKS: Record<string, Omit<AnswerResponse, "query">> = {
  "한국투자증권이 발행한 주가연계파생결합사채 상품은 어떤 내용인가요?": KIS_ELS,
  "파라택시스코리아의 합병 결정 내용은?": PARATAXIS_MERGER,
  "테슬라 자율주행 현황은?": NOT_FOUND,
};

// 실제 /answers 처리 시간(약 1.9초)과 비슷하게 지연을 흉내낸다.
export function mockAskAnswer(query: string): Promise<AnswerResponse> {
  return new Promise((resolve, reject) => {
    setTimeout(() => {
      if (query.includes("에러")) {
        reject(new ApiError("답변 생성 중 문제가 발생했습니다.", 502, "LLM_UNAVAILABLE"));
        return;
      }
      const matched = QUERY_MOCKS[query] ?? NOT_FOUND;
      resolve({ query, ...matched });
    }, 1500);
  });
}
