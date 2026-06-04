package com.finsight.finsight_ai.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequiredArgsConstructor
public class BedrockTestController {

    private final ChatModel chatModel;

    @GetMapping("/test-bedrock")
    public String test() {

        Prompt prompt = new Prompt("Say hello");

        ChatResponse response =
                chatModel.call(prompt);

        return response.getResult()
                .getOutput()
                .getText();
    }
}
