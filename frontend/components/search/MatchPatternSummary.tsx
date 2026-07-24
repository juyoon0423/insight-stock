import { describeMatchPatternSummary, summarizeMatchPatterns } from "@/lib/matchPattern";
import type { SearchResultItem } from "@/lib/types";

interface MatchPatternSummaryProps {
  // 카드에 배지가 붙는 것과 같은 범위(top-10)를 넘겨받아야 숫자가 카드와 일치한다.
  topResults: SearchResultItem[];
}

export default function MatchPatternSummary({ topResults }: MatchPatternSummaryProps) {
  const summary = summarizeMatchPatterns(topResults);
  if (summary.total === 0) return null;
  const headline = describeMatchPatternSummary(summary);

  return (
    <div className="mb-4 rounded-lg border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-zinc-900">
      <p className="text-sm font-medium text-zinc-800 dark:text-zinc-200">{headline}</p>
      <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1.5 text-xs text-zinc-500 dark:text-zinc-400">
        <span className="flex items-center gap-1.5">
          <span className="h-1.5 w-1.5 rounded-full bg-blue-500" aria-hidden="true" />
          키워드 단독 {summary.keywordOnly}건
        </span>
        <span className="flex items-center gap-1.5">
          <span className="h-1.5 w-1.5 rounded-full bg-orange-500" aria-hidden="true" />
          의미 단독 {summary.vectorOnly}건
        </span>
        <span className="flex items-center gap-1.5">
          <span className="flex -space-x-0.5">
            <span className="h-1.5 w-1.5 rounded-full border border-white bg-blue-500 dark:border-zinc-900" />
            <span className="h-1.5 w-1.5 rounded-full border border-white bg-orange-500 dark:border-zinc-900" />
          </span>
          양쪽 일치 {summary.consensus}건
        </span>
      </div>
    </div>
  );
}
