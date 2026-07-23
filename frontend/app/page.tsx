"use client";

import { useCallback, useState } from "react";
import QuestionForm from "@/components/QuestionForm";
import ResultPanel, { type ResultStatus } from "@/components/ResultPanel";
import { ApiError, askAnswer } from "@/lib/api";
import type { AnswerResponse } from "@/lib/types";

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
      const result = await askAnswer({ query });
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

  return (
    <div className="flex flex-1 justify-center bg-zinc-50 dark:bg-black">
      <main className="w-full max-w-2xl px-4 py-10 sm:px-6">
        <header className="mb-8 text-center">
          <h1 className="text-2xl font-bold text-zinc-900 dark:text-zinc-50">InsightStock</h1>
          <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
            DART 공시에 근거해 답변합니다 — 모든 답변에는 출처가 함께 표시됩니다.
          </p>
        </header>

        <QuestionForm onSubmit={runQuery} isLoading={status === "loading"} />

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
