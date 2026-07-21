package com.insightstock.insightstock.domain;

/**
 * 청크가 일반 서술형 텍스트인지, 표를 "라벨: 값" 형태로 직렬화한 것인지 구분한다.
 */
public enum ChunkType {
    TEXT,
    TABLE
}
