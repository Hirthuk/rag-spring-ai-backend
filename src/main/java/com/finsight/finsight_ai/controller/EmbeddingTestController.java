package com.finsight.finsight_ai.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test/embedding")
@ConditionalOnBean(EmbeddingModel.class)
@RequiredArgsConstructor
public class EmbeddingTestController {

    private final EmbeddingModel embeddingModel;

    @GetMapping
    public String test() {
        float[] embedding = embeddingModel.embed("Hello");
        return "Embedding test completed. Vector size: " + embedding.length;
    }
}
