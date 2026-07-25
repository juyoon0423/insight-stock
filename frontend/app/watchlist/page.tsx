"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import CompanyAutocomplete from "@/components/watchlist/CompanyAutocomplete";
import { ApiError, addToWatchlist, getWatchlist, removeFromWatchlist } from "@/lib/api";
import { useSession } from "@/lib/tokenStore";
import type { CompanySummary, WatchlistItem } from "@/lib/types";

const ERROR_MESSAGES: Record<string, string> = {
  COMPANY_NOT_FOUND: "존재하지 않는 회사입니다.",
  WATCHLIST_ALREADY_EXISTS: "이미 관심 목록에 등록된 회사입니다.",
  WATCHLIST_NOT_FOUND: "이미 삭제된 항목입니다.",
  AUTH_REQUIRED: "로그인이 만료되었습니다. 다시 로그인해주세요.",
  INTERNAL_ERROR: "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
};

function describeError(e: unknown): string {
  if (e instanceof ApiError) {
    return ERROR_MESSAGES[e.errorCode] ?? e.message;
  }
  return "서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요.";
}

export default function WatchlistPage() {
  const session = useSession();
  const [items, setItems] = useState<WatchlistItem[]>([]);
  // "authenticated"가 되면 곧바로 조회를 시작하므로 true로 시작한다 - effect 안에서
  // 동기적으로 setState를 호출하지 않기 위해(react-hooks/set-state-in-effect),
  // 로딩 상태는 초기값으로만 표현하고 완료 시점(.finally)에서만 false로 내린다.
  const [isLoadingList, setIsLoadingList] = useState(true);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pendingCompanyId, setPendingCompanyId] = useState<number | null>(null);
  const [removingId, setRemovingId] = useState<number | null>(null);

  // 로그인 상태가 확정된 뒤에만 조회한다 - "initializing" 단계에서 조회하면 아직
  // 세션 복원 전이라 access token이 없어 불필요한 401을 겪는다. 새로고침 시에도 이
  // effect가 session.status 변화(→ "authenticated")에 반응해 다시 조회하므로
  // 목록이 그대로 유지되는 것처럼 보인다. setState는 전부 .then/.catch/.finally
  // 콜백 안에서만 호출해 effect 본문 자체는 동기적으로 상태를 바꾸지 않는다.
  useEffect(() => {
    if (session.status !== "authenticated") return;
    let cancelled = false;
    getWatchlist()
      .then((res) => {
        if (!cancelled) setItems(res.watchlist);
      })
      .catch((e) => {
        if (!cancelled) setActionError(describeError(e));
      })
      .finally(() => {
        if (!cancelled) setIsLoadingList(false);
      });
    return () => {
      cancelled = true;
    };
  }, [session.status]);

  async function handleSelectCompany(company: CompanySummary) {
    setActionError(null);
    setPendingCompanyId(company.id);
    try {
      const newItem = await addToWatchlist(company.id);
      setItems((prev) => [newItem, ...prev]);
    } catch (e) {
      setActionError(describeError(e));
    } finally {
      setPendingCompanyId(null);
    }
  }

  async function handleRemove(id: number) {
    setActionError(null);
    setRemovingId(id);
    try {
      await removeFromWatchlist(id);
      setItems((prev) => prev.filter((item) => item.id !== id));
    } catch (e) {
      setActionError(describeError(e));
    } finally {
      setRemovingId(null);
    }
  }

  if (session.status === "initializing") {
    return (
      <div className="flex flex-1 justify-center bg-zinc-50 dark:bg-black">
        <main className="w-full max-w-2xl px-4 py-10 sm:px-6">
          <p className="text-center text-sm text-zinc-400 dark:text-zinc-500">불러오는 중...</p>
        </main>
      </div>
    );
  }

  if (session.status === "anonymous") {
    return (
      <div className="flex flex-1 justify-center bg-zinc-50 dark:bg-black">
        <main className="w-full max-w-2xl px-4 py-10 sm:px-6">
          <div className="rounded-lg border border-zinc-200 bg-white p-8 text-center dark:border-zinc-800 dark:bg-zinc-900">
            <p className="text-base font-medium text-zinc-700 dark:text-zinc-200">로그인이 필요합니다</p>
            <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
              관심 회사 목록은 로그인 후 이용할 수 있습니다.
            </p>
            <Link
              href="/login"
              className="mt-4 inline-block rounded-lg bg-zinc-900 px-6 py-3 text-sm font-medium text-white transition-colors hover:bg-zinc-700 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
            >
              로그인하러 가기
            </Link>
          </div>
        </main>
      </div>
    );
  }

  return (
    <div className="flex flex-1 justify-center bg-zinc-50 dark:bg-black">
      <main className="w-full max-w-2xl px-4 py-10 sm:px-6">
        <header className="mb-8">
          <Link
            href="/"
            className="text-xs text-zinc-400 transition-colors hover:text-zinc-600 dark:hover:text-zinc-300"
          >
            ← 메인으로 돌아가기
          </Link>
          <h1 className="mt-2 text-2xl font-bold text-zinc-900 dark:text-zinc-50">관심 회사 목록</h1>
          <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
            회사를 검색해 등록해두세요(현재 수집된 회사 범위 내에서만 검색됩니다).
          </p>
        </header>

        <CompanyAutocomplete
          excludeCompanyIds={items.map((item) => item.company.id)}
          onSelect={handleSelectCompany}
          disabled={pendingCompanyId !== null}
        />

        {actionError && (
          <div className="mt-3 rounded-lg border border-red-200 bg-red-50 p-3 dark:border-red-900/50 dark:bg-red-950/30">
            <p className="text-sm text-red-700 dark:text-red-400">{actionError}</p>
          </div>
        )}

        <section className="mt-8">
          {isLoadingList ? (
            <p className="text-sm text-zinc-400 dark:text-zinc-500">목록을 불러오는 중...</p>
          ) : items.length === 0 ? (
            <p className="text-sm text-zinc-400 dark:text-zinc-500">아직 등록한 회사가 없습니다.</p>
          ) : (
            <ul className="flex flex-col gap-2">
              {items.map((item) => (
                <li
                  key={item.id}
                  className="flex items-center justify-between rounded-lg border border-zinc-200 bg-white px-4 py-3 dark:border-zinc-800 dark:bg-zinc-900"
                >
                  <div>
                    <p className="text-sm font-medium text-zinc-900 dark:text-zinc-100">{item.company.corpName}</p>
                    {item.company.stockCode && (
                      <p className="text-xs text-zinc-400 dark:text-zinc-500">{item.company.stockCode}</p>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={() => handleRemove(item.id)}
                    disabled={removingId === item.id}
                    className="text-xs text-zinc-400 transition-colors hover:text-red-600 disabled:opacity-40 dark:hover:text-red-400"
                  >
                    {removingId === item.id ? "삭제 중..." : "삭제"}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      </main>
    </div>
  );
}
