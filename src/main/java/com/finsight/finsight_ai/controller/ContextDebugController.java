package com.finsight.finsight_ai.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/debug")
public class ContextDebugController {

    private final ApplicationContext context;

    @GetMapping("/embedding-beans")
    public List<String> embeddingBeans() {
        return Arrays.stream(context.getBeanDefinitionNames())
                .filter(bean ->
                        bean.toLowerCase().contains("embed")
                                || bean.toLowerCase().contains("titan"))
                .sorted()
                .toList();
    }
}
