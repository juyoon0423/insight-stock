package com.insightstock.insightstock.embedding;

import java.util.List;

public interface EmbeddingClient {

    EmbeddingBatchResult embed(List<String> texts);
}
