package com.finsight.finsight_ai.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

@Configuration
@Slf4j
public class AIConfiguration {

    /**
     * Creates a ChatClient.Builder bean if a ChatModel is available.
     * This bean can be injected in controllers and services that need to use the ChatClient.
     * The ChatModel bean should be auto-configured by Spring AI Bedrock autoconfiguration.
     */
    @Bean
    @ConditionalOnBean(ChatModel.class)
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        log.info("ChatClient.Builder bean created with ChatModel: {}", chatModel.getClass().getSimpleName());
        return ChatClient.builder(chatModel);
    }

    /**
     * Fallback method to log when ChatModel is not available
     */
    @Bean
    public Optional<ChatClient.Builder> optionalChatClientBuilder(Optional<ChatModel> chatModel) {
        if (chatModel.isEmpty()) {
            log.warn("ChatModel not available - Bedrock may not be properly configured. AI responses will not be available.");
        }
        return chatModel.map(ChatClient::builder);
    }
}
