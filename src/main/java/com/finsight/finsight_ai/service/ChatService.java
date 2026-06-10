package com.finsight.finsight_ai.service;

import com.finsight.finsight_ai.model.ChatMemoryMessage;
import com.finsight.finsight_ai.model.ChatResponse;
import com.finsight.finsight_ai.service.memory.MemoryService;
import com.finsight.finsight_ai.service.orchestrator.AIResponseService;
import com.finsight.finsight_ai.service.parser.ResponseParserService;
import com.finsight.finsight_ai.service.prompt.PromptBuilderService;
import com.finsight.finsight_ai.service.retrieval.RetrievalService;
import com.finsight.finsight_ai.service.search.SearchRoutingService;
import com.finsight.finsight_ai.service.search.TavilySearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.*;
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

    // Main method with all parameters
    public ChatResponse askQuestion(
            String conversationId,
            String userMessage,
            String systemMessage
    ) {

        try {
            log.info("Processing question: {}", userMessage);
            log.info("RetrievalService present = {}", retrievalService.isPresent());

            // Check memory size before adding
            int currentMessageCount = memoryService.getMessageCount(conversationId);
            log.info("Current conversation has {} messages before adding new user message", currentMessageCount);

            if (userMessage == null || userMessage.trim().isEmpty()) {
                return ChatResponse.error("User message cannot be empty.");
            }

            // Get conversation history
            List<ChatMemoryMessage> history = memoryService.getHistory(conversationId);
            String memoryContext = promptBuilderService.buildConversationMemory(history);

            log.info("Memory context built from {} history messages", history.size());

            // Try to retrieve relevant documents if retrieval service is available
            List<Document> documents = new ArrayList<>();
            if (retrievalService.isPresent()) {
                try {
                    RetrievalService service = retrievalService.get();
                    String retrievalQuery = service.prepareRetrievalQuery(userMessage);
                    documents = service.retrieveRelevantDocuments(retrievalQuery);

                    if (!documents.isEmpty()) {
                        log.info("RAG ACTIVE - {} documents supplied to LLM", documents.size());
                    } else {
                        log.warn("RAG INACTIVE - No documents supplied to LLM");
                    }
                    log.info("Retrieved {} relevant documents", documents.size());

                    documents.forEach(doc -> log.info("RAG DOC = {}", doc.getText()));
                } catch (Exception e) {
                    log.warn("Vector store retrieval failed, proceeding without RAG: {}", e.getMessage());
                    documents = new ArrayList<>();
                }
            } else {
                log.info("Vector store retrieval not available, proceeding without RAG");
            }

            // Build RAG context
            String ragContext = promptBuilderService.buildContext(documents);

            log.info("================================");
            log.info("RAG CONTEXT LENGTH = {}", ragContext.length());
            log.info("RAG CONTEXT = {}", ragContext);
            log.info("================================");

            // Build user prompt
            String internetContext = "";
            if (searchRoutingService.shouldUseInternetSearch(userMessage)) {
                internetContext = tavilySearchService.search(userMessage);
                log.info("Internet Search Used");
                log.info("Internet Context: {}", internetContext);
            }

            String userPrompt = promptBuilderService.buildUserPrompt(
                    memoryContext,
                    ragContext,
                    internetContext,
                    userMessage
            );

            log.info("================================");
            log.info("FINAL USER PROMPT");
            log.info(userPrompt);
            log.info("================================");

            // Build system prompt (using simplified version)
            String finalSystemPrompt = buildFinalSystemPrompt(systemMessage);

            // Save user message BEFORE getting AI response
            memoryService.addMessage(conversationId, "USER", userMessage);
            log.info("Added user message to memory, message count now: {}", memoryService.getMessageCount(conversationId));

            // Get AI response
            String aiRawResponse = aiResponseService.getAIResponse(finalSystemPrompt, userPrompt);
            log.debug("Raw AI Response: {}", aiRawResponse);

            // Parse AI response
            ChatResponse parsedResponse = responseParserService.parseAIResponse(aiRawResponse);
            log.debug("Parsed AI Response: {}", parsedResponse);

            // VALIDATE AND CLEAN CHART DATA
            parsedResponse = validateAndCleanChartData(parsedResponse);

            // Ensure response has valid chart data
            if (parsedResponse.getChartData() == null) {
                parsedResponse.setChartData(new ArrayList<>());
            }
            if (parsedResponse.getChartType() == null) {
                parsedResponse.setChartType("none");
            }

            // Save ONLY the answer text, not the full JSON response
            String answerText = parsedResponse.getAnswer();
            if (answerText != null && !answerText.trim().isEmpty()) {
                memoryService.addMessage(conversationId, "ASSISTANT", answerText);
                log.info("Added assistant answer to memory (length: {} chars), message count now: {}",
                        answerText.length(), memoryService.getMessageCount(conversationId));
            } else {
                log.warn("Empty answer from AI, not adding to memory");
            }

            return parsedResponse;

        } catch (Exception e) {
            log.error("Error while processing question", e);
            return ChatResponse.error("I encountered an error while processing your request. Please try again.");
        }
    }

    // Convenience method without systemMessage
    public ChatResponse askQuestion(String conversationId, String userMessage) {
        return askQuestion(conversationId, userMessage, null);
    }

    /**
     * Validate and clean chart data - works with List<Map<String, Object>>
     */
    private ChatResponse validateAndCleanChartData(ChatResponse response) {
        if (response == null) {
            return ChatResponse.error("Invalid response");
        }

        List<Map<String, Object>> chartData = response.getChartData();
        if (chartData == null || chartData.isEmpty()) {
            if (!"none".equals(response.getChartType())) {
                response.setChartType("none");
            }
            return response;
        }

        // Filter out invalid chart data entries
        List<Map<String, Object>> validChartData = chartData.stream()
                .filter(data -> {
                    if (data == null) return false;
                    Object year = data.get("year");
                    Object value = data.get("value");
                    if (year == null) return false;
                    if (value == null) {
                        log.debug("Removing null value for year: {}", year);
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
        else if (validChartData.size() != chartData.size()) {
            response.setChartData(validChartData);
            log.debug("Removed {} invalid chart data entries", chartData.size() - validChartData.size());
        }

        return response;
    }

    /**
     * Build final system prompt - SIMPLIFIED VERSION FOR CONSISTENT JSON
     */
    private String buildFinalSystemPrompt(String customSystemMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append(getSimplifiedSystemPrompt());

        if (customSystemMessage != null && !customSystemMessage.trim().isEmpty()) {
            sb.append("\n\n");
            sb.append("ADDITIONAL INSTRUCTION FROM USER:\n");
            sb.append(customSystemMessage);
            sb.append("\n\nPlease incorporate this instruction while maintaining the JSON output format.");
        }

        return sb.toString();
    }

    /**
     * Simplified system prompt focused on forecasting
     */
    private String getSimplifiedSystemPrompt() {
        return """
You are FinSight AI. Return ONLY valid JSON. No text before, no text after, no markdown, no ```json.

==================================================
OUTPUT STRUCTURE (STRICT)
==================================================

{
  "answer": "string - concise, 2-3 sentences max",
  "chartType": "line" OR "bar" OR "forecast" OR "none",
  "chartData": []
}

==================================================
FORECASTING RULE (DEFAULT FOR COMPANY QUESTIONS)
==================================================

When user asks about a company (e.g., "how is X doing?", "X performance", "X financial health", "tell me about X"):

→ ALWAYS provide a 5-year forecast by DEFAULT

Forecasting requires:
✓ At least 3 years of historical data

If 3+ years exist:
- Project revenue for next 5 years
- Use chartType = "forecast"
- Add "F" suffix to forecast years: "2025F", "2026F", etc.
- Calculate using CAGR = (Last Year Value / First Year Value) ^ (1/Number of Years) - 1

If less than 3 years exist:
- Skip forecast
- Use chartType = "line"
- State limitation in answer

==================================================
ANSWER RULES
==================================================

Keep answers SHORT (50-75 words max).
No bullet points.
No markdown formatting.
Natural sentences only.

==================================================
CHART DATA FORMAT
==================================================

[{"year": "2020", "value": 10000000}, {"year": "2021", "value": 12000000}]

Values must be integers (no quotes, no commas, no decimals)

For forecast charts:
- Historical years: no suffix
- Forecast years: add "F" suffix like "2025F"

==================================================
HALLUCINATION PREVENTION
==================================================

Use ONLY retrieved financial data for historical values.
Never invent numbers.

If insufficient data:
{
  "answer": "Insufficient data available for reliable analysis.",
  "chartType": "none",
  "chartData": []
}
""";
    }
}