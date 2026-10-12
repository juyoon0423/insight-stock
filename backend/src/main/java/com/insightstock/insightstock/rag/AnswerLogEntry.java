package com.insightstock.insightstock.rag;

import java.util.List;

// /answers 요청 1건이 "어떤 청크를 보고, 무엇을 인용해, 어떻게 답했는지"의 기록.
// AnswerService가 채우고 AnswerAuditLogger가 JSON 한 줄로 남긴다. 답변 품질 측정
// (docs/answer-quality-eval.md)에서 실패 원인을 가르려면 컨텍스트와 인용 정보가 필요했는데
// 응답에는 인용된 출처만 있어서, 운영 중인 요청에 대해서도 같은 분석을 할 수 있게 로그로 남긴다.
public record AnswerLogEntry(
        String query,
        int topK,
        // LLM에 넘어간 컨텍스트. 순서가 곧 프롬프트의 [1], [2]... 번호다.
        List<ContextChunk> context,
        // 출처로 인정된 인용 번호(컨텍스트 범위 안, 등장 순서).
        List<Integer> citedNumbers,
        // 모델이 썼지만 컨텍스트 범위 밖이라 버린 번호.
        List<Integer> outOfRangeCitations,
        // 고정 거절 문구("...찾지 못했습니다.")로만 답했는가.
        boolean refused,
        // 검색 결과가 0건이라 LLM을 호출하지 않았는가(이때 model/promptVersion/토큰은 비어 있다).
        boolean llmCalled,
        String model,
        String promptVersion,
        long searchMs,
        long llmMs,
        int promptTokens,
        int completionTokens,
        double costUsd) {

    // number: 프롬프트에서의 번호([n]). keywordRank/vectorRank는 그 검색에 안 걸렸으면 null.
    public record ContextChunk(int number, long chunkId, String rceptNo, Integer keywordRank, Integer vectorRank) {
    }

    // 거절은 아닌데 유효한 인용이 하나도 없는 답변 - 근거 표시 없이 나간 답변이라 따로 본다.
    public boolean answeredWithoutCitation() {
        return llmCalled && !refused && citedNumbers.isEmpty();
    }
}
