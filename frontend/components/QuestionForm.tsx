"use client";

import { type FormEvent, useState } from "react";

interface QuestionFormProps {
  onSubmit: (query: string) => void;
  isLoading: boolean;
}

// 실제 DB(2026-07-20~21 수집분, GET /api/v1/search로 결과 순도 확인 완료)에서
// 답이 확실히 나오는 질문 2개 + 환각 제어를 보여주는 "못 찾는" 질문 1개.
export const EXAMPLE_QUERIES = [
  "한국투자증권이 발행한 주가연계파생결합사채 상품은 어떤 내용인가요?",
  "파라택시스코리아의 합병 결정 내용은?",
  "테슬라 자율주행 현황은?",
];

export default function QuestionForm({ onSubmit, isLoading }: QuestionFormProps) {
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
          placeholder="DART 공시에 대해 질문해보세요 (예: 삼성전자 최근 유상증자 내용은?)"
          disabled={isLoading}
          className="w-full flex-1 rounded-lg border border-zinc-300 bg-white px-4 py-3 text-sm text-zinc-900 placeholder:text-zinc-400 focus:border-zinc-500 focus:outline-none focus:ring-1 focus:ring-zinc-500 disabled:opacity-60 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:placeholder:text-zinc-500"
        />
        <button
          type="submit"
          disabled={isLoading || !query.trim()}
          className="shrink-0 rounded-lg bg-zinc-900 px-6 py-3 text-sm font-medium text-white transition-colors hover:bg-zinc-700 disabled:cursor-not-allowed disabled:opacity-40 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
        >
          {isLoading ? "답변 생성 중..." : "질문하기"}
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
