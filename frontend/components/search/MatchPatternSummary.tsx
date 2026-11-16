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
    <div className="mb-4 rounded-md border border-line bg-surface p-4">
      <p className="text-sm font-semibold text-pretty text-ink">{headline}</p>
      <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1.5 text-sm tabular-nums text-ink-muted">
        <span className="flex items-center gap-1.5">
          <span className="h-2 w-2 rounded-full bg-rank-keyword" aria-hidden="true" />
          키워드 단독 {summary.keywordOnly}건
        </span>
        <span className="flex items-center gap-1.5">
          <span className="h-2 w-2 rounded-full bg-rank-vector" aria-hidden="true" />
          의미 단독 {summary.vectorOnly}건
        </span>
        <span className="flex items-center gap-1.5">
          <span className="flex -space-x-0.5" aria-hidden="true">
            <span className="h-2 w-2 rounded-full border border-surface bg-rank-keyword" />
            <span className="h-2 w-2 rounded-full border border-surface bg-rank-vector" />
          </span>
          양쪽 일치 {summary.consensus}건
        </span>
      </div>
    </div>
  );
}
