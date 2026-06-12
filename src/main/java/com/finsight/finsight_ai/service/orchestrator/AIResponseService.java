package com.finsight.finsight_ai.service.orchestrator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class AIResponseService {

    private final BedrockProxyChatModel chatModel;

    @Value("${spring.ai.bedrock.converse.chat.options.max-tokens:10000}")
    private Integer maxTokens;

    @Value("${spring.ai.bedrock.converse.chat.options.temperature:0.15}")
    private Double temperature;

    @Value("${spring.ai.bedrock.converse.chat.options.top-p:0.85}")
    private Double topP;

    public AIResponseService(BedrockProxyChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String getAIResponse(String systemPrompt, String userPrompt) {
        int maxRetries = 2;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("AI Request attempt {}/{}", attempt, maxRetries);
                log.info("Using configuration - Max Tokens: {}, Temperature: {}, Top-P: {}", maxTokens, temperature, topP);

                // Explicitly pass options so max-tokens is GUARANTEED to be applied
                ChatOptions options = ChatOptions.builder()
                        .maxTokens(maxTokens)
                        .temperature(temperature)
                        .topP(topP)
                        .build();

                var request = new Prompt(
                        List.of(
                                new SystemMessage(systemPrompt),
                                new UserMessage(userPrompt)
                        ),
                        options
                );

                var response = chatModel.call(request);
                log.info(String.valueOf(response.getResult()));

                // Log detailed token usage
                var usage = response.getMetadata().getUsage();
                log.info("Token Usage - Prompt: {}, Completion: {}, Total: {}",
                        usage.getPromptTokens(),
                        usage.getCompletionTokens(),
                        usage.getTotalTokens());
                log.info("Configured Max Tokens: {}", maxTokens);
                double percentageUsed = ((double) usage.getCompletionTokens() / maxTokens) * 100;
                log.info("Completion Tokens Used: {}/{} ({}%)",
                        usage.getCompletionTokens(),
                        maxTokens,
                        String.format("%.1f", percentageUsed));

                // Extract string from AssistantMessage
                AssistantMessage assistantMessage = response.getResult().getOutput();
                String result = assistantMessage.getText();

                log.info("AI Response received, length: {} characters", result != null ? result.length() : 0);
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