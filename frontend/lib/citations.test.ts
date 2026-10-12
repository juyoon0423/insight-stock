import { describe, expect, it } from "vitest";
import { splitAnswerByCitations } from "./citations";

describe("splitAnswerByCitations", () => {
  it("citedNumbers의 위치를 배지 번호로 쓴다", () => {
    // 백엔드가 [3], [1] 순서로 인정 -> sources[0]이 3번 청크, sources[1]이 1번 청크
    const segments = splitAnswerByCitations("배당이 결정됐습니다[3]. 기준일은 연말입니다[1][3].", [3, 1]);

    expect(segments).toEqual([
      { type: "text", content: "배당이 결정됐습니다" },
      { type: "citation", content: "[3]", sourceIndex: 1 },
      { type: "text", content: ". 기준일은 연말입니다" },
      { type: "citation", content: "[1]", sourceIndex: 2 },
      { type: "citation", content: "[3]", sourceIndex: 1 },
      { type: "text", content: "." },
    ]);
  });

  it("범위 밖 번호가 먼저 나와도 뒤의 유효한 인용을 밀어내지 않는다", () => {
    // 회귀 케이스: 예전에는 지어낸 [12]가 "첫 번째로 등장한 인용"이 되어 출처 1번 배지로
    // 표시되고, 진짜 인용인 [2]는 일반 텍스트가 됐다.
    const segments = splitAnswerByCitations("매출이 늘었습니다[12]. 부채비율은 낮아졌습니다[2].", [2]);

    expect(segments).toEqual([
      { type: "text", content: "매출이 늘었습니다" },
      { type: "text", content: "[12]" },
      { type: "text", content: ". 부채비율은 낮아졌습니다" },
      { type: "citation", content: "[2]", sourceIndex: 1 },
      { type: "text", content: "." },
    ]);
  });

  it("본문의 연도 같은 대괄호 숫자는 출처 배지로 만들지 않는다", () => {
    const segments = splitAnswerByCitations("[2024]년 사업보고서 기준입니다[1]. 참고: [0]", [1]);

    const citations = segments.filter((segment) => segment.type === "citation");
    expect(citations).toEqual([{ type: "citation", content: "[1]", sourceIndex: 1 }]);
    // 원문은 한 글자도 잃지 않는다
    expect(segments.map((segment) => segment.content).join("")).toBe(
      "[2024]년 사업보고서 기준입니다[1]. 참고: [0]",
    );
  });

  it("범위 밖 번호와 본문 숫자가 함께 섞여도 유효한 인용만 배지가 된다", () => {
    const segments = splitAnswerByCitations("[2025]년 실적[9]은 개선됐고[2], 배당도 늘었습니다[1][15].", [2, 1]);

    const citations = segments.filter((segment) => segment.type === "citation");
    expect(citations).toEqual([
      { type: "citation", content: "[2]", sourceIndex: 1 },
      { type: "citation", content: "[1]", sourceIndex: 2 },
    ]);
  });

  it("citedNumbers가 비어 있으면 모든 [N]이 텍스트로 남는다", () => {
    const segments = splitAnswerByCitations("근거 없는 문장입니다[1].", []);

    expect(segments.every((segment) => segment.type === "text")).toBe(true);
  });

  it("인용이 없는 답변은 텍스트 한 덩어리다", () => {
    expect(splitAnswerByCitations("찾지 못했습니다.", [])).toEqual([{ type: "text", content: "찾지 못했습니다." }]);
  });
});
