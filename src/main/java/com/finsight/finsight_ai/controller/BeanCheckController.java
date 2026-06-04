package com.finsight.finsight_ai.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

@RestController
@RequiredArgsConstructor
public class BeanCheckController {

    private final ApplicationContext context;

    @GetMapping("/bean-check")
    public String beanCheck() {

        String[] chatModels =
                context.getBeanNamesForType(
                        org.springframework.ai.chat.model.ChatModel.class);

        return Arrays.toString(chatModels);
    }
}
