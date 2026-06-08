package com.finsight.finsight_ai.controller;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/debug")
public class TitanPropertyDebugger {

    @Value("${spring.ai.bedrock.titan.embedding.model:NOT_SET}")
    private String model;

    @GetMapping("/titan-model")
    public String titanModel() {
        return model;
    }
}
