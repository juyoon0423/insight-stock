package com.insightstock.insightstock.rag.dto;

import java.util.List;

public record AnswerResponse(String answer, List<SourceRef> sources) {
}
