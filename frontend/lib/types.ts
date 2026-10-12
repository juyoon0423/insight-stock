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
  // 본문의 [N] 중 백엔드가 출처로 인정한 번호(등장 순서). sources와 길이·순서가 같아
  // citedNumbers[i]번 인용이 sources[i]를 가리킨다.
  citedNumbers: number[];
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

export interface UserSummary {
  id: number;
  email: string;
  nickname: string;
}

export interface SignupRequest {
  email: string;
  password: string;
  nickname: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  user: UserSummary;
}

export interface AccessTokenResponse {
  accessToken: string;
}

export interface CompanySummary {
  id: number;
  corpCode: string;
  corpName: string;
  stockCode: string | null;
  corpCls: string;
}

export interface CompanySearchResponse {
  companies: CompanySummary[];
}

export interface WatchlistItem {
  id: number;
  company: CompanySummary;
  createdAt: string;
}

export interface WatchlistListResponse {
  watchlist: WatchlistItem[];
}

export interface NotificationItem {
  id: number;
  company: CompanySummary;
  reportNm: string;
  rceptNo: string;
  dartUrl: string;
  createdAt: string;
  isRead: boolean;
}

export interface NotificationListResponse {
  notifications: NotificationItem[];
}

export interface UnreadCountResponse {
  unreadCount: number;
}
