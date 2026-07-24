// 카드에 "한쪽 검색만 찾아냄 / 양쪽 합의" 배지를 붙일지 판단하는 순수 함수.
// 임계값은 보수적으로 잡았다 - 애매한 케이스(경계선 순위)까지 분류하면 배지가
// 카드 대부분에 붙어 오히려 눈에 안 띄고 신뢰도도 떨어진다. 배지 없음이 기본이고
// 뚜렷한 경우에만 붙는다(듀얼 마커가 어차피 두 순위를 그대로 보여주므로 정보 손실도 없음).
export type MatchPattern = "keyword-only" | "vector-only" | "consensus";

const STRONG_RANK = 5;
const WEAK_RANK = 20;

export function classifyMatchPattern(
  keywordRank: number | null,
  vectorRank: number | null,
): MatchPattern | null {
  const keywordStrong = keywordRank !== null && keywordRank <= STRONG_RANK;
  const vectorStrong = vectorRank !== null && vectorRank <= STRONG_RANK;
  const keywordWeakOrAbsent = keywordRank === null || keywordRank > WEAK_RANK;
  const vectorWeakOrAbsent = vectorRank === null || vectorRank > WEAK_RANK;

  if (keywordStrong && vectorStrong) return "consensus";
  if (vectorStrong && keywordWeakOrAbsent) return "vector-only";
  if (keywordStrong && vectorWeakOrAbsent) return "keyword-only";
  return null;
}

export interface MatchPatternSummary {
  total: number;
  consensus: number;
  keywordOnly: number;
  vectorOnly: number;
}

// 카드별 배지와 같은 로직(classifyMatchPattern)으로 상단 요약 통계를 낸다 -
// 카드에 보이는 배지 개수와 요약 숫자가 항상 일치해야 신뢰가 생긴다.
export function summarizeMatchPatterns(
  results: { keywordRank: number | null; vectorRank: number | null }[],
): MatchPatternSummary {
  const summary: MatchPatternSummary = { total: results.length, consensus: 0, keywordOnly: 0, vectorOnly: 0 };
  for (const { keywordRank, vectorRank } of results) {
    const pattern = classifyMatchPattern(keywordRank, vectorRank);
    if (pattern === "consensus") summary.consensus++;
    else if (pattern === "keyword-only") summary.keywordOnly++;
    else if (pattern === "vector-only") summary.vectorOnly++;
  }
  return summary;
}

// 데이터에 따라 문구가 자연스럽게 바뀌는 요약 한 줄 - 극단적인 케이스(합의 0건,
// 편중 0건)는 별도로 강조하고, 나머지는 일반형 문장으로 폴백한다.
export function describeMatchPatternSummary(summary: MatchPatternSummary): string {
  const { total, consensus, keywordOnly, vectorOnly } = summary;
  const oneSided = keywordOnly + vectorOnly;
  if (total === 0) return "";

  if (consensus === 0 && oneSided === total) {
    return `이 질의에서는 두 검색 결과가 하나도 겹치지 않았습니다 — 키워드 검색이 ${keywordOnly}건, 의미 검색이 ${vectorOnly}건을 각각 단독으로 찾아냈습니다.`;
  }
  if (oneSided === 0 && consensus > 0) {
    return "이 질의에서는 편중된 결과가 하나도 없었습니다 — 상위 결과에서 두 검색이 계속 같은 곳을 가리켰습니다.";
  }
  if (oneSided === 0 && consensus === 0) {
    return "이 질의에서는 뚜렷하게 편중되거나 합의된 결과가 없었습니다 — 두 검색의 순위가 고르게 섞여 있습니다.";
  }

  const foundParts: string[] = [];
  if (keywordOnly > 0) foundParts.push(`키워드 검색이 ${keywordOnly}건`);
  if (vectorOnly > 0) foundParts.push(`의미 검색이 ${vectorOnly}건`);
  const foundText =
    foundParts.length > 0
      ? `${foundParts.join(", ")}을 ${foundParts.length > 1 ? "각각 " : ""}단독으로 찾아냈고, `
      : "";
  const consensusText =
    consensus > 0 ? `${consensus}건은 양쪽 검색이 일치했습니다.` : "양쪽이 일치한 결과는 없었습니다.";

  return `이 질의에서는 ${foundText}${consensusText}`;
}
