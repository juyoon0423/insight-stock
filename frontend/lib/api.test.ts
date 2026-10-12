import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// 인증이 필요한 API 호출(authFetch)의 토큰 갱신 흐름을 검증한다. 화면에서는 "30분 뒤에도 로그인이
// 유지되는가", "토큰이 완전히 만료되면 깔끔하게 로그아웃되는가"로 드러나는 부분이다.
// 실제 서버를 부르지 않는다 - fetch와 localStorage를 가짜로 바꾼다.

const BASE_URL = "http://api.test";
const USER = { id: 1, email: "user@example.com", nickname: "닉네임" };

type Api = typeof import("./api");
type TokenStore = typeof import("./tokenStore");

let api: Api;
let tokenStore: TokenStore;
let storage: Map<string, string>;
let calls: { url: string; authorization: string | null }[];
// 테스트마다 바꿔 끼우는 가짜 서버 동작
let respond: (url: string, authorization: string | null) => Response | Promise<Response>;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const authRequired = () => json(401, { detail: "로그인이 필요합니다.", errorCode: "AUTH_REQUIRED" });

function refreshCalls() {
  return calls.filter((call) => call.url.endsWith("/api/v1/auth/refresh"));
}

beforeEach(async () => {
  storage = new Map();
  calls = [];
  vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", BASE_URL);
  // tokenStore는 window가 없으면(서버 렌더링) localStorage를 건드리지 않는다 - 브라우저처럼 보이게 한다.
  vi.stubGlobal("window", {});
  vi.stubGlobal("localStorage", {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => void storage.set(key, value),
    removeItem: (key: string) => void storage.delete(key),
  });
  vi.stubGlobal("fetch", async (input: string, init?: RequestInit) => {
    const headers = (init?.headers ?? {}) as Record<string, string>;
    const authorization = headers.Authorization ?? null;
    calls.push({ url: input, authorization });
    return respond(input, authorization);
  });
  // api.ts가 모듈 로드 시점에 환경변수를 읽고, 세션 상태도 모듈 변수라 테스트마다 새로 불러온다.
  vi.resetModules();
  api = await import("./api");
  tokenStore = await import("./tokenStore");
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

function login(accessToken: string, refreshToken: string) {
  tokenStore.applySession({ accessToken, refreshToken, user: USER });
}

describe("authFetch (getWatchlist로 검증)", () => {
  it("access token이 유효하면 그 토큰으로 한 번만 호출한다", async () => {
    login("access-1", "refresh-1");
    respond = () => json(200, { watchlist: [] });

    await expect(api.getWatchlist()).resolves.toEqual({ watchlist: [] });

    expect(calls).toEqual([{ url: `${BASE_URL}/api/v1/watchlist`, authorization: "Bearer access-1" }]);
  });

  it("401을 받으면 refresh token으로 갱신한 뒤 새 토큰으로 다시 호출한다", async () => {
    login("expired-access", "refresh-1");
    respond = (url, authorization) => {
      if (url.endsWith("/auth/refresh")) return json(200, { accessToken: "access-2" });
      return authorization === "Bearer access-2" ? json(200, { watchlist: [] }) : authRequired();
    };

    await expect(api.getWatchlist()).resolves.toEqual({ watchlist: [] });

    expect(calls.map((call) => call.authorization)).toEqual(["Bearer expired-access", null, "Bearer access-2"]);
    expect(tokenStore.getSessionState()).toMatchObject({ status: "authenticated", accessToken: "access-2" });
  });

  it("여러 요청이 동시에 401을 받아도 갱신 요청은 한 번만 나간다", async () => {
    login("expired-access", "refresh-1");
    respond = async (url, authorization) => {
      if (url.endsWith("/auth/refresh")) {
        await new Promise((resolve) => setTimeout(resolve, 20));
        return json(200, { accessToken: "access-2" });
      }
      if (authorization !== "Bearer access-2") return authRequired();
      return url.endsWith("/unread-count") ? json(200, { unreadCount: 3 }) : json(200, { watchlist: [] });
    };

    const [watchlist, unread] = await Promise.all([api.getWatchlist(), api.getUnreadNotificationCount()]);

    expect(watchlist).toEqual({ watchlist: [] });
    expect(unread).toEqual({ unreadCount: 3 });
    expect(refreshCalls()).toHaveLength(1);
  });

  it("refresh token도 거절되면 로그아웃 상태로 정리하고 AUTH_REQUIRED 에러를 던진다", async () => {
    login("expired-access", "expired-refresh");
    respond = (url) =>
      url.endsWith("/auth/refresh")
        ? json(401, { detail: "유효하지 않은 refresh 토큰입니다.", errorCode: "INVALID_REFRESH_TOKEN" })
        : authRequired();

    await expect(api.getWatchlist()).rejects.toMatchObject({ status: 401, errorCode: "AUTH_REQUIRED" });

    expect(tokenStore.getSessionState()).toEqual({ status: "anonymous", accessToken: null, user: null });
    expect(storage.size).toBe(0);
    // 갱신에 실패했으면 원래 요청을 다시 보내지 않는다(같은 401만 반복된다).
    expect(calls.filter((call) => call.url.endsWith("/watchlist"))).toHaveLength(1);
  });

  it("저장된 refresh token이 없으면 갱신을 시도하지 않는다", async () => {
    respond = () => authRequired();

    await expect(api.getWatchlist()).rejects.toMatchObject({ errorCode: "AUTH_REQUIRED" });

    expect(refreshCalls()).toHaveLength(0);
  });
});

describe("bootstrapSession (새로고침 후 로그인 복원)", () => {
  it("저장된 refresh token으로 access token을 받아 로그인 상태를 복원한다", async () => {
    storage.set("insightstock.refreshToken", "refresh-1");
    storage.set("insightstock.user", JSON.stringify(USER));
    respond = () => json(200, { accessToken: "access-9" });

    await api.bootstrapSession();

    expect(tokenStore.getSessionState()).toEqual({ status: "authenticated", accessToken: "access-9", user: USER });
  });

  it("refresh token이 만료됐으면 저장된 세션을 지우고 비로그인 상태가 된다", async () => {
    storage.set("insightstock.refreshToken", "expired-refresh");
    storage.set("insightstock.user", JSON.stringify(USER));
    respond = () => json(401, { detail: "유효하지 않은 refresh 토큰입니다.", errorCode: "INVALID_REFRESH_TOKEN" });

    await api.bootstrapSession();

    expect(tokenStore.getSessionState().status).toBe("anonymous");
    expect(storage.size).toBe(0);
  });

  it("저장된 것이 없으면 서버를 부르지 않고 비로그인 상태가 된다", async () => {
    respond = () => json(500, {});

    await api.bootstrapSession();

    expect(tokenStore.getSessionState().status).toBe("anonymous");
    expect(calls).toHaveLength(0);
  });
});

describe("에러 응답 변환", () => {
  it("ProblemDetail의 detail과 errorCode를 ApiError로 옮긴다", async () => {
    respond = () => json(429, { detail: "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.", errorCode: "RATE_LIMITED" });

    await expect(api.askAnswer({ query: "질문" })).rejects.toMatchObject({
      name: "ApiError",
      status: 429,
      errorCode: "RATE_LIMITED",
      message: "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.",
    });
  });

  it("본문이 JSON이 아니어도 상태 코드를 담은 ApiError를 던진다", async () => {
    respond = () => new Response("<html>502 Bad Gateway</html>", { status: 502 });

    await expect(api.search("질의")).rejects.toMatchObject({ status: 502, errorCode: "UNKNOWN_ERROR" });
  });
});
