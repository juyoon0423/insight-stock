"use client";

import { useState } from "react";
import { classifyMatchPattern } from "@/lib/matchPattern";
import type { SearchResultItem } from "@/lib/types";
import DualRankMarker from "./DualRankMarker";
import MatchPatternBadge from "./MatchPatternBadge";

interface RankedResultCardProps {
  item: SearchResultItem;
  finalRank: number;
  compact?: boolean;
}

const PREVIEW_LENGTH = 180;

export default function RankedResultCard({ item, finalRank, compact = false }: RankedResultCardProps) {
  const [expanded, setExpanded] = useState(false);
  const [showDuplicates, setShowDuplicates] = useState(false);
  const isLong = item.content.length > PREVIEW_LENGTH;
  const displayedContent = expanded || !isLong ? item.content : `${item.content.slice(0, PREVIEW_LENGTH)}…`;
  const duplicateCount = item.duplicateSources.length;
  const matchPattern = classifyMatchPattern(item.keywordRank, item.vectorRank);

  return (
    <div
      className={
        compact
          ? "rounded-md border border-line bg-surface p-3"
          : "rounded-md border border-line bg-surface p-4"
      }
    >
      <div className="mb-2 flex items-start justify-between gap-3">
        <div className="flex min-w-0 items-start gap-2">
          <span className="flex h-6 min-w-6 shrink-0 items-center justify-center rounded-sm bg-ink px-1 text-xs font-semibold tabular-nums text-paper">
            <span className="sr-only">최종 </span>
            {finalRank}
            <span className="sr-only">위</span>
          </span>
          <div className="min-w-0">
            <p className="font-semibold text-ink">{item.corpName}</p>
            <p className="text-sm break-words text-ink-muted">
              {item.reportNm}
              {item.sectionTitle && ` · ${item.sectionTitle}`}
            </p>
          </div>
        </div>
        <span className="shrink-0 text-xs tabular-nums text-ink-subtle">
          RRF {item.rrfScore.toFixed(4)}
        </span>
      </div>

      {matchPattern && (
        <div className="mb-2">
          <MatchPatternBadge pattern={matchPattern} />
        </div>
      )}

      <p className="mb-3 whitespace-pre-wrap break-words text-sm leading-relaxed text-ink">
        {displayedContent}
        {isLong && (
          <button
            type="button"
            onClick={() => setExpanded((v) => !v)}
            aria-expanded={expanded}
            className="ml-1 text-sm font-semibold text-ink-muted underline underline-offset-2 hover:text-ink"
          >
            {expanded ? "접기" : "더보기"}
          </button>
        )}
      </p>

      <DualRankMarker keywordRank={item.keywordRank} vectorRank={item.vectorRank} />

      {duplicateCount > 0 && (
        <div className="mt-3 border-t border-line pt-3">
          <button
            type="button"
            onClick={() => setShowDuplicates((v) => !v)}
            aria-expanded={showDuplicates}
            className="text-sm font-semibold text-ink-muted transition-colors hover:text-ink"
          >
            {showDuplicates ? "관련 공시 접기" : `내용이 동일한 관련 공시 ${duplicateCount}건 보기`}
          </button>
          {showDuplicates && (
            <ul className="mt-2 space-y-1.5">
              {item.duplicateSources.map((source, i) => (
                <li key={`${source.rceptNo}-${i}`}>
                  <a
                    href={source.dartUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="flex items-center justify-between gap-2 rounded-sm bg-paper px-2 py-1.5 text-sm text-ink-muted transition-colors hover:text-evidence"
                  >
                    <span className="min-w-0 break-words">
                      {source.corpName} · {source.reportNm}
                      {source.sectionTitle && ` · ${source.sectionTitle}`}
                    </span>
                    <span className="shrink-0 font-semibold text-evidence underline underline-offset-2">
                      DART 원문<span className="sr-only">(새 창)</span>
                    </span>
                  </a>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
