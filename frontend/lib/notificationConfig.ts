// 헤더 배지 폴링 간격(ms) - 알림 전달 방식은 A(화면 내 배지+목록, 폴링)로 확정된
// 4단계 설계 결정(근거: docs/progress-log.md 4단계 알림 섹션). 값 하나만 바꿔도
// 되도록 컴포넌트 코드에서 분리해뒀다.
export const NOTIFICATION_POLL_INTERVAL_MS = 30_000;
