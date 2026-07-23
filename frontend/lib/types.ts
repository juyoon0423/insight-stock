// 백엔드 DTO와 1:1로 맞춘 타입. 명세: docs/progress.md "API 명세" 절 참고.

export interface SourceRef {
  rceptNo: string;
  corpName: string;
  reportNm: string;
  sectionTitle: string | null;
  dartUrl: string;
}

export interface AnswerRequest {
  query: string;
  topK?: number;
}

export interface AnswerResponse {
  query: string;
  answer: string;
  sources: SourceRef[];
}

export interface SearchResultItem {
  corpName: string;
  reportNm: string;
  sectionTitle: string | null;
  content: string;
  rrfScore: number;
  keywordRank: number | null;
  vectorRank: number | null;
}

export interface SearchResponse {
  query: string;
  results: SearchResultItem[];
}

// GlobalExceptionHandler가 내려주는 RFC 7807 ProblemDetail + errorCode 확장 필드.
export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance: string;
  errorCode: string;
}
