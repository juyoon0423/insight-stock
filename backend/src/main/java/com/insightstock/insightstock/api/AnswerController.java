package com.insightstock.insightstock.api;

import com.insightstock.insightstock.api.dto.AnswerRequest;
import com.insightstock.insightstock.rag.AnswerService;
import com.insightstock.insightstock.rag.dto.AnswerResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/answers")
public class AnswerController {

    private static final Logger log = LoggerFactory.getLogger(AnswerController.class);

    private final AnswerService answerService;

    public AnswerController(AnswerService answerService) {
        this.answerService = answerService;
    }

    @PostMapping
    public AnswerResponse createAnswer(@Valid @RequestBody AnswerRequest request) {
        long startedAt = System.currentTimeMillis();
        AnswerResponse response = request.topK() != null
                ? answerService.answer(request.query(), request.topK())
                : answerService.answer(request.query());
        log.info("POST /api/v1/answers 처리 시간: {}ms", System.currentTimeMillis() - startedAt);
        return response;
    }
}
