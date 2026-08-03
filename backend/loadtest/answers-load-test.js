// POST /api/v1/answers (하이브리드 검색 + LLM 답변 생성) 부하테스트.
// 요청 1건당 gpt-4o-mini 호출 1회가 실제로 발생해 비용이 든다(요청당 약 1~2원,
// AnswerService.java:26-27 단가 기준) - 그래서 VU 수가 아니라 총 요청 수를
// shared-iterations로 못박아 옵션 A/C 모두 동일하게 50건으로 제한한다.
//
// 주의: OpenAiChatCompletionClient에는 재시도/백오프가 없다(429가 그대로
// RagApiException으로 전파). 게다가 GlobalExceptionHandler.handleRagApi()가
// 원인과 무관하게 항상 HTTP 502(LLM_UNAVAILABLE)로 뭉뚱그리므로, k6가 보는
// 상태 코드만으로는 "OpenAI 429"와 "그 외 실제 버그"를 구분할 수 없다.
// 502가 나오면 반드시 백엔드 서버 로그(RagApiException 스택트레이스, 원인
// 메시지에 "429"가 있는지)를 대조해서 구분할 것 - 이 스크립트는 502 발생
// 자체만 카운트한다.
//
// 실행: k6 run -e BASE_URL=http://localhost:8080 answers-load-test.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const queries = new SharedArray('queries', function () {
  return JSON.parse(open('./data/queries.json')).queries;
});

export const badGatewayCount = new Counter('answers_502_bad_gateway');
export const answersDuration = new Trend('answers_duration', true);

export const options = {
  scenarios: {
    answers: {
      executor: 'shared-iterations',
      vus: 5,
      iterations: 50,
      maxDuration: '5m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
};

export default function () {
  const query = queries[Math.floor(Math.random() * queries.length)];
  const payload = JSON.stringify({ query, topK: 10 });

  const res = http.post(`${BASE_URL}/api/v1/answers`, payload, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'answers' },
  });

  check(res, { 'status is 200': (r) => r.status === 200 });
  if (res.status === 502) {
    badGatewayCount.add(1);
  }
  answersDuration.add(res.timings.duration);

  sleep(0.5);
}
