import type { AnswerRequest, AnswerResponse, ProblemDetail, SearchResponse } from "./types";

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
