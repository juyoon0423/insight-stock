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
        aria-label={unreadCount > 0 ? `알림, 읽지 않은 알림 ${unreadCount}건` : "알림"}
        aria-expanded={isOpen}
        aria-controls="notification-panel"
        className="relative rounded-full p-1.5 text-ink-muted transition-colors hover:bg-paper hover:text-ink"
      >
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5" aria-hidden="true">
          <path
            fillRule="evenodd"
            d="M5.25 9a6.75 6.75 0 0 1 13.5 0v.75c0 2.123.8 4.057 2.118 5.52a.75.75 0 0 1-.297 1.206c-1.544.57-3.16.99-4.831 1.243a3.75 3.75 0 1 1-7.48 0 24.585 24.585 0 0 1-4.831-1.244.75.75 0 0 1-.298-1.205A8.217 8.217 0 0 0 5.25 9.75V9Zm4.502 8.9a2.25 2.25 0 1 0 4.496 0 25.057 25.057 0 0 1-4.496 0Z"
            clipRule="evenodd"
          />
        </svg>
        {unreadCount > 0 && (
          // 장식용 숫자 - 읽지 않은 건수는 버튼 aria-label로 전달한다
          <span
            aria-hidden="true"
            className="absolute -right-1 -top-1 flex h-[1.125rem] min-w-[1.125rem] items-center justify-center rounded-full bg-evidence px-1 text-xs font-semibold tabular-nums text-on-evidence"
          >
            {unreadCount > 99 ? "99+" : unreadCount}
          </span>
        )}
      </button>

      {isOpen && (
        <div
          id="notification-panel"
          className="absolute right-0 z-20 mt-2 w-80 max-w-[calc(100vw-2rem)] overflow-hidden rounded-md border border-line bg-surface shadow-lg"
        >
          <div className="max-h-96 overflow-y-auto overscroll-contain">
            {isLoadingList ? (
              <p role="status" className="px-4 py-6 text-center text-sm text-ink-subtle">불러오는 중…</p>
            ) : listError ? (
              <p role="alert" className="px-4 py-6 text-center text-sm text-danger">{listError}</p>
            ) : items.length === 0 ? (
              <p className="px-4 py-6 text-center text-sm text-ink-subtle">아직 알림이 없습니다.</p>
            ) : (
              <ul className="divide-y divide-line">
                {items.map((item) => (
                  <li key={item.id}>
                    <button
                      type="button"
                      onClick={() => handleItemClick(item)}
                      className={`flex w-full flex-col gap-0.5 px-4 py-3 text-left transition-colors hover:bg-paper ${
                        item.isRead ? "" : "bg-evidence-tint"
                      }`}
                    >
                      <div className="flex min-w-0 items-center gap-1.5">
                        {!item.isRead && (
                          <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-evidence" aria-hidden="true" />
                        )}
                        <span className="truncate text-sm font-semibold text-ink">
                          {!item.isRead && <span className="sr-only">읽지 않음: </span>}
                          {item.company.corpName}
                        </span>
                      </div>
                      <p className="truncate text-xs text-ink-muted">{item.reportNm}</p>
                      <p className="text-xs tabular-nums text-ink-subtle">
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
