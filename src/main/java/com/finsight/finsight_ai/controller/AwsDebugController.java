package com.finsight.finsight_ai.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AwsDebugController {
    private final ApplicationContext context;

    @GetMapping("/aws-debug")
    public Map<String, String> debug() {

        Map<String, String> map = new HashMap<>();

        map.put(
                "AWS_PROFILE",
                System.getenv("AWS_PROFILE")
        );

        map.put(
                "AWS_REGION",
                System.getenv("AWS_REGION")
        );

        map.put(
                "AWS_DEFAULT_REGION",
                System.getenv("AWS_DEFAULT_REGION")
        );

        return map;
    }

    @GetMapping("/aws-beans")
    public Object beans() {
        return context.getBeansOfType(
                software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient.class
        ).keySet();
    }
}
