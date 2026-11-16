"use client";

import Link from "next/link";
import { logout } from "@/lib/api";
import { useSession } from "@/lib/tokenStore";
import NotificationBell from "@/components/NotificationBell";

// 전 페이지 공통 상단 바 - 왼쪽에 서비스 이름(홈 링크), 오른쪽에 로그인 상태.
// app/layout.tsx에 한 번만 마운트해 어느 화면에서든 일관되게 보인다.
export default function AuthStatusBar() {
  const session = useSession();

  return (
    <header className="border-b border-line bg-surface">
      <div className="mx-auto flex max-w-5xl items-center justify-between gap-4 px-4 py-3 sm:px-6">
        {/* translate="no": 브라우저 자동 번역이 서비스 이름을 바꾸지 않게 */}
        <Link href="/" translate="no" className="text-base font-semibold text-ink">
          InsightStock
        </Link>
        <nav aria-label="계정" className="flex items-center gap-4 text-sm">
          {session.status === "authenticated" && session.user ? (
            <>
              <Link href="/watchlist" className="link-quiet">
                관심 목록
              </Link>
              <NotificationBell />
              <span className="text-ink-muted">{session.user.nickname}님</span>
              <button type="button" onClick={logout} className="link-quiet">
                로그아웃
              </button>
            </>
          ) : session.status === "anonymous" ? (
            // 회원가입 링크는 전시(2026-11-04) 동안 숨긴다 - 관람객이 가입하면 운영 DB에 실제 이메일이
            // 남기 때문. /signup 페이지와 로그인 화면 안의 가입 링크는 그대로 있다.
            <Link href="/login" className="link-quiet">
              로그인
            </Link>
          ) : (
            // "initializing" - 조용한 refresh 시도 중, 잠깐이라 스켈레톤 없이 빈 공간으로 둔다.
            <span className="h-5" />
          )}
        </nav>
      </div>
    </header>
  );
}
