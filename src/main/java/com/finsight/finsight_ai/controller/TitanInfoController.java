package com.finsight.finsight_ai.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.model.bedrock.titan.autoconfigure.BedrockTitanEmbeddingProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/debug")
public class TitanInfoController {

    private final BedrockTitanEmbeddingProperties properties;

    @GetMapping("/titan-info")
    public Object info() {
        return properties.getModel();
    }
}