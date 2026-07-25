// 절충안(2026-07-25 결정, docs/progress.md 참고): access token은 메모리(이 모듈의
// 변수)에만 두고 localStorage에는 절대 저장하지 않는다 - XSS로 스크립트가 값을 훔쳐도
// 노출 창이 access token 수명(30분)으로 제한된다. refresh token(14일)과 표시용 사용자
// 정보(민감하지 않음)만 localStorage에 남겨 새로고침 후에도 조용히 재로그인할 수 있게 한다.
//
// React 컴포넌트뿐 아니라 lib/api.ts의 fetch 로직(컴포넌트 트리 밖)도 현재 토큰을
// 동기적으로 읽어야 해서 Context 대신 순수 모듈 스코프 스토어 + useSyncExternalStore를 쓴다.
import { useSyncExternalStore } from "react";
import type { UserSummary } from "./types";

export type AuthStatus = "initializing" | "authenticated" | "anonymous";

export interface SessionState {
  status: AuthStatus;
  accessToken: string | null;
  user: UserSummary | null;
}

const REFRESH_TOKEN_KEY = "insightstock.refreshToken";
const USER_KEY = "insightstock.user";

let state: SessionState = { status: "initializing", accessToken: null, user: null };
const listeners = new Set<() => void>();

function notify(): void {
  listeners.forEach((listener) => listener());
}

export function getSessionState(): SessionState {
  return state;
}

function subscribeSession(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

// 앱 시작 시점과 서버 렌더링 시점 모두 같은 초기 상태("initializing")를 보게 되므로
// getServerSnapshot으로 같은 getter를 그대로 재사용해도 하이드레이션 불일치가 없다.
export function useSession(): SessionState {
  return useSyncExternalStore(subscribeSession, getSessionState, getSessionState);
}

export function setSession(accessToken: string, user: UserSummary): void {
  state = { status: "authenticated", accessToken, user };
  notify();
}

export function clearSession(): void {
  state = { status: "anonymous", accessToken: null, user: null };
  notify();
}

// signup/login 성공 직후 - access token은 메모리에, refresh token/사용자 정보는
// localStorage에 함께 남긴다. 사용자 정보를 캐시해두는 이유: /auth/refresh는
// accessToken만 돌려주므로, 별도 "내 정보 조회" API 없이도 새로고침 후 이 캐시로
// 닉네임을 바로 그려줄 수 있다(email/nickname은 민감 정보가 아니라 localStorage에
// 남겨도 되는 정보로 판단).
export function applySession(tokens: { accessToken: string; refreshToken: string; user: UserSummary }): void {
  setSession(tokens.accessToken, tokens.user);
  if (typeof window !== "undefined") {
    localStorage.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken);
    localStorage.setItem(USER_KEY, JSON.stringify(tokens.user));
  }
}

export function clearPersistedSession(): void {
  if (typeof window === "undefined") return;
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

export function getStoredRefreshToken(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

export function getStoredUser(): UserSummary | null {
  if (typeof window === "undefined") return null;
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as UserSummary;
  } catch {
    return null;
  }
}
