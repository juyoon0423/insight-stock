package com.insightstock.insightstock.rag;

import com.insightstock.insightstock.rag.dto.ChatCompletionResult;
import com.insightstock.insightstock.rag.dto.ChatMessage;
import com.insightstock.insightstock.rag.dto.OpenAiChatRequest;
import com.insightstock.insightstock.rag.dto.OpenAiChatResponse;
import com.insightstock.insightstock.rag.dto.RagPrompt;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class OpenAiChatCompletionClient implements ChatCompletionClient {

    private final RestClient restClient;
    private final String model;
    private final double temperature;

    public OpenAiChatCompletionClient(
            @Value("${rag.chat.api.base-url}") String baseUrl,
            @Value("${rag.chat.api.key}") String apiKey,
            @Value("${rag.chat.model}") String model,
            @Value("${rag.chat.temperature}") double temperature) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.model = model;
        this.temperature = temperature;
    }

    @Override
    public ChatCompletionResult complete(RagPrompt prompt) {
        OpenAiChatRequest request = new OpenAiChatRequest(
                model,
                List.of(ChatMessage.system(prompt.systemPrompt()), ChatMessage.user(prompt.userPrompt())),
                temperature);

        OpenAiChatResponse response;
        try {
            response = restClient.post()
                    .uri("/chat/completions")
                    .body(request)
                    .retrieve()
                    .body(OpenAiChatResponse.class);
        } catch (RestClientException e) {
            throw new RagApiException("OpenAI 채팅 완성 API 호출 실패", e);
        }

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new RagApiException("OpenAI 채팅 완성 API 응답이 비어있습니다.", null);
        }

        String content = response.choices().get(0).message().content();
        int promptTokens = usagePromptTokens(response);
        int completionTokens = usageCompletionTokens(response);

        return new ChatCompletionResult(content, promptTokens, completionTokens);
    }

    private int usagePromptTokens(OpenAiChatResponse response) {
        return response.usage() != null && response.usage().promptTokens() != null
                ? response.usage().promptTokens()
                : 0;
    }

    private int usageCompletionTokens(OpenAiChatResponse response) {
        return response.usage() != null && response.usage().completionTokens() != null
                ? response.usage().completionTokens()
                : 0;
    }
}
