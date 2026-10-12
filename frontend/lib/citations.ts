export interface CitationSegment {
  type: "text" | "citation";
  content: string;
  // citation 타입일 때만 사용 - sources 배열에서의 위치(1-based)
  sourceIndex?: number;
}

const CITATION_PATTERN = /\[(\d+)\]/g;

// 답변 본문의 [N]을 출처 배지로 바꾼다. 어떤 번호가 진짜 출처인지는 프론트가 추측하지
// 않고 백엔드가 내려준 citedNumbers(AnswerResponse)만 따른다 - citedNumbers[i]번 인용이
// sources[i]를 가리키므로 배지 번호는 그 위치(i + 1)다.
//
// citedNumbers에 없는 대괄호 숫자는 출처가 아니므로 원문 텍스트로 남긴다: 모델이 지어낸
// 범위 밖 번호([12]), 본문의 연도 표기([2024]) 등. 예전에는 프론트가 "첫 등장 순서"로 번호를
// 직접 다시 매겼는데, 백엔드가 버린 번호까지 순서에 끼어들어 지어낸 번호가 엉뚱한 출처
// 배지로 연결되는 버그가 있었다.
export function splitAnswerByCitations(answerText: string, citedNumbers: number[]): CitationSegment[] {
  const segments: CitationSegment[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  CITATION_PATTERN.lastIndex = 0;
  while ((match = CITATION_PATTERN.exec(answerText)) !== null) {
    if (match.index > lastIndex) {
      segments.push({ type: "text", content: answerText.slice(lastIndex, match.index) });
    }

    const position = citedNumbers.indexOf(Number(match[1]));
    if (position >= 0) {
      segments.push({ type: "citation", content: match[0], sourceIndex: position + 1 });
    } else {
      segments.push({ type: "text", content: match[0] });
    }

    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < answerText.length) {
    segments.push({ type: "text", content: answerText.slice(lastIndex) });
  }

  return segments;
}
