package com.insightstock.insightstock.rag.dto;

import java.util.List;

public record AnswerResponse(String query, String answer, List<SourceRef> sources) {
}
