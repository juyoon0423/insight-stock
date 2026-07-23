"use client";

import { useState } from "react";
import { ApiError, search } from "@/lib/api";
import type { SearchResponse } from "@/lib/types";

// 백엔드 연결 확인용 임시 페이지. 디자인은 다음 단계에서 다시 만든다.
export default function SearchTestPage() {
  const [query, setQuery] = useState("신영증권 증권발행실적보고서");
  const [result, setResult] = useState<SearchResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function handleSearch() {
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const data = await search(query, 5);
      setResult(data);
    } catch (e) {
      setError(e instanceof ApiError ? `[${e.errorCode}] ${e.message}` : String(e));
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="p-6 font-mono text-sm">
      <h1 className="text-lg font-bold mb-4">백엔드 연결 테스트 (GET /api/v1/search)</h1>
      <div className="flex gap-2 mb-4">
        <input
          className="border px-2 py-1 w-96"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <button
          className="border px-3 py-1 bg-gray-100 disabled:opacity-50"
          onClick={handleSearch}
          disabled={loading}
        >
          {loading ? "검색 중..." : "검색"}
        </button>
      </div>
      {error && <p className="text-red-600 mb-4">{error}</p>}
      {result && <pre className="whitespace-pre-wrap bg-gray-50 p-4">{JSON.stringify(result, null, 2)}</pre>}
    </main>
  );
}
