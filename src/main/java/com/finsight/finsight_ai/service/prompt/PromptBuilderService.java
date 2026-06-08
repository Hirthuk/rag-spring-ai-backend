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
        if (documents == null || documents.isEmpty()) {
            return "";
        }

        return documents.stream()
                .map(Document::getText)
                .filter(text -> text != null && !text.isBlank())
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

- If RETRIEVED DOCUMENT CONTEXT is present, answer primarily from that context.
- Treat RETRIEVED DOCUMENT CONTEXT as the authoritative source.
- Do not claim information is unavailable if it exists in the retrieved context.
- Use INTERNET SEARCH RESULTS only when the retrieved documents do not contain the answer.
- Return ONLY valid JSON.

""");

        return sb.toString();
    }

    public String buildUserPrompt(
            String memoryContext,
            String ragContext,
            String internetContext,
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
                RETRIEVED DOCUMENT CONTEXT:

                """);

            sb.append(ragContext);

            sb.append("\n\n");
        }

        /*
         * INTERNET CONTEXT
         */
        if (internetContext != null &&
                !internetContext.isBlank()) {

            sb.append("""
                INTERNET SEARCH RESULTS:

                """);

            sb.append(internetContext);

            sb.append("\n\n");
        }

        /*
         * QUESTION
         */
        sb.append("""
            CURRENT USER QUESTION:

            """);

        sb.append(userMessage);

        sb.append("""

            IMPORTANT:

            - Use retrieved documents if available
            - Use internet search results if available
            - Use conversation history if relevant
            - Prefer document context over internet context
            - If information conflicts, mention it
            - Return ONLY valid JSON
            """);

        return sb.toString();
    }

}
