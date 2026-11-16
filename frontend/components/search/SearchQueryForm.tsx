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
      <form onSubmit={handleSubmit}>
        <label htmlFor="search-query" className="mb-2 block text-sm font-semibold text-ink">
          검색어
        </label>
        <div className="flex flex-col gap-2 sm:flex-row">
          <input
            id="search-query"
            name="search-query"
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            // 백엔드 검증(검색어 500자 이하)과 같은 값 - 넘겨서 400을 받기 전에 입력 단계에서 막는다.
            maxLength={500}
            autoComplete="off"
            placeholder="예: 신영증권 증권발행실적보고서"
            disabled={isLoading}
            className="field-input min-w-0 flex-1"
          />
          <button type="submit" disabled={isLoading || !query.trim()} className="btn-primary">
            {isLoading ? "검색 중…" : "검색"}
          </button>
        </div>
      </form>
      <h2 className="mt-6 text-sm font-semibold text-ink">예시 검색어</h2>
      <div className="mt-2 flex flex-wrap gap-2">
        {EXAMPLE_QUERIES.map((example) => (
          <button
            key={example}
            type="button"
            disabled={isLoading}
            onClick={() => handleExampleClick(example)}
            className="rounded-md border border-line-strong bg-surface px-3 py-1.5 text-left text-sm text-ink transition-colors hover:border-ink disabled:cursor-not-allowed disabled:text-ink-subtle"
          >
            {example}
          </button>
        ))}
      </div>
    </div>
  );
}
