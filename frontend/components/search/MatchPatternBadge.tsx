import type { MatchPattern } from "@/lib/matchPattern";

// 듀얼 마커와 같은 색(키워드=파랑, 벡터=주황)을 그대로 써서 배지와 마커가
// 같은 언어로 읽히게 한다. 합의는 두 색을 겹쳐 찍어 "둘 다"라는 뜻을 표현.
const STYLES: Record<MatchPattern, { text: string; className: string; dots: string[] }> = {
  "keyword-only": {
    text: "키워드 검색이 찾아냄",
    className: "bg-blue-50 text-blue-700 dark:bg-blue-950/40 dark:text-blue-300",
    dots: ["bg-blue-500"],
  },
  "vector-only": {
    text: "의미 검색이 찾아냄",
    className: "bg-orange-50 text-orange-700 dark:bg-orange-950/40 dark:text-orange-300",
    dots: ["bg-orange-500"],
  },
  consensus: {
    text: "양쪽 검색 일치",
    className: "bg-zinc-100 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300",
    dots: ["bg-blue-500", "bg-orange-500"],
  },
};

export default function MatchPatternBadge({ pattern }: { pattern: MatchPattern }) {
  const { text, className, dots } = STYLES[pattern];
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px] font-medium ${className}`}
    >
      <span className="flex -space-x-0.5">
        {dots.map((dot, i) => (
          <span
            key={i}
            className={`h-1.5 w-1.5 rounded-full border border-white dark:border-zinc-900 ${dot}`}
          />
        ))}
      </span>
      {text}
    </span>
  );
}
