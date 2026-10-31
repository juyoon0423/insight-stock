"use client";

import { type FormEvent, useState } from "react";

interface QuestionFormProps {
  onSubmit: (query: string) => void;
  isLoading: boolean;
}

interface ExampleQuery {
  query: string;
  // 칩 안에 작게 붙는 표시. 답을 일부러 거절하는 질문처럼, 표시가 없으면 버그로 오해할 수 있는 칩에만 쓴다.
  note?: string;
}

// 전시 시연 확정 질문(docs/demo-runbook.md "시연 질문" 표, 2026-10-28 운영 서버에서 확인).
// 앞의 3개는 10월(2026.10.20~10.26) 접수 공시로 답이 나오는 질문이고, 마지막은 주가 예측이라
// 고정 문구로 거절해야 하는 질문이다. 문장을 한 글자라도 바꾸면 검색 결과가 달라질 수 있으니
// 런북 문장 그대로 둔다.
export const EXAMPLE_QUERIES: ExampleQuery[] = [
  { query: "현대로템이 체결한 대만 공급계약의 계약금액과 매출액 대비 비율은?" },
  { query: "신한지주가 소각하기로 한 보통주식 수와 소각예정금액은?" },
  { query: "한화에어로스페이스 KF-21 부품 공급계약 정정으로 계약금액은 얼마에서 얼마로 바뀌었나?" },
  { query: "삼성전자 주가는 다음 달에 오를까요, 내릴까요?", note: "답변 거절 예시" },
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
          // 백엔드 검증(질문 500자 이하)과 같은 값 - 넘겨서 400을 받기 전에 입력 단계에서 막는다.
          maxLength={500}
          // 예시는 운영 검색에서 정답 공시가 1위로 확인된 질문(2026-10-31) - 칩과 겹치지 않게 골랐다.
          placeholder="DART 공시에 대해 질문해보세요 (예: 동아쏘시오홀딩스가 동아제약을 합병하는 목적은 무엇인가?)"
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
        {EXAMPLE_QUERIES.map(({ query: example, note }) => (
          <button
            key={example}
            type="button"
            disabled={isLoading}
            onClick={() => handleExampleClick(example)}
            // 긴 문장은 좁은 화면에서 칩 안에서 줄바꿈되므로 완전한 알약 모양 대신 rounded-2xl + 왼쪽 정렬.
            className="rounded-2xl border border-zinc-300 px-3 py-1 text-left text-xs text-zinc-700 transition-colors hover:border-zinc-400 hover:text-zinc-900 disabled:opacity-40 dark:border-zinc-700 dark:text-zinc-400 dark:hover:border-zinc-500 dark:hover:text-zinc-200"
          >
            {example}
            {note && (
              <span className="ml-1.5 rounded bg-zinc-100 px-1.5 py-0.5 text-[11px] font-medium text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400">
                {note}
              </span>
            )}
          </button>
        ))}
      </div>
    </div>
  );
}
