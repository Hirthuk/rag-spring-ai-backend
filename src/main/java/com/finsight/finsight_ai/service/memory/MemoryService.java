package com.finsight.finsight_ai.service.memory;

import com.finsight.finsight_ai.model.ChatMemoryMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class MemoryService {

    // Limit memory to 6 messages total (3 user + 3 assistant)
    private static final int MAX_MESSAGES = 6;

    private final Map<String, List<ChatMemoryMessage>> conversations = new ConcurrentHashMap<>();

    /**
     * Add a message to conversation history
     */
    public void addMessage(String conversationId, String role, String content) {
        // For assistant messages, ensure we're storing ONLY the answer text (not JSON)
        String cleanContent = content;
        if ("ASSISTANT".equalsIgnoreCase(role) && content != null && content.trim().startsWith("{")) {
            cleanContent = extractAnswerFromJson(content);
            log.debug("Extracted answer from JSON: {}", cleanContent);
        }

        conversations.computeIfAbsent(conversationId, k -> new ArrayList<>());
        List<ChatMemoryMessage> messages = conversations.get(conversationId);

        // Create ChatMemoryMessage with role and content
        ChatMemoryMessage message = new ChatMemoryMessage(role, cleanContent);
        messages.add(message);

        // Enforce max message limit
        while (messages.size() > MAX_MESSAGES) {
            ChatMemoryMessage removed = messages.remove(0);
            log.debug("Removed old message: role={}, content={}", removed.getRole(), removed.getContent());
        }

        log.debug("Added message to conversation {}. Total messages: {}", conversationId, messages.size());
    }

    /**
     * Extract just the "answer" field from JSON response
     */
    private String extractAnswerFromJson(String jsonResponse) {
        try {
            // Simple extraction without full JSON parsing
            int answerStart = jsonResponse.indexOf("\"answer\":");
            if (answerStart == -1) return jsonResponse;

            int valueStart = jsonResponse.indexOf("\"", answerStart + 9) + 1;
            int valueEnd = jsonResponse.indexOf("\"", valueStart);

            if (valueStart > 0 && valueEnd > valueStart) {
                return jsonResponse.substring(valueStart, valueEnd);
            }
        } catch (Exception e) {
            log.warn("Failed to extract answer from JSON: {}", e.getMessage());
        }
        return jsonResponse;
    }

    /**
     * Get conversation history (returns List of ChatMemoryMessage)
     */
    public List<ChatMemoryMessage> getHistory(String conversationId) {
        return new ArrayList<>(conversations.getOrDefault(conversationId, new ArrayList<>()));
    }

    /**
     * Get formatted conversation history for prompt
     * Returns ONLY text, no JSON structure
     */
    public String getFormattedHistory(String conversationId) {
        List<ChatMemoryMessage> messages = conversations.getOrDefault(conversationId, new ArrayList<>());

        if (messages.isEmpty()) {
            return "";
        }

        StringBuilder history = new StringBuilder();
        history.append("\n==================================================\n");
        history.append("CONVERSATION HISTORY (Use this for context)\n");
        history.append("==================================================\n\n");

        for (ChatMemoryMessage msg : messages) {
            String role = "USER".equals(msg.getRole()) ? "User" : "Assistant";
            history.append(role).append(": ").append(msg.getContent()).append("\n\n");
        }

        history.append("==================================================\n");
        return history.toString();
    }

    /**
     * Get raw message count for debugging
     */
    public int getMessageCount(String conversationId) {
        return conversations.getOrDefault(conversationId, new ArrayList<>()).size();
    }

    /**
     * Clear conversation history
     */
    public void clearHistory(String conversationId) {
        conversations.remove(conversationId);
        log.debug("Cleared history for conversation: {}", conversationId);
    }
}