"use client";

import { useEffect, useState } from "react";
import { searchCompanies } from "@/lib/api";
import type { CompanySummary } from "@/lib/types";

interface CompanyAutocompleteProps {
  // 이미 등록된 회사는 후보에서 미리 제외한다(같은 회사를 다시 클릭해 409를
  // 겪을 일 자체를 줄인다 - 그래도 겪으면 상위 컴포넌트가 에러 메시지로 안내).
  excludeCompanyIds: number[];
  onSelect: (company: CompanySummary) => void;
  disabled?: boolean;
}

const DEBOUNCE_MS = 300;

export default function CompanyAutocomplete({ excludeCompanyIds, onSelect, disabled }: CompanyAutocompleteProps) {
  const [query, setQuery] = useState("");
  const [candidates, setCandidates] = useState<CompanySummary[]>([]);
  const [isSearching, setIsSearching] = useState(false);

  useEffect(() => {
    const trimmed = query.trim();
    if (!trimmed) {
      // 드롭다운 자체가 query.trim() 기준으로 안 그려지니 상태를 안 건드려도 된다 -
      // 다음 실제 검색이 candidates를 새로 채울 때까지 이전 값이 남아있어도 무해하다.
      return;
    }
    // 타이핑마다 요청이 나가지 않도록 마지막 입력 후 DEBOUNCE_MS만큼 기다린다 -
    // 이전 타이머는 다음 키 입력이나 언마운트 시 취소된다. setState는 전부 이
    // 타이머/프로미스 콜백 안에서만 호출한다(effect 본문 자체는 동기적으로
    // 상태를 바꾸지 않는다 - "검색 중" 표시는 onChange 핸들러에서 켠다).
    const timer = setTimeout(() => {
      searchCompanies(trimmed)
        .then((res) => setCandidates(res.companies))
        .catch(() => setCandidates([]))
        .finally(() => setIsSearching(false));
    }, DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [query]);

  const visibleCandidates = candidates.filter((company) => !excludeCompanyIds.includes(company.id));

  function handleQueryChange(value: string) {
    setQuery(value);
    setIsSearching(value.trim().length > 0);
  }

  function handleSelect(company: CompanySummary) {
    onSelect(company);
    setQuery("");
    setCandidates([]);
  }

  return (
    <div className="relative">
      <input
        type="text"
        value={query}
        onChange={(e) => handleQueryChange(e.target.value)}
        disabled={disabled}
        placeholder="회사명으로 검색 (예: 삼성전자)"
        className="w-full rounded-lg border border-zinc-300 bg-white px-4 py-3 text-sm text-zinc-900 placeholder:text-zinc-400 focus:border-zinc-500 focus:outline-none focus:ring-1 focus:ring-zinc-500 disabled:opacity-60 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:placeholder:text-zinc-500"
      />
      {query.trim() && (
        <div className="absolute z-10 mt-1 w-full overflow-hidden rounded-lg border border-zinc-200 bg-white shadow-lg dark:border-zinc-700 dark:bg-zinc-900">
          {isSearching ? (
            <p className="px-4 py-3 text-sm text-zinc-400 dark:text-zinc-500">검색 중...</p>
          ) : visibleCandidates.length === 0 ? (
            <p className="px-4 py-3 text-sm text-zinc-400 dark:text-zinc-500">
              일치하는 회사가 없습니다(현재 수집된 회사 범위 내에서만 검색됩니다).
            </p>
          ) : (
            <ul className="max-h-64 overflow-y-auto">
              {visibleCandidates.map((company) => (
                <li key={company.id}>
                  <button
                    type="button"
                    onClick={() => handleSelect(company)}
                    className="flex w-full items-center justify-between px-4 py-2 text-left text-sm text-zinc-900 transition-colors hover:bg-zinc-100 dark:text-zinc-100 dark:hover:bg-zinc-800"
                  >
                    <span>{company.corpName}</span>
                    {company.stockCode && (
                      <span className="text-xs text-zinc-400 dark:text-zinc-500">{company.stockCode}</span>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
