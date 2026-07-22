package com.insightstock.insightstock.embedding;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

// OpenAI 임베딩 API가 사용하는 cl100k_base 인코딩으로 토큰 수를 로컬에서 정확히 계산한다.
// API 응답의 usage.total_tokens는 요청(sub-batch) 단위 합계만 주기 때문에, 청크별
// chunks.token_count를 채우려면 이 계산이 필요하다. 동시에 API 호출 전에 8,191 토큰
// 한도 초과 청크를 걸러내는 용도로도 쓴다.
@Component
public class TokenCounter {

    private final Encoding encoding;

    public TokenCounter() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    public int count(String text) {
        return encoding.countTokens(text);
    }
}
