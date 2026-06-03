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
public class BeanDebugController {

    private final ApplicationContext context;

    @GetMapping("/beans")
    public List<String> beans() {

        return Arrays.stream(
                        context.getBeanDefinitionNames()
                )
                .filter(bean ->
                        bean.toLowerCase().contains("embed")
                                || bean.toLowerCase().contains("titan")
                                || bean.toLowerCase().contains("bedrock"))
                .sorted()
                .toList();
    }
}
