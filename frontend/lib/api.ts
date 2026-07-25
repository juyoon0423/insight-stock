import {
  clearPersistedSession,
  clearSession,
  applySession,
  getSessionState,
  getStoredRefreshToken,
  getStoredUser,
  setSession,
} from "./tokenStore";
import type {
  AccessTokenResponse,
  AnswerRequest,
  AnswerResponse,
  CompanySearchResponse,
  LoginRequest,
  ProblemDetail,
  SearchResponse,
  SignupRequest,
  TokenResponse,
  WatchlistItem,
  WatchlistListResponse,
} from "./types";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL;

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly errorCode: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

function requireBaseUrl(): string {
  if (!API_BASE_URL) {
    throw new Error(
      "NEXT_PUBLIC_API_BASE_URL이 설정되지 않았습니다. .env.local을 확인하세요.",
    );
  }
  return API_BASE_URL;
}

async function handleResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const problem: ProblemDetail | null = await response.json().catch(() => null);
    throw new ApiError(
      problem?.detail ?? `요청이 실패했습니다 (HTTP ${response.status})`,
      response.status,
      problem?.errorCode ?? "UNKNOWN_ERROR",
    );
  }
  // DELETE 등 204 No Content는 본문이 없어 .json()이 파싱 에러를 던진다.
  if (response.status === 204) {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}

// GET /api/v1/search - LLM 호출 없이 하이브리드 검색만 수행(비용 거의 없음).
export async function search(query: string, topK?: number): Promise<SearchResponse> {
  const params = new URLSearchParams({ q: query });
  if (topK !== undefined) {
    params.set("topK", String(topK));
  }
  const response = await fetch(`${requireBaseUrl()}/api/v1/search?${params.toString()}`);
  return handleResponse<SearchResponse>(response);
}

// POST /api/v1/answers - 하이브리드 검색 + LLM 답변 생성(비용 발생).
export async function askAnswer(request: AnswerRequest): Promise<AnswerResponse> {
  const response = await fetch(`${requireBaseUrl()}/api/v1/answers`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  return handleResponse<AnswerResponse>(response);
}

// GET /api/v1/companies - WatchList 등록용 회사 자동완성(공개 엔드포인트, 인증 불필요).
export async function searchCompanies(q: string): Promise<CompanySearchResponse> {
  const params = new URLSearchParams({ q });
  const response = await fetch(`${requireBaseUrl()}/api/v1/companies?${params.toString()}`);
  return handleResponse<CompanySearchResponse>(response);
}

// ---- 인증 ----

export async function signup(request: SignupRequest): Promise<TokenResponse> {
  const response = await fetch(`${requireBaseUrl()}/api/v1/auth/signup`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  const tokenResponse = await handleResponse<TokenResponse>(response);
  applySession(tokenResponse);
  return tokenResponse;
}

export async function login(request: LoginRequest): Promise<TokenResponse> {
  const response = await fetch(`${requireBaseUrl()}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  const tokenResponse = await handleResponse<TokenResponse>(response);
  applySession(tokenResponse);
  return tokenResponse;
}

// refresh 자체는 세션 갱신용 내부 호출이라 export하지 않는다 - bootstrapSession()과
// authFetch()의 자동 재시도 로직에서만 쓰인다.
async function refreshAccessToken(refreshToken: string): Promise<AccessTokenResponse> {
  const response = await fetch(`${requireBaseUrl()}/api/v1/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
  });
  return handleResponse<AccessTokenResponse>(response);
}

// Stateless refresh token이라 서버에 무효화를 요청할 게 없다(3단계 설계 결정 그대로) -
// 로그아웃은 클라이언트에서 토큰을 잊는 것만으로 충분하다.
export function logout(): void {
  clearSession();
  clearPersistedSession();
}

// 앱 첫 진입/새로고침 시 1회 호출(components/SessionBootstrap.tsx) - localStorage의
// refresh token으로 access token을 조용히 재발급해 로그인 상태를 복원한다. refresh
// API는 accessToken만 돌려주므로 표시용 사용자 정보는 localStorage 캐시를 그대로 쓴다.
export async function bootstrapSession(): Promise<void> {
  const storedRefreshToken = getStoredRefreshToken();
  const cachedUser = getStoredUser();
  if (!storedRefreshToken || !cachedUser) {
    clearSession();
    return;
  }
  try {
    const { accessToken } = await refreshAccessToken(storedRefreshToken);
    setSession(accessToken, cachedUser);
  } catch {
    clearPersistedSession();
    clearSession();
  }
}

// 동시에 여러 요청이 401을 맞아도 refresh 호출은 한 번만 나가도록 진행 중인 Promise를
// 공유한다(WatchList 화면처럼 여러 API를 한 번에 부르는 경우 대비).
let refreshInFlight: Promise<string | null> | null = null;

function refreshOnce(): Promise<string | null> {
  if (refreshInFlight) return refreshInFlight;

  const storedRefreshToken = getStoredRefreshToken();
  if (!storedRefreshToken) return Promise.resolve(null);

  refreshInFlight = refreshAccessToken(storedRefreshToken)
    .then(({ accessToken }) => {
      const cachedUser = getSessionState().user ?? getStoredUser();
      if (cachedUser) {
        setSession(accessToken, cachedUser);
      }
      return accessToken;
    })
    .catch(() => {
      // refresh token도 만료/위조됐다는 뜻 - 완전히 로그아웃 상태로 정리한다.
      clearPersistedSession();
      clearSession();
      return null;
    })
    .finally(() => {
      refreshInFlight = null;
    });

  return refreshInFlight;
}

// 인증이 필요한 API 전용 fetch. access token을 헤더에 실어 보내고, 401을 받으면
// refresh token으로 한 번만 재시도한다 - 그래도 실패하면 원래 401 응답을 그대로
// 반환해서 handleResponse가 errorCode=AUTH_REQUIRED인 ApiError로 변환하게 둔다
// (화면은 이미 errorCode 기반 에러 메시지 매핑을 쓰고 있으니 별도 분기가 필요 없다).
async function authFetch(path: string, options: RequestInit = {}): Promise<Response> {
  const attempt = () => {
    const token = getSessionState().accessToken;
    return fetch(`${requireBaseUrl()}${path}`, {
      ...options,
      headers: {
        ...options.headers,
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    });
  };

  const first = await attempt();
  if (first.status !== 401) return first;

  const refreshedToken = await refreshOnce();
  if (!refreshedToken) return first;

  return attempt();
}

// ---- WatchList ----

export async function getWatchlist(): Promise<WatchlistListResponse> {
  const response = await authFetch("/api/v1/watchlist");
  return handleResponse<WatchlistListResponse>(response);
}

export async function addToWatchlist(companyId: number): Promise<WatchlistItem> {
  const response = await authFetch("/api/v1/watchlist", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ companyId }),
  });
  return handleResponse<WatchlistItem>(response);
}

export async function removeFromWatchlist(id: number): Promise<void> {
  const response = await authFetch(`/api/v1/watchlist/${id}`, { method: "DELETE" });
  return handleResponse<void>(response);
}
