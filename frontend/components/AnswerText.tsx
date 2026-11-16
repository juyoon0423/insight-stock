import { splitAnswerByCitations } from "@/lib/citations";

interface AnswerTextProps {
  answer: string;
  citedNumbers: number[];
}

// 배지로 바꿀지 판단하는 로직은 lib/citations.ts(splitAnswerByCitations)에 있고 여기서는 모양만 정한다.
// citedNumbers에 있는 번호만 배지, 범위 밖 번호([99] 등)는 일반 텍스트 - 이 규칙은 바꾸지 않는다.
export default function AnswerText({ answer, citedNumbers }: AnswerTextProps) {
  const segments = splitAnswerByCitations(answer, citedNumbers);

  // 한글은 어절 단위로 줄바꿈(break-keep), 아주 긴 숫자·영문만 어디서든 끊는다(wrap-anywhere).
  return (
    <p className="whitespace-pre-wrap break-keep wrap-anywhere text-[1.0625rem] leading-[1.85] text-pretty text-ink">
      {segments.map((segment, i) =>
        segment.type === "citation" ? (
          // 브라우저 기본 앵커 이동(#source-N) 그대로 - 대상 출처의 강조는 SourceCard의 :target 스타일이 맡는다.
          <a
            key={i}
            href={`#source-${segment.sourceIndex}`}
            aria-label={`출처 ${segment.sourceIndex}`}
            className="cite-badge cite-badge-inline mx-0.5 no-underline transition-colors hover:bg-evidence hover:text-on-evidence"
          >
            {segment.sourceIndex}
          </a>
        ) : (
          <span key={i}>{segment.content}</span>
        ),
      )}
    </p>
  );
}
