"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import AnswerPreview from "@/components/AnswerPreview";
import QuestionForm from "@/components/QuestionForm";
import ResultPanel, { type ResultStatus } from "@/components/ResultPanel";
import { ApiError, askAnswer } from "@/lib/api";
import { mockAskAnswer } from "@/lib/mockAnswers";
import type { AnswerResponse } from "@/lib/types";

// 화면 작업 중 실제 /answers 호출(약 $0.0015)을 없애기 위한 토글. .env.local에서만
// true로 켜고, .env.local.example 기본값은 false(실수로 목업인 채 배포되는 것 방지).
const USE_MOCK = process.env.NEXT_PUBLIC_USE_MOCK_ANSWERS === "true";

const ERROR_MESSAGES: Record<string, string> = {
  VALIDATION_ERROR: "질문을 다시 확인해주세요.",
  LLM_UNAVAILABLE: "답변 생성 중 문제가 발생했습니다. 잠시 후 다시 시도해주세요.",
  SEARCH_UNAVAILABLE: "검색 중 문제가 발생했습니다. 잠시 후 다시 시도해주세요.",
  INTERNAL_ERROR: "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
};

export default function Home() {
  const [status, setStatus] = useState<ResultStatus>("idle");
  const [data, setData] = useState<AnswerResponse | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [lastQuery, setLastQuery] = useState("");

  const runQuery = useCallback(async (query: string) => {
    setLastQuery(query);
    setStatus("loading");
    setErrorMessage(null);
    try {
      const result = USE_MOCK ? await mockAskAnswer(query) : await askAnswer({ query });
      setData(result);
      setStatus("success");
    } catch (e) {
      if (e instanceof ApiError) {
        setErrorMessage(ERROR_MESSAGES[e.errorCode] ?? e.message);
      } else {
        setErrorMessage("서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요.");
      }
      setStatus("error");
    }
  }, []);

  // 레이아웃 근거: docs/frontend-design.md 3-4절, 8절. 헤더와 같은 폭(max-w-5xl)을 쓰고, 넓은 화면에서는
  // 왼쪽에 질문, 오른쪽 칸(20rem, 답변 화면의 근거 공시 칸과 같은 폭)에 답변 미리보기를 둔다.
  // 좁은 화면에서는 입력창 → 예시 질문 → 미리보기 순서로 쌓인다(예시 질문이 첫 화면에 보이도록).
  // 질문을 하면 실제 답변과 헷갈리지 않게 미리보기를 숨긴다.
  return (
    <div className="flex flex-1 justify-center">
      <main id="main-content" className="w-full max-w-5xl px-4 py-10 sm:px-6 sm:py-14">
        <div className="grid gap-10 lg:grid-cols-[minmax(0,1fr)_20rem]">
          <div>
            <header className="mb-8">
              <h1 className="text-2xl font-semibold text-balance text-ink">DART 공시에 근거해 답합니다</h1>
              <p className="mt-2 text-pretty text-ink-muted">
                모든 답변에는 출처가 함께 표시됩니다. 답변 속 번호를 누르면 근거가 된 공시로 이동합니다.
              </p>
              {/* 목업이 정상 답변하는 질문은 lib/mockAnswers.ts의 7월 공시 질문 2개뿐이다(예시 질문은 모두 "찾지 못함") */}
              {USE_MOCK && (
                <p className="mt-3 inline-block rounded-sm border border-line-strong px-2 py-0.5 text-xs text-ink-muted">
                  목업 모드: 7월 공시 질문 2개만 정상 응답합니다
                </p>
              )}
            </header>

            <QuestionForm onSubmit={runQuery} isLoading={status === "loading"} />

            <p className="mt-6 text-sm">
              <Link href="/search" className="link-quiet underline">
                하이브리드 검색이 어떻게 순위를 매기는지 보기
              </Link>
            </p>
          </div>

          {status === "idle" && <AnswerPreview />}
        </div>

        <ResultPanel
          status={status}
          data={data}
          errorMessage={errorMessage}
          onRetry={() => runQuery(lastQuery)}
        />
      </main>
    </div>
  );
}
