package com.insightstock.insightstock.rag;

import com.insightstock.insightstock.rag.dto.ChatCompletionResult;
import com.insightstock.insightstock.rag.dto.RagPrompt;

public interface ChatCompletionClient {

    ChatCompletionResult complete(RagPrompt prompt);
}
