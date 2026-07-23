package com.insightstock.insightstock.dart;

// DART 원문 뷰어 URL을 만든다. 서버가 결정적으로 생성하고 모델/클라이언트는 이 값을
// 그대로 쓰기만 한다 - URL을 모델이 직접 만들게 하면 그 자체가 환각 지점이 되기 때문
// (rag.AnswerService의 설계 원칙과 동일, search.HybridSearchService도 재사용).
public final class DartUrls {

    private static final String VIEWER_URL_PREFIX = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=";

    private DartUrls() {
    }

    public static String viewerUrl(String rceptNo) {
        return VIEWER_URL_PREFIX + rceptNo;
    }
}
