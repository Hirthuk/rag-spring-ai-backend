package com.finsight.finsight_ai.service.memory;

import com.finsight.finsight_ai.model.ChatMemoryMessage;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MemoryService {

    /*
     * conversationId
     *      ↓
     * List of chat messages
     */
    private final Map<String,
            List<ChatMemoryMessage>>
            memoryStore =
            new ConcurrentHashMap<>();

    /**
     * Save message to memory
     */
    public void addMessage(
            String conversationId,
            String role,
            String content
    ) {

        List<ChatMemoryMessage> messages =
                memoryStore.computeIfAbsent(
                        conversationId,
                        k -> new ArrayList<>()
                );

        messages.add(
                new ChatMemoryMessage(
                        role,
                        content
                )
        );

        // Keep only last 10 messages
        if (messages.size() > 3) {
            messages.remove(0);
        }
    }

    /**
     * Get conversation history
     */
    public List<ChatMemoryMessage>
    getConversationHistory(
            String conversationId
    ) {

        return memoryStore.getOrDefault(
                conversationId,
                new ArrayList<>()
        );
    }

    /**
     * Clear conversation
     */
    public void clearConversation(
            String conversationId
    ) {

        memoryStore.remove(conversationId);
    }
}
