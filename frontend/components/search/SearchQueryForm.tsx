"use client";

import { type FormEvent, useState } from "react";

interface SearchQueryFormProps {
  onSubmit: (query: string) => void;
  isLoading: boolean;
}

// GET /api/v1/search로 실제 결과 순도를 미리 확인한 질문들(2026-07-23) - 신영증권은
// 키워드/벡터가 1위로 정확히 합의하는 예시, GRT는 회사 자신의 청크가 키워드 8위/벡터
// 1위로 갈리는 편중 예시라 "왜 하이브리드가 필요한가"를 보여주기 좋다.
const EXAMPLE_QUERIES = [
  "신영증권의 증권발행실적보고서 내용은?",
  "GRT의 유상증자 결정 내용은?",
  "한국투자증권이 발행한 주가연계파생결합사채 상품은 어떤 내용인가요?",
];

export default function SearchQueryForm({ onSubmit, isLoading }: SearchQueryFormProps) {
  const [query, setQuery] = useState("");

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    const trimmed = query.trim();
    if (!trimmed || isLoading) return;
    onSubmit(trimmed);
  }

  function handleExampleClick(example: string) {
    if (isLoading) return;
    setQuery(example);
    onSubmit(example);
  }

  return (
    <div>
      <form onSubmit={handleSubmit} className="flex flex-col gap-2 sm:flex-row">
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="검색어를 입력해보세요 (예: 신영증권 증권발행실적보고서)"
          disabled={isLoading}
          className="w-full flex-1 rounded-lg border border-zinc-300 bg-white px-4 py-3 text-sm text-zinc-900 placeholder:text-zinc-400 focus:border-zinc-500 focus:outline-none focus:ring-1 focus:ring-zinc-500 disabled:opacity-60 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:placeholder:text-zinc-500"
        />
        <button
          type="submit"
          disabled={isLoading || !query.trim()}
          className="shrink-0 rounded-lg bg-zinc-900 px-6 py-3 text-sm font-medium text-white transition-colors hover:bg-zinc-700 disabled:cursor-not-allowed disabled:opacity-40 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
        >
          {isLoading ? "검색 중..." : "검색"}
        </button>
      </form>
      <div className="mt-3 flex flex-wrap gap-2">
        {EXAMPLE_QUERIES.map((example) => (
          <button
            key={example}
            type="button"
            disabled={isLoading}
            onClick={() => handleExampleClick(example)}
            className="rounded-full border border-zinc-200 px-3 py-1 text-xs text-zinc-500 transition-colors hover:border-zinc-400 hover:text-zinc-700 disabled:opacity-40 dark:border-zinc-700 dark:text-zinc-400 dark:hover:border-zinc-500 dark:hover:text-zinc-200"
          >
            {example}
          </button>
        ))}
      </div>
    </div>
  );
}
