package com.finsight.finsight_ai.service.orchestrator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AIResponseService {

    private final Optional<ChatClient.Builder> chatClientBuilder;

    public String getAIResponse(
            String systemPrompt,
            String userPrompt
    ) {
        if (chatClientBuilder.isEmpty()) {
            log.warn("ChatClient.Builder is not available. The Bedrock ChatModel is not configured. Unable to process AI requests.");
            return "I apologize, but the AI service is currently not available. Please ensure Bedrock ChatModel is properly configured in your AWS environment.";
        }

        try {
            log.debug("Invoking ChatClient with system prompt length: {}, user prompt length: {}", 
                    systemPrompt != null ? systemPrompt.length() : 0, 
                    userPrompt != null ? userPrompt.length() : 0);
            
            ChatClient chatClient = chatClientBuilder.get().build();

            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            
            log.debug("AI Response received, length: {}", response != null ? response.length() : 0);
            return response;
        } catch (Exception e) {
            log.error("Error calling ChatClient: {}", e.getMessage(), e);
            return "I encountered an error processing your request. Please try again.";
        }
    }
}