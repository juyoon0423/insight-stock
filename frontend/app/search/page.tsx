"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import SearchQueryForm from "@/components/search/SearchQueryForm";
import SearchResultList from "@/components/search/SearchResultList";
import { ApiError, search } from "@/lib/api";
import type { SearchResultItem } from "@/lib/types";

// top-10만 요청하면 "한쪽에서만 강했지만 최종 순위에서 밀려난 청크"가 응답에 아예
// 안 잡힌다 - 11~20위까지 받아서 "다음 순위 후보"로 보여줘야 그 대비가 드러난다.
const TOP_K = 20;

type Status = "idle" | "loading" | "success" | "error";

const ERROR_MESSAGES: Record<string, string> = {
  VALIDATION_ERROR: "검색어를 다시 확인해주세요.",
  SEARCH_UNAVAILABLE: "검색 중 문제가 발생했습니다. 잠시 후 다시 시도해주세요.",
  INTERNAL_ERROR: "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
};

export default function SearchPage() {
  const [status, setStatus] = useState<Status>("idle");
  const [results, setResults] = useState<SearchResultItem[]>([]);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [lastQuery, setLastQuery] = useState("");

  const runSearch = useCallback(async (query: string) => {
    setLastQuery(query);
    setStatus("loading");
    setErrorMessage(null);
    try {
      const response = await search(query, TOP_K);
      setResults(response.results);
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
        <header className="mb-8">
          <Link
            href="/"
            className="text-xs text-zinc-400 transition-colors hover:text-zinc-600 dark:hover:text-zinc-300"
          >
            ← 질문하기로 돌아가기
          </Link>
          <h1 className="mt-2 text-2xl font-bold text-zinc-900 dark:text-zinc-50">
            하이브리드 검색 원리 보기
          </h1>
          <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
            키워드(BM25) 검색과 벡터(의미) 검색이 각각 매긴 순위, 그리고 RRF로 합쳐진
            최종 순위를 함께 보여줍니다. LLM을 호출하지 않아 비용이 거의 없습니다.
          </p>
        </header>

        <SearchQueryForm onSubmit={runSearch} isLoading={status === "loading"} />

        {status === "loading" && (
          <div className="mt-6 animate-pulse space-y-3" aria-busy="true" aria-label="검색 중">
            {[0, 1, 2].map((i) => (
              <div key={i} className="h-24 rounded-lg bg-zinc-200 dark:bg-zinc-800" />
            ))}
          </div>
        )}

        {status === "error" && (
          <div className="mt-6 flex flex-col items-start gap-3 rounded-lg border border-red-200 bg-red-50 p-4 dark:border-red-900/50 dark:bg-red-950/30">
            <p className="text-sm text-red-700 dark:text-red-400">{errorMessage}</p>
            <button
              onClick={() => runSearch(lastQuery)}
              className="rounded-md border border-red-300 px-3 py-1.5 text-xs font-medium text-red-700 transition-colors hover:bg-red-100 dark:border-red-800 dark:text-red-300 dark:hover:bg-red-900/40"
            >
              다시 시도
            </button>
          </div>
        )}

        {status === "success" && results.length === 0 && (
          <p className="mt-16 text-center text-sm text-zinc-400 dark:text-zinc-500">
            검색 결과가 없습니다.
          </p>
        )}

        {status === "success" && results.length > 0 && (
          <div className="mt-6">
            <SearchResultList results={results} />
          </div>
        )}

        {status === "idle" && (
          <p className="py-16 text-center text-sm text-zinc-400 dark:text-zinc-500">
            질문을 입력하면 키워드/벡터 검색 순위와 RRF 융합 결과를 비교해서 보여줍니다.
          </p>
        )}
      </main>
    </div>
  );
}
