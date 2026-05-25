package com.finsight.finsight_ai.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient.Builder chatClientBuilder;

    public String askQuestion(String question) {
        ChatClient chatClient = chatClientBuilder.build();

        return chatClient.prompt().user(question).call().content();
    }
}
