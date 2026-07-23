"use client";

import { useState } from "react";
import type { SearchResultItem } from "@/lib/types";
import DualRankMarker from "./DualRankMarker";

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

  return (
    <div
      className={
        compact
          ? "rounded-lg border border-zinc-200 bg-white p-3 dark:border-zinc-800 dark:bg-zinc-900/60"
          : "rounded-lg border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-zinc-900"
      }
    >
      <div className="mb-2 flex items-start justify-between gap-3">
        <div className="flex items-start gap-2">
          <span className="flex h-6 min-w-6 shrink-0 items-center justify-center rounded bg-zinc-900 px-1 text-xs font-semibold text-white dark:bg-zinc-100 dark:text-zinc-900">
            {finalRank}
          </span>
          <div>
            <p className="font-semibold text-zinc-900 dark:text-zinc-100">{item.corpName}</p>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              {item.reportNm}
              {item.sectionTitle && ` · ${item.sectionTitle}`}
            </p>
          </div>
        </div>
        <span className="shrink-0 text-xs text-zinc-400 dark:text-zinc-500">
          RRF {item.rrfScore.toFixed(4)}
        </span>
      </div>

      <p className="mb-3 whitespace-pre-wrap text-sm text-zinc-700 dark:text-zinc-300">
        {displayedContent}
        {isLong && (
          <button
            type="button"
            onClick={() => setExpanded((v) => !v)}
            className="ml-1 text-xs font-medium text-blue-600 hover:underline dark:text-blue-400"
          >
            {expanded ? "접기" : "더보기"}
          </button>
        )}
      </p>

      <DualRankMarker keywordRank={item.keywordRank} vectorRank={item.vectorRank} />

      {duplicateCount > 0 && (
        <div className="mt-3 border-t border-zinc-100 pt-3 dark:border-zinc-800">
          <button
            type="button"
            onClick={() => setShowDuplicates((v) => !v)}
            className="text-xs font-medium text-zinc-500 transition-colors hover:text-zinc-700 dark:text-zinc-400 dark:hover:text-zinc-200"
          >
            {showDuplicates ? "관련 공시 접기" : `📄 내용이 동일한 관련 공시 ${duplicateCount}건 보기`}
          </button>
          {showDuplicates && (
            <ul className="mt-2 space-y-1.5">
              {item.duplicateSources.map((source, i) => (
                <li key={`${source.rceptNo}-${i}`}>
                  <a
                    href={source.dartUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="flex items-center justify-between gap-2 rounded-md bg-zinc-50 px-2 py-1.5 text-xs text-zinc-600 transition-colors hover:bg-zinc-100 hover:text-blue-600 dark:bg-zinc-800/50 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-blue-400"
                  >
                    <span>
                      {source.corpName} · {source.reportNm}
                      {source.sectionTitle && ` · ${source.sectionTitle}`}
                    </span>
                    <span className="shrink-0 underline">DART 원문</span>
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
