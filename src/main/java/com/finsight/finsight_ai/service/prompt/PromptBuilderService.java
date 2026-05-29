package com.finsight.finsight_ai.service.prompt;

import com.finsight.finsight_ai.model.ChatMemoryMessage;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class PromptBuilderService {

    /**
     * Build RAG context
     */
    public String buildContext(
            List<Document> documents
    ) {

        return documents.stream()
                .map(Document::getText)
                .collect(
                        Collectors.joining(
                                "\n\n---\n\n"
                        )
                );
    }

    /**
     * Build memory context
     */
    public String buildConversationMemory(
            List<ChatMemoryMessage> memory
    ) {

        if (memory == null ||
                memory.isEmpty()) {

            return "";
        }

        StringBuilder sb =
                new StringBuilder();

        sb.append("""
                PREVIOUS CONVERSATION HISTORY:
                
                """);

        for (ChatMemoryMessage msg : memory) {

            sb.append(msg.getRole())
                    .append(": ")
                    .append(msg.getContent())
                    .append("\n");
        }

        sb.append("\n");

        return sb.toString();
    }

    /**
     * Build final user prompt
     */
    public String buildUserPrompt(
            String memoryContext,
            String ragContext,
            String userMessage
    ) {

        StringBuilder sb =
                new StringBuilder();

        /*
         * MEMORY
         */
        if (memoryContext != null &&
                !memoryContext.isBlank()) {

            sb.append(memoryContext);
        }

        /*
         * RAG CONTEXT
         */
        if (ragContext != null &&
                !ragContext.isBlank()) {

            sb.append("""
                    RETRIEVED FINANCIAL CONTEXT:
                    
                    """);

            sb.append(ragContext);

            sb.append("\n\n");
        }

        /*
         * CURRENT QUESTION
         */
        sb.append("""
                CURRENT USER QUESTION:
                
                """);

        sb.append(userMessage);

        sb.append("""

                
                
                IMPORTANT:
                - Use previous conversation if relevant
                - Use retrieved financial context if relevant
                - Maintain conversational continuity
                - Return ONLY valid JSON
                """);

        return sb.toString();
    }
}
