package com.insightstock.insightstock.embedding;

import java.util.List;

// 호출 성격이 다른 두 경로를 메서드로 구분한다 - 구현체가 경로별로 다른 타임아웃을
// 적용할 수 있게 하기 위함이다(OpenAiEmbeddingClient 참고).
public interface EmbeddingClient {

    // 배치(색인) 경로: 청크 여러 건을 한 요청으로 임베딩한다. 사용자가 기다리지 않는
    // 백그라운드 작업이라 응답을 길게 기다려도 된다.
    EmbeddingBatchResult embed(List<String> texts);

    // 단일(질의) 경로: /search, /answers에서 질의 1건을 임베딩한다. 사용자가 화면 앞에서
    // 기다리는 요청이라 짧은 타임아웃이 걸린다.
    float[] embedQuery(String text);
}
