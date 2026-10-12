"use client";

import { type FormEvent, useState } from "react";

interface SearchQueryFormProps {
  onSubmit: (query: string) => void;
  isLoading: boolean;
}

// GET /api/v1/search로 topK=40까지 미리 조사해서(2026-07-24) 최종 top-10에서
// 매치 패턴 배지가 어떻게 나오는지 실측 확인한 질문들 - 편중형(한쪽 검색만 찾아냄)과
// 합의형(양쪽 일치)을 대비시켜야 "왜 하이브리드가 필요한가"가 한눈에 드러난다.
// - 전환사채: top-10 10개 전부 편중 배지(벡터단독 5 + 키워드단독 5, 합의 0) - 한쪽만
//   썼으면 최종 결과의 절반이 안 보였을 것이라는 가장 극적인 증거.
// - GRT: 키워드단독 3 + 합의 1 - 무관한 회사 청크가 키워드 1위인데도 벡터가 동의하지
//   않아 RRF로 8위까지 밀리는 반대 방향 증명(키워드 단독 1위 = 신뢰 불가).
// - 파라택시스코리아: 합의 5 + 편중 0 - 두 검색이 진짜 일치하면 어떻게 보이는지
//   보여주는 대비용 기준점(메인 페이지 예시 칩과 질문 텍스트는 같지만 배열은 독립적).
const EXAMPLE_QUERIES = [
  "전환사채 발행조건과 전환가액은?",
  "GRT의 유상증자 결정 내용은?",
  "파라택시스코리아의 합병 결정 내용은?",
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
          // 백엔드 검증(검색어 500자 이하)과 같은 값 - 넘겨서 400을 받기 전에 입력 단계에서 막는다.
          maxLength={500}
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
