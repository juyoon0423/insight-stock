"use client";

import { useEffect, useRef, useState } from "react";
import { getNotifications, getUnreadNotificationCount, markNotificationAsRead } from "@/lib/api";
import { NOTIFICATION_POLL_INTERVAL_MS } from "@/lib/notificationConfig";
import { useSession } from "@/lib/tokenStore";
import type { NotificationItem } from "@/lib/types";

// AuthStatusBar 옆에 배치하는 헤더 배지+드롭다운. 전달 방식 A(폴링)로 확정된 4단계
// 설계 결정을 그대로 구현한다 - 로그인 상태일 때만 렌더링(비로그인 시 배지/목록
// 자체가 안 보임).
export default function NotificationBell() {
  const session = useSession();
  const [unreadCount, setUnreadCount] = useState(0);
  const [isOpen, setIsOpen] = useState(false);
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [isLoadingList, setIsLoadingList] = useState(false);
  const [listError, setListError] = useState<string | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  // 로그인 상태일 때만 미읽음 건수를 주기적으로 폴링한다. setState는 전부 .then/.catch
  // 콜백 안에서만 호출한다(effect 본문 자체는 동기적으로 상태를 바꾸지 않는다 -
  // WatchlistPage와 같은 규칙).
  useEffect(() => {
    // 비로그인/초기화 중에는 이 컴포넌트가 아예 null을 렌더링하므로(아래 참고)
    // unreadCount를 여기서 동기적으로 되돌릴 필요가 없다 - effect 본문의 동기적
    // setState는 react-hooks/set-state-in-effect에 걸린다(WatchlistPage와 같은 규칙).
    if (session.status !== "authenticated") {
      return;
    }
    let cancelled = false;
    function poll() {
      getUnreadNotificationCount()
        .then((res) => {
          if (!cancelled) setUnreadCount(res.unreadCount);
        })
        .catch(() => {
          // 배지 하나 때문에 에러 화면을 보여줄 필요는 없다 - 다음 주기에 조용히 재시도.
        });
    }
    poll();
    const timer = setInterval(poll, NOTIFICATION_POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [session.status]);

  // 드롭다운이 열려 있을 때 바깥을 클릭하면 닫는다.
  useEffect(() => {
    if (!isOpen) return;
    function handleClickOutside(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, [isOpen]);

  function handleToggle() {
    const next = !isOpen;
    setIsOpen(next);
    if (next) {
      setIsLoadingList(true);
      setListError(null);
      getNotifications()
        .then((res) => setItems(res.notifications))
        .catch(() => setListError("알림을 불러오지 못했습니다."))
        .finally(() => setIsLoadingList(false));
    }
  }

  function handleItemClick(item: NotificationItem) {
    // 낙관적으로 먼저 읽음 처리 - 이미 읽은 항목이어도 API가 idempotent라 안전하다.
    setItems((prev) => prev.map((n) => (n.id === item.id ? { ...n, isRead: true } : n)));
    if (!item.isRead) {
      setUnreadCount((prev) => Math.max(0, prev - 1));
    }
    markNotificationAsRead(item.id).catch(() => {
      // 실패해도 다음 폴링/재조회 때 실제 서버 상태로 자연히 맞춰지므로 별도 롤백은 안 함.
    });
    window.open(item.dartUrl, "_blank", "noopener,noreferrer");
  }

  if (session.status !== "authenticated") {
    return null;
  }

  return (
    <div ref={containerRef} className="relative">
      <button
        type="button"
        onClick={handleToggle}
        aria-label="알림"
        className="relative rounded-full p-1 text-zinc-500 transition-colors hover:bg-zinc-100 hover:text-zinc-900 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-zinc-100"
      >
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4">
          <path
            fillRule="evenodd"
            d="M5.25 9a6.75 6.75 0 0 1 13.5 0v.75c0 2.123.8 4.057 2.118 5.52a.75.75 0 0 1-.297 1.206c-1.544.57-3.16.99-4.831 1.243a3.75 3.75 0 1 1-7.48 0 24.585 24.585 0 0 1-4.831-1.244.75.75 0 0 1-.298-1.205A8.217 8.217 0 0 0 5.25 9.75V9Zm4.502 8.9a2.25 2.25 0 1 0 4.496 0 25.057 25.057 0 0 1-4.496 0Z"
            clipRule="evenodd"
          />
        </svg>
        {unreadCount > 0 && (
          <span className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-red-500 px-1 text-[10px] font-semibold text-white">
            {unreadCount > 99 ? "99+" : unreadCount}
          </span>
        )}
      </button>

      {isOpen && (
        <div className="absolute right-0 z-20 mt-2 w-80 overflow-hidden rounded-lg border border-zinc-200 bg-white shadow-lg dark:border-zinc-700 dark:bg-zinc-900">
          <div className="max-h-96 overflow-y-auto">
            {isLoadingList ? (
              <p className="px-4 py-6 text-center text-sm text-zinc-400 dark:text-zinc-500">불러오는 중...</p>
            ) : listError ? (
              <p className="px-4 py-6 text-center text-sm text-red-500 dark:text-red-400">{listError}</p>
            ) : items.length === 0 ? (
              <p className="px-4 py-6 text-center text-sm text-zinc-400 dark:text-zinc-500">아직 알림이 없습니다.</p>
            ) : (
              <ul className="divide-y divide-zinc-100 dark:divide-zinc-800">
                {items.map((item) => (
                  <li key={item.id}>
                    <button
                      type="button"
                      onClick={() => handleItemClick(item)}
                      className={`flex w-full flex-col gap-0.5 px-4 py-3 text-left transition-colors hover:bg-zinc-50 dark:hover:bg-zinc-800 ${
                        item.isRead ? "" : "bg-blue-50/60 dark:bg-blue-950/20"
                      }`}
                    >
                      <div className="flex items-center gap-1.5">
                        {!item.isRead && (
                          <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-blue-500" aria-hidden="true" />
                        )}
                        <span className="truncate text-sm font-medium text-zinc-900 dark:text-zinc-100">
                          {item.company.corpName}
                        </span>
                      </div>
                      <p className="truncate text-xs text-zinc-500 dark:text-zinc-400">{item.reportNm}</p>
                      <p className="text-[11px] text-zinc-400 dark:text-zinc-500">
                        {new Date(item.createdAt).toLocaleString("ko-KR", {
                          dateStyle: "short",
                          timeStyle: "short",
                        })}
                      </p>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
