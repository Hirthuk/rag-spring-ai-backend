package com.finsight.finsight_ai.service;

import com.finsight.finsight_ai.model.ChatMemoryMessage;
import com.finsight.finsight_ai.model.ChatResponse;
import com.finsight.finsight_ai.model.ChartData;
import com.finsight.finsight_ai.service.memory.MemoryService;
import com.finsight.finsight_ai.service.orchestrator.AIResponseService;
import com.finsight.finsight_ai.service.parser.ResponseParserService;
import com.finsight.finsight_ai.service.prompt.FinancialPrompts;
import com.finsight.finsight_ai.service.prompt.PromptBuilderService;
import com.finsight.finsight_ai.service.retrieval.RetrievalService;
import com.finsight.finsight_ai.service.search.SearchRoutingService;
import com.finsight.finsight_ai.service.search.TavilySearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ChatService {

    private final Optional<RetrievalService> retrievalService;
    private final PromptBuilderService promptBuilderService;
    private final AIResponseService aiResponseService;
    private final ResponseParserService responseParserService;
    private final MemoryService memoryService;
    private final TavilySearchService tavilySearchService;
    private final SearchRoutingService searchRoutingService;
    
    public ChatService(
            Optional<RetrievalService> retrievalService,
            PromptBuilderService promptBuilderService,
            AIResponseService aiResponseService,
            ResponseParserService responseParserService,
            MemoryService memoryService,
            TavilySearchService tavilySearchService,
            SearchRoutingService searchRoutingService
    ) {
        this.retrievalService = retrievalService;
        this.promptBuilderService = promptBuilderService;
        this.aiResponseService = aiResponseService;
        this.responseParserService = responseParserService;
        this.memoryService = memoryService;
        this.tavilySearchService = tavilySearchService;
        this.searchRoutingService = searchRoutingService;
    }

    public ChatResponse askQuestion(
            String conversationId,
            String userMessage,
            String systemMessage
    ) {

        try {
            log.info("Processing question: {}", userMessage);

            if (userMessage == null || userMessage.trim().isEmpty()) {
                return new ChatResponse(
                        "User message cannot be empty.",
                        "none",
                        List.of()
                );
            }

             // Get conversation memory
             List<ChatMemoryMessage> history = memoryService.getConversationHistory(conversationId);
             String memoryContext = promptBuilderService.buildConversationMemory(history);

             // Try to retrieve relevant documents if retrieval service is available
             List<Document> documents = new ArrayList<>();
             if (retrievalService.isPresent()) {
                 try {
                     String retrievalQuery =
                             retrievalService.get()
                                     .prepareRetrievalQuery(
                                             userMessage
                                     );

                     documents =
                             retrievalService.get()
                                     .retrieveRelevantDocuments(
                                             retrievalQuery
                                     );
                     log.info("Retrieved {} relevant documents", documents.size());
                 } catch (Exception e) {
                     log.warn("Vector store retrieval failed, proceeding without RAG: {}", e.getMessage());
                     documents = new ArrayList<>();
                 }
             } else {
                 log.info("Vector store retrieval not available, proceeding without RAG");
             }

            // Build RAG context
            String ragContext = promptBuilderService.buildContext(documents);

            // Build user prompt
            String internetContext = "";

            if (searchRoutingService
                    .shouldUseInternetSearch(
                            userMessage
                    )) {

                internetContext =
                        tavilySearchService.search(
                                userMessage
                        );

                log.info(
                        "Internet Search Used"
                );
                log.info(
                        "Internet Context: {}",
                        internetContext
                );
            }

            String userPrompt =
                    promptBuilderService.buildUserPrompt(
                            memoryContext,
                            ragContext,
                            internetContext,
                            userMessage
                    );


            // Build system prompt
            String finalSystemPrompt = buildFinalSystemPrompt(systemMessage);

            // Save user message
            memoryService.addMessage(conversationId, "USER", userMessage);

            // Get AI response
            String aiRawResponse = aiResponseService.getAIResponse(finalSystemPrompt, userPrompt);
            log.debug("Raw AI Response: {}", aiRawResponse);

            // Parse AI response
            ChatResponse parsedResponse = responseParserService.parseAIResponse(aiRawResponse);
            log.debug("Parsed AI Response: {}", parsedResponse);

            // VALIDATE AND CLEAN CHART DATA
            parsedResponse = validateAndCleanChartData(parsedResponse);

            // Save AI response
            memoryService.addMessage(conversationId, "ASSISTANT", parsedResponse.getAnswer());

            return parsedResponse;

        } catch (Exception e) {
            log.error("Error while processing question", e);
            return createFallbackResponse();
        }
    }

    public ChatResponse askQuestion(String conversationId, String userMessage) {
        return askQuestion(conversationId, userMessage, null);
    }

    /**
     * Validate and clean chart data - removes null values and ensures data quality
     */
    private ChatResponse validateAndCleanChartData(ChatResponse response) {
        if (response.getChartData() == null || response.getChartData().isEmpty()) {
            if (response.getChartType() == null || "none".equals(response.getChartType())) {
                return response;
            }
            response.setChartType("none");
            return response;
        }

        // Filter out chart data entries with null values
        List<ChartData> validChartData = response.getChartData().stream()
                .filter(data -> {
                    if (data == null || data.getYear() == null) {
                        return false;
                    }
                    if (data.getValue() == null) {
                        log.debug("Removing null value for year: {}", data.getYear());
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());

        // If all data was invalid, set to empty
        if (validChartData.isEmpty()) {
            response.setChartData(new ArrayList<>());
            response.setChartType("none");
            log.debug("All chart data was invalid, cleared chartData");
        }
        // If some data was removed, update the response
        else if (validChartData.size() != response.getChartData().size()) {
            response.setChartData(validChartData);
            log.debug("Removed {} invalid chart data entries",
                    response.getChartData().size() - validChartData.size());

            // Optionally update answer to mention omitted data
            String originalAnswer = response.getAnswer();
            if (!originalAnswer.contains("chart data omitted")) {
                response.setAnswer(originalAnswer + " (Note: Some years without data were omitted from the chart.)");
            }
        }

        return response;
    }

    /**
     * Build final system prompt
     */
    private String buildFinalSystemPrompt(String customSystemMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append(FinancialPrompts.FINANCIAL_SYSTEM_PROMPT);

        if (customSystemMessage != null && !customSystemMessage.trim().isEmpty()) {
            sb.append("\n\n");
            sb.append("ADDITIONAL INSTRUCTION FROM USER:\n");
            sb.append(customSystemMessage);
            sb.append("\n\nPlease incorporate this instruction while maintaining the JSON output format.");
        }

        return sb.toString();
    }

    /**
     * Generic fallback response
     */
    private ChatResponse createFallbackResponse() {
        return new ChatResponse(
                "I encountered an error while processing your request. Please try again.",
                "none",
                List.of()
        );
    }
}