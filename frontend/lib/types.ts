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
  // content가 완전히 동일해 이 결과 하나로 합쳐진 다른 공시들의 출처(중복 제거
  // 비활성이거나 중복이 없으면 빈 배열). dart-findings.md 2-10 참고.
  duplicateSources: SourceRef[];
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
