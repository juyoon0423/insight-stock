export interface CitationSegment {
  type: "text" | "citation";
  content: string;
  // citation 타입일 때만 사용 - sources 배열에서의 위치(1-based)
  sourceIndex?: number;
}

const CITATION_PATTERN = /\[(\d+)\]/g;

// 답변 텍스트에 등장하는 원본 인용 번호([3], [1] 등)를 첫 등장 순서 기준으로 1, 2, ...로
// 다시 매긴다. 백엔드 AnswerService가 sources 배열을 만들 때 쓰는 규칙(CitationParser의
// LinkedHashSet - 첫 등장 순서 기준 중복 제거)과 동일해야 sources[i]와 정확히 맞아떨어진다.
export function splitAnswerByCitations(answerText: string, sourceCount: number): CitationSegment[] {
  const segments: CitationSegment[] = [];
  const appearanceOrder = new Map<string, number>();
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  CITATION_PATTERN.lastIndex = 0;
  while ((match = CITATION_PATTERN.exec(answerText)) !== null) {
    if (match.index > lastIndex) {
      segments.push({ type: "text", content: answerText.slice(lastIndex, match.index) });
    }

    const rawNumber = match[1];
    if (!appearanceOrder.has(rawNumber)) {
      appearanceOrder.set(rawNumber, appearanceOrder.size + 1);
    }
    const sourceIndex = appearanceOrder.get(rawNumber)!;

    if (sourceIndex <= sourceCount) {
      segments.push({ type: "citation", content: match[0], sourceIndex });
    } else {
      // sources 배열 범위를 벗어나는 번호는(이론상 발생하지 않아야 함) 원문 그대로 표시
      segments.push({ type: "text", content: match[0] });
    }

    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < answerText.length) {
    segments.push({ type: "text", content: answerText.slice(lastIndex) });
  }

  return segments;
}
