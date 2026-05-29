package com.finsight.finsight_ai.service.orchestrator;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AIResponseService {

    private final ChatClient.Builder chatClientBuilder;

    public String getAIResponse(
            String systemPrompt,
            String userPrompt
    ) {

        ChatClient chatClient = chatClientBuilder.build();

        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }
}