package com.finsight.finsight_ai.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class BedrockTestController {

    private final ChatClient.Builder chatClientBuilder;

    @GetMapping("/claude")
    public String testClaude() {

        ChatClient chatClient =
                chatClientBuilder.build();

        return chatClient.prompt()
                .user("Say hello from Claude")
                .call()
                .content();
    }
}
