package com.insightstock.insightstock.domain;

/**
 * 공시 원문 수집 파이프라인(fetch → parse → chunk)에서 한 공시가 어느 단계까지
 * 처리됐는지를 나타낸다. 배치가 중단되거나 실패했을 때 이 값으로 재처리 대상을 골라낸다.
 */
public enum DocumentStatus {
    PENDING,
    FETCHED,
    PARSED,
    CHUNKED,
    FAILED
}
