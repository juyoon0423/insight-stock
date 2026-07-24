"use client";

import { useState } from "react";
import type { SearchResultItem } from "@/lib/types";
import RankedResultCard from "./RankedResultCard";

export const TOP_N = 10;

interface SearchResultListProps {
  results: SearchResultItem[];
}

export default function SearchResultList({ results }: SearchResultListProps) {
  const [showMore, setShowMore] = useState(false);
  const top = results.slice(0, TOP_N);
  const rest = results.slice(TOP_N);

  return (
    <div className="space-y-3">
      {top.map((item, i) => (
        <RankedResultCard key={`${item.corpName}-${i}`} item={item} finalRank={i + 1} />
      ))}

      {rest.length > 0 && (
        <div>
          <button
            type="button"
            onClick={() => setShowMore((v) => !v)}
            className="w-full rounded-lg border border-dashed border-zinc-300 py-2 text-xs font-medium text-zinc-500 transition-colors hover:border-zinc-400 hover:text-zinc-700 dark:border-zinc-700 dark:text-zinc-400 dark:hover:text-zinc-200"
          >
            {showMore
              ? "다음 순위 후보 접기"
              : `다음 순위 후보 ${rest.length}건 펼치기 (${TOP_N + 1}~${results.length}위)`}
          </button>
          {showMore && (
            <div className="mt-3 space-y-2">
              {rest.map((item, i) => (
                <RankedResultCard
                  key={`${item.corpName}-${TOP_N + i}`}
                  item={item}
                  finalRank={TOP_N + i + 1}
                  compact
                />
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
