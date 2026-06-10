package com.finsight.finsight_ai.service.orchestrator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class AIResponseService {

    private final BedrockProxyChatModel chatModel;

    public AIResponseService(BedrockProxyChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String getAIResponse(String systemPrompt, String userPrompt) {
        int maxRetries = 2;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("AI Request attempt {}/{}", attempt, maxRetries);

                var request = new Prompt(
                        List.of(
                                new SystemMessage(systemPrompt),
                                new UserMessage(userPrompt)
                        )
                );

                var response = chatModel.call(request);

                // Fix: Extract string from AssistantMessage
                AssistantMessage assistantMessage = response.getResult().getOutput();
                String result = assistantMessage.getText();

                log.info("AI Response received, length: {}", result != null ? result.length() : 0);
                return result;

            } catch (Exception e) {
                lastException = e;
                log.warn("AI Request attempt {} failed: {}", attempt, e.getMessage());

                if (attempt < maxRetries) {
                    try {
                        // Fix: Explicit cast to long to avoid multiplication issues
                        long sleepTime = 1000L * attempt;
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        throw new RuntimeException("Failed to get AI response after " + maxRetries + " attempts", lastException);
    }
}