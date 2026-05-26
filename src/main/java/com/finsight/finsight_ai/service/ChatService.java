package com.finsight.finsight_ai.service;

import com.finsight.finsight_ai.model.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;  // ADD THIS for logging
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;  // CORRECT import

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j  // ADD THIS for logging support
public class ChatService {

    private final ChatClient.Builder chatClientBuilder;
    private final VectorStore vectorStore;

    public ChatResponse askQuestion(String question) {
        try {
            // Step 1 - Search similar documents from the vector store
            List<Document> documents = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(question)
                            .topK(3)
                            .build()
            );

            // Step 2 - Combine retrieved context
            String context = documents.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n"));

            // Step 3 - Build RAG Prompt
            String prompt = buildPrompt(context, question);

            // Step 4 - Call the Chat API
            ChatClient chatClient = chatClientBuilder.build();

            String aiResponse = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            // Step 5 - Parse JSON response
            return parseAIResponse(aiResponse);

        } catch (Exception e) {
            // FIXED: Use logging instead of printStackTrace()
            log.error("Error processing question: {}", question, e);
            return new ChatResponse(
                    "Failed to process AI response: " + e.getMessage(),
                    "none",
                    List.of()
            );
        }
    }

    private String buildPrompt(String context, String question) {
        return """
You are an AI Financial Analyst.

You MUST answer ONLY in valid JSON format.

Rules:
1. Return ONLY valid JSON
2. No markdown
3. No explanation outside JSON
4. Extract chart data dynamically from financial context
5. If no chart data available return empty array

Required JSON Format:

{
  "answer": "text response",
  "chartType": "line/bar/pie/forecast/none",
  "chartData": [
    {
      "year": "2020",
      "value": 10.0
    }
  ]
}

Financial Data:
%s

User Question:
%s
""".formatted(context, question);
    }

    private ChatResponse parseAIResponse(String aiResponse) {
        try {
            // EXTRACTED: Method to clean the response
            String cleanedResponse = cleanJsonResponse(aiResponse);

            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(cleanedResponse, ChatResponse.class);

        } catch (Exception e) {
            log.error("Failed to parse AI response: {}", aiResponse, e);
            return new ChatResponse(
                    "Error parsing AI response",
                    "none",
                    List.of()
            );
        }
    }

    // EXTRACTED: Method to clean JSON response from markdown
    private String cleanJsonResponse(String aiResponse) {
        String cleaned = aiResponse.trim();

        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        }
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }

        return cleaned.trim();
    }
}