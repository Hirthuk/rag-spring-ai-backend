package com.finsight.finsight_ai.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBean(EmbeddingModel.class)
@RequiredArgsConstructor
public class DebugController {

    private final EmbeddingModel embeddingModel;

    @GetMapping("/debug/embed")
    public String test() {
        embeddingModel.embed("hello");

        return "success";
    }
}