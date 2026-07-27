"use client";

import Link from "next/link";
import { logout } from "@/lib/api";
import { useSession } from "@/lib/tokenStore";
import NotificationBell from "@/components/NotificationBell";

// 전 페이지 공통 - 각 페이지 자체 헤더(제목/설명)와는 별개로, 로그인 상태만 보여주는
// 얇은 상단 바. app/layout.tsx에 한 번만 마운트해 어느 화면에서든 일관되게 보인다.
export default function AuthStatusBar() {
  const session = useSession();

  return (
    <div className="border-b border-zinc-200 bg-white dark:border-zinc-800 dark:bg-zinc-950">
      <div className="mx-auto flex max-w-2xl items-center justify-end gap-4 px-4 py-2 text-xs sm:px-6">
        {session.status === "authenticated" && session.user ? (
          <>
            <Link
              href="/watchlist"
              className="text-zinc-500 transition-colors hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100"
            >
              관심 목록
            </Link>
            <NotificationBell />
            <span className="text-zinc-500 dark:text-zinc-400">{session.user.nickname}님</span>
            <button
              type="button"
              onClick={logout}
              className="text-zinc-400 transition-colors hover:text-zinc-700 dark:hover:text-zinc-200"
            >
              로그아웃
            </button>
          </>
        ) : session.status === "anonymous" ? (
          <>
            <Link
              href="/login"
              className="text-zinc-500 transition-colors hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100"
            >
              로그인
            </Link>
            <Link
              href="/signup"
              className="font-medium text-zinc-900 transition-colors hover:underline dark:text-zinc-100"
            >
              회원가입
            </Link>
          </>
        ) : (
          // "initializing" - 조용한 refresh 시도 중, 잠깐이라 스켈레톤 없이 빈 공간으로 둔다.
          <span className="h-4" />
        )}
      </div>
    </div>
  );
}
