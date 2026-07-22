package com.insightstock.insightstock.domain;

/**
 * 공시 원문 수집 파이프라인(fetch → parse → chunk)에서 한 공시가 어느 단계까지
 * 처리됐는지를 나타낸다. 배치가 중단되거나 실패했을 때 이 값으로 재처리 대상을 골라낸다.
 */
public enum DocumentStatus {
    PENDING,
    FETCHED,
    PARSED,
    // document API가 status=014(원문 없음)를 반환한 경우의 terminal 상태.
    // PARSED/FAILED와 구분해야 청킹 배치가 실수로 빈 원문을 청크로 만들지 않고,
    // 재시도 로직도 이 케이스를 재시도 대상으로 삼지 않는다.
    NO_DOCUMENT,
    CHUNKED,
    // ElasticSearch에 청크가 색인 완료된 terminal 상태. 재색인이 필요하면 CHUNKED로
    // 되돌려서 IndexingScheduler가 다시 집어가게 한다(재청킹 때 썼던 것과 같은 패턴).
    INDEXED,
    FAILED
}
