package com.insightstock.insightstock.embedding;

import com.insightstock.insightstock.embedding.dto.OpenAiEmbeddingItem;
import com.insightstock.insightstock.embedding.dto.OpenAiEmbeddingRequest;
import com.insightstock.insightstock.embedding.dto.OpenAiEmbeddingResponse;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private final RestClient restClient;
    private final String model;

    public OpenAiEmbeddingClient(
            @Value("${embedding.api.base-url}") String baseUrl,
            @Value("${embedding.api.key}") String apiKey,
            @Value("${embedding.model}") String model) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.model = model;
    }

    @Override
    public EmbeddingBatchResult embed(List<String> texts) {
        OpenAiEmbeddingRequest request = new OpenAiEmbeddingRequest(model, texts);

        OpenAiEmbeddingResponse response;
        try {
            response = restClient.post()
                    .uri("/embeddings")
                    .body(request)
                    .retrieve()
                    .body(OpenAiEmbeddingResponse.class);
        } catch (RestClientException e) {
            throw new EmbeddingApiException("OpenAI 임베딩 API 호출 실패 (텍스트 %d건)".formatted(texts.size()), e);
        }

        if (response == null || response.data() == null) {
            throw new EmbeddingApiException("OpenAI 임베딩 API 응답이 비어있습니다.", null);
        }

        List<float[]> embeddings = response.data().stream()
                .sorted(Comparator.comparing(OpenAiEmbeddingItem::index))
                .map(item -> toFloatArray(item.embedding()))
                .toList();

        int totalTokens = response.usage() != null ? response.usage().totalTokens() : 0;
        return new EmbeddingBatchResult(embeddings, totalTokens);
    }

    private float[] toFloatArray(List<Float> values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }
}
