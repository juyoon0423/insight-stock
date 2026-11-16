import type { MatchPattern } from "@/lib/matchPattern";

// 듀얼 마커와 같은 색(키워드=rank-keyword 청록, 벡터=rank-vector 주황)을 그대로 써서 배지와 마커가
// 같은 언어로 읽히게 한다. 합의는 두 색을 겹쳐 찍어 "둘 다"라는 뜻을 표현.
// 강조색 하나 원칙의 예외 - 두 검색을 색으로 구분하는 것 자체가 정보다(docs/frontend-design.md 3-2절).
const STYLES: Record<MatchPattern, { text: string; className: string; dots: string[] }> = {
  "keyword-only": {
    text: "키워드 검색이 찾아냄",
    className: "bg-rank-keyword-tint text-rank-keyword",
    dots: ["bg-rank-keyword"],
  },
  "vector-only": {
    text: "의미 검색이 찾아냄",
    className: "bg-rank-vector-tint text-rank-vector",
    dots: ["bg-rank-vector"],
  },
  consensus: {
    text: "양쪽 검색 일치",
    className: "bg-paper text-ink border border-line",
    dots: ["bg-rank-keyword", "bg-rank-vector"],
  },
};

export default function MatchPatternBadge({ pattern }: { pattern: MatchPattern }) {
  const { text, className, dots } = STYLES[pattern];
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-semibold ${className}`}
    >
      <span className="flex -space-x-0.5" aria-hidden="true">
        {dots.map((dot, i) => (
          <span
            key={i}
            className={`h-2 w-2 rounded-full border border-surface ${dot}`}
          />
        ))}
      </span>
      {text}
    </span>
  );
}
