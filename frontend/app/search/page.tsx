"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import MatchPatternSummary from "@/components/search/MatchPatternSummary";
import SearchQueryForm from "@/components/search/SearchQueryForm";
import SearchResultList, { TOP_N } from "@/components/search/SearchResultList";
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
    <div className="flex flex-1 justify-center">
      <main id="main-content" className="w-full max-w-2xl px-4 py-10 sm:px-6 sm:py-14">
        <header className="mb-8">
          <p className="text-sm">
            <Link href="/" className="link-quiet">
              ← 질문하기로 돌아가기
            </Link>
          </p>
          <h1 className="mt-3 text-2xl font-semibold text-balance text-ink">하이브리드 검색 원리 보기</h1>
          <p className="mt-2 text-pretty text-ink-muted">
            키워드(BM25) 검색과 벡터(의미) 검색이 각각 매긴 순위, 그리고 RRF로 합쳐진
            최종 순위를 함께 보여줍니다. LLM을 호출하지 않아 비용이 거의 없습니다.
          </p>
        </header>

        <SearchQueryForm onSubmit={runSearch} isLoading={status === "loading"} />

        {status === "loading" && (
          <div role="status" className="mt-8">
            <span className="sr-only">검색 중입니다…</span>
            <div className="animate-pulse space-y-3" aria-hidden="true">
              {[0, 1, 2].map((i) => (
                <div key={i} className="h-24 rounded-md bg-line" />
              ))}
            </div>
          </div>
        )}

        {status === "error" && (
          <div role="alert" className="alert-danger mt-8 flex flex-col items-start gap-3">
            <p>{errorMessage}</p>
            <button type="button" onClick={() => runSearch(lastQuery)} className="btn-secondary">
              다시 시도
            </button>
          </div>
        )}

        {status === "success" && results.length === 0 && (
          <p className="mt-12 text-sm text-ink-muted">검색 결과가 없습니다. 회사명이나 공시 종류를 넣어 다시 검색해 보세요.</p>
        )}

        {status === "success" && results.length > 0 && (
          <div className="mt-8">
            <MatchPatternSummary topResults={results.slice(0, TOP_N)} />
            <SearchResultList results={results} />
          </div>
        )}

        {status === "idle" && (
          <p className="mt-12 text-sm text-ink-subtle">
            질문을 입력하면 키워드/벡터 검색 순위와 RRF 융합 결과를 비교해서 보여줍니다.
          </p>
        )}
      </main>
    </div>
  );
}
