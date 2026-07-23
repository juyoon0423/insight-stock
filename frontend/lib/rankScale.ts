// hybrid-search.candidate-size(backend application.yml)와 동일 - 각 검색(키워드/벡터)이
// RRF 융합 전에 가져오는 후보 개수. keywordRank/vectorRank는 이 범위 안의 값이거나,
// 후보에 아예 없었으면 null이다.
export const CANDIDATE_SIZE = 50;

export interface RankPosition {
  rank: number | null;
  // 트랙 위 위치(%) - 1위=0%, CANDIDATE_SIZE위=100%
  percent: number;
  // true면 이 검색의 top-N 후보에 아예 없었다는 뜻(순위 없음, 51위 이후 어딘가일 수도 있음)
  outOfRange: boolean;
}

export function toRankPosition(rank: number | null): RankPosition {
  if (rank === null) {
    return { rank: null, percent: 100, outOfRange: true };
  }
  const clamped = Math.min(Math.max(rank, 1), CANDIDATE_SIZE);
  const percent = ((clamped - 1) / (CANDIDATE_SIZE - 1)) * 100;
  return { rank, percent, outOfRange: false };
}
