package com.insightstock.insightstock.search.evaluation;

// search-quality-queries.json의 category 값과 그대로 매칭된다.
public enum QueryCategory {
    // 고유명사형 - 회사명/숫자를 그대로 인용, 키워드 검색에 유리한 조건.
    KEYWORD_FRIENDLY,
    // 사건형 - 특정 사건(정정, 이사 선임, 설명회 개최 등) 확인.
    EVENT
}
