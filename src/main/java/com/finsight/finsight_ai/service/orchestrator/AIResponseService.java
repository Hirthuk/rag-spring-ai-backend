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
import reactor.core.publisher.Flux;

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

    /**
     * Streaming variant - emits the model's plain-text output token-by-token as a
     * Flux of text chunks (used by the SSE streaming endpoint for a typing effect).
     */
    public Flux<String> streamResponse(String systemPrompt, String userPrompt) {
        ChatOptions options = ChatOptions.builder()
                .maxTokens(maxTokens)
                .temperature(temperature)
                .topP(topP)
                .build();

        Prompt prompt = new Prompt(
                List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)),
                options
        );

        return chatModel.stream(prompt)
                .map(chunk -> {
                    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                        return "";
                    }
                    String text = chunk.getResult().getOutput().getText();
                    return text == null ? "" : text;
                })
                .filter(t -> !t.isEmpty());
    }

    public String getAIResponse(String systemPrompt, String userPrompt) {
        int maxRetries = 2;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
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

                var usage = response.getMetadata().getUsage();
                double pct = ((double) usage.getCompletionTokens() / maxTokens) * 100;
                log.info("Tokens: {}/{} ({:.1f}%)", usage.getCompletionTokens(), maxTokens,
                        String.format("%.1f", pct));

                AssistantMessage assistantMessage = response.getResult().getOutput();
                String result = assistantMessage.getText();
                return result;

            } catch (Exception e) {
                lastException = e;
                log.warn("AI request failed (attempt {}/{}): {}", attempt, maxRetries, e.getMessage());

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