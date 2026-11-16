"use client";

import { type FormEvent, useRef, useState } from "react";

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
  const inputRef = useRef<HTMLInputElement>(null);

  // 버튼은 빈 입력에서도 눌리는 모양(군청 채움)으로 둔다. 비었거나 공백뿐이면 API를 부르지 않고
  // 입력창으로 포커스만 옮긴다 - "무엇을 하면 되는지"를 비활성 버튼 대신 커서로 알려 준다.
  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (isLoading) return;
    const trimmed = query.trim();
    if (!trimmed) {
      inputRef.current?.focus();
      return;
    }
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
        <label htmlFor="question" className="mb-2 block text-sm font-semibold text-ink">
          질문
        </label>
        <div className="flex flex-col gap-2 sm:flex-row">
          <input
            ref={inputRef}
            id="question"
            name="question"
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            // 백엔드 검증(질문 500자 이하)과 같은 값 - 넘겨서 400을 받기 전에 입력 단계에서 막는다.
            maxLength={500}
            // 비밀번호 관리자가 이 칸을 로그인 칸으로 오인하지 않게
            autoComplete="off"
            aria-describedby="question-scope"
            // 예시는 운영 검색에서 정답 공시가 1위로 확인된 질문(2026-10-31) - 칩과 겹치지 않게 골랐다.
            placeholder="예: 동아쏘시오홀딩스가 동아제약을 합병하는 목적은 무엇인가?"
            disabled={isLoading}
            className="field-input min-w-0 flex-1"
          />
          <button type="submit" disabled={isLoading} className="btn-primary">
            {isLoading ? "답변 생성 중…" : "질문하기"}
          </button>
        </div>
      </form>
      {/* 운영 DB의 INDEXED 공시 접수일 범위(2026-10-31 조회: 7월 20~24일 3,715건, 10월 20~26일 2,547건).
          색인 데이터를 추가하면 함께 고친다. */}
      <p id="question-scope" className="mt-2 text-sm tabular-nums text-ink-muted">
        수집 범위: 2026.07.20~24, 2026.10.20~26 접수 공시
      </p>

      {/* 예시 질문: 칩 대신 줄 단위 목록 - 긴 문장도 한 줄씩 읽히고, 누를 수 있는 영역이 넓다 */}
      <h2 className="mt-8 text-sm font-semibold text-ink">예시 질문</h2>
      <ul className="mt-2 border-t border-line">
        {EXAMPLE_QUERIES.map(({ query: example, note }) => (
          <li key={example} className="border-b border-line">
            <button
              type="button"
              disabled={isLoading}
              onClick={() => handleExampleClick(example)}
              className="group flex w-full items-baseline justify-between gap-3 px-2 py-3 text-left text-[0.9375rem] text-ink transition-colors hover:bg-wash disabled:cursor-not-allowed disabled:text-ink-subtle disabled:hover:bg-transparent"
            >
              <span className="text-pretty">{example}</span>
              <span className="flex shrink-0 items-baseline gap-3">
                {note && (
                  <span className="rounded-sm border border-line-strong px-1.5 text-xs text-ink-muted">{note}</span>
                )}
                {/* 누를 수 있는 줄이라는 표시. 화면 낭독기는 버튼 이름만 읽도록 숨긴다 */}
                <span
                  aria-hidden="true"
                  className="text-ink-subtle transition-transform group-hover:translate-x-0.5 group-hover:text-ink"
                >
                  →
                </span>
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
