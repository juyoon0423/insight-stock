// GET /api/v1/search (하이브리드 검색: BM25+벡터+RRF, LLM 호출 없음) 부하테스트.
// 비용은 쿼리 임베딩(20토큰 안팎)뿐이라 수천 건을 돌려도 사실상 0원 - 동시성을
// 자유롭게 올려서 검색 경로(Postgres/pgvector + Elasticsearch + RRF)의 처리량/
// 레이턴시 프로파일을 확인하는 용도.
//
// 실행 예시(옵션 A - 기준선):
//   k6 run --stage 30s:10,2m:30,30s:0 -e BASE_URL=http://localhost:8080 search-load-test.js
// 실행 예시(옵션 C - 스트레스, 20→100 VU ramp):
//   k6 run --stage 1m:20,1m:40,1m:60,1m:80,2m:100,2m:100,1m:0 \
//     -e BASE_URL=http://localhost:8080 search-load-test.js
//
// 단계(stages)는 커맨드라인에서 -e/--stage로 주입하므로, 이 파일 자체는 두 옵션에
// 공용으로 쓴다 - export const options는 fallback(기본값)만 둔다.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const queries = new SharedArray('queries', function () {
  return JSON.parse(open('./data/queries.json')).queries;
});

export const errorRate = new Rate('search_errors');
export const searchDuration = new Trend('search_duration', true);

export const options = {
  // 커맨드라인 --stage로 덮어쓰는 걸 기본 전제로 하되, 인자 없이 실행해도
  // 최소한의 스모크 테스트가 되도록 fallback을 남겨둔다.
  stages: [
    { duration: '30s', target: 10 },
    { duration: '1m', target: 10 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<3000'],
  },
};

export default function () {
  const query = queries[Math.floor(Math.random() * queries.length)];
  const url = `${BASE_URL}/api/v1/search?q=${encodeURIComponent(query)}&topK=10`;

  const res = http.get(url, { tags: { name: 'search' } });

  const ok = check(res, {
    'status is 200': (r) => r.status === 200,
  });
  errorRate.add(!ok);
  searchDuration.add(res.timings.duration);

  sleep(0.2);
}
