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
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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
    private final Optional<FinancialAssistantService> financialAssistantService;

    public ChatService(
            Optional<RetrievalService> retrievalService,
            PromptBuilderService promptBuilderService,
            AIResponseService aiResponseService,
            ResponseParserService responseParserService,
            MemoryService memoryService,
            TavilySearchService tavilySearchService,
            SearchRoutingService searchRoutingService,
            Optional<FinancialAssistantService> financialAssistantService
    ) {
        this.retrievalService = retrievalService;
        this.promptBuilderService = promptBuilderService;
        this.aiResponseService = aiResponseService;
        this.responseParserService = responseParserService;
        this.memoryService = memoryService;
        this.tavilySearchService = tavilySearchService;
        this.searchRoutingService = searchRoutingService;
        this.financialAssistantService = financialAssistantService;
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

            // Check if this is a financial analysis question - route to FinancialAssistantService
            if (isFinancialAnalysisQuestion(userMessage) && financialAssistantService.isPresent()) {
                log.info("🧠 Routing to FinancialAssistantService for detailed financial analysis");
                return handleFinancialQuestion(userMessage, conversationId);
            }

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
            // SKIP RAG for casual/greeting messages to avoid injecting irrelevant financial data
            List<Document> documents = new ArrayList<>();
            boolean isCasual = isCasualMessage(userMessage);
            if (isCasual) {
                log.info("💬 Casual/greeting message detected - skipping RAG retrieval");
            } else if (retrievalService.isPresent()) {
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
            if (!isCasual && searchRoutingService.shouldUseInternetSearch(userMessage)) {
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

            // Build system prompt - use a simple conversational prompt for casual
            // messages so the model doesn't launch into financial analysis for "Hi"
            String finalSystemPrompt = isCasual
                    ? getCasualSystemPrompt()
                    : buildFinalSystemPrompt(systemMessage);

            // Log which data source(s) are feeding this response
            boolean ragUsed = !documents.isEmpty();
            boolean internetUsed = internetContext != null && !internetContext.isEmpty();
            logResponseSources("CHAT", ragUsed, documents.size(), internetUsed);

            // Save user message BEFORE getting AI response
            memoryService.addMessage(conversationId, "USER", userMessage);
            log.info("Added user message to memory, message count now: {}", memoryService.getMessageCount(conversationId));

            // Get AI response
            String aiRawResponse = aiResponseService.getAIResponse(finalSystemPrompt, userPrompt);
            log.info("Raw AI Response: {}", aiRawResponse);

            // Parse AI response
            ChatResponse parsedResponse = responseParserService.parseAIResponse(aiRawResponse);
            log.info("Parsed AI Response: {}", parsedResponse);

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
     * Streaming variant of askQuestion. Returns Server-Sent Events so the frontend
     * can render the answer token-by-token (typing effect). Event types:
     *   - "token": a chunk of the answer text (append these as they arrive)
     *   - "chart": JSON array of chart data points (sent once, after the text)
     *   - "done" : terminal marker ("[DONE]")
     *   - "error": an error message
     * Routing mirrors askQuestion: financial questions stream the FinSight analysis;
     * casual/general questions stream a plain-text conversational reply.
     */
    public Flux<ServerSentEvent<String>> streamQuestion(String conversationId, String userMessage, String systemMessage) {
        try {
            if (userMessage == null || userMessage.trim().isEmpty()) {
                return Flux.just(
                        ServerSentEvent.<String>builder("User message cannot be empty.").event("error").build(),
                        ServerSentEvent.<String>builder("[DONE]").event("done").build()
                );
            }
            log.info("Streaming question: {}", userMessage);

            // FINANCIAL ROUTE - stream the FinSight analysis + chart data
            if (isFinancialAnalysisQuestion(userMessage) && financialAssistantService.isPresent()) {
                log.info("🧠 Streaming via FinancialAssistantService");
                memoryService.addMessage(conversationId, "USER", userMessage);
                StringBuilder acc = new StringBuilder();
                return financialAssistantService.get().streamAnalysis(userMessage)
                        .doOnNext(sse -> {
                            if ("token".equals(sse.event()) && sse.data() != null) {
                                acc.append(sse.data());
                            }
                        })
                        .doOnComplete(() -> {
                            if (acc.length() > 0) {
                                memoryService.addMessage(conversationId, "ASSISTANT", acc.toString());
                            }
                        });
            }

            // CASUAL / GENERAL ROUTE - stream a plain-text reply
            boolean casual = isCasualMessage(userMessage);

            List<ChatMemoryMessage> history = memoryService.getHistory(conversationId);
            String memoryContext = promptBuilderService.buildConversationMemory(history);

            List<Document> documents = new ArrayList<>();
            if (!casual && retrievalService.isPresent()) {
                try {
                    RetrievalService service = retrievalService.get();
                    String retrievalQuery = service.prepareRetrievalQuery(userMessage);
                    documents = service.retrieveRelevantDocuments(retrievalQuery);
                } catch (Exception e) {
                    log.warn("RAG retrieval failed during stream, continuing without it: {}", e.getMessage());
                }
            }
            String ragContext = promptBuilderService.buildContext(documents);
            String userPrompt = promptBuilderService.buildUserPrompt(memoryContext, ragContext, "", userMessage);
            String sysPrompt = casual ? getCasualStreamingPrompt() : getGeneralStreamingPrompt();

            // Internet search is not used on the streaming path; log the source mix
            logResponseSources("CHAT-STREAM", !documents.isEmpty(), documents.size(), false);

            memoryService.addMessage(conversationId, "USER", userMessage);
            StringBuilder acc = new StringBuilder();

            Flux<ServerSentEvent<String>> tokens = aiResponseService.streamResponse(sysPrompt, userPrompt)
                    .doOnNext(acc::append)
                    .map(t -> ServerSentEvent.<String>builder(t).event("token").build());

            Flux<ServerSentEvent<String>> tail = Flux.defer(() -> {
                if (acc.length() > 0) {
                    memoryService.addMessage(conversationId, "ASSISTANT", acc.toString());
                }
                return Flux.just(
                        ServerSentEvent.<String>builder("[]").event("chart").build(),
                        ServerSentEvent.<String>builder("[DONE]").event("done").build()
                );
            });

            return Flux.concat(tokens, tail)
                    .onErrorResume(e -> {
                        log.error("Streaming error: {}", e.getMessage());
                        return Flux.just(
                                ServerSentEvent.<String>builder("Error: " + e.getMessage()).event("error").build(),
                                ServerSentEvent.<String>builder("[DONE]").event("done").build()
                        );
                    });

        } catch (Exception e) {
            log.error("streamQuestion failed: {}", e.getMessage(), e);
            return Flux.just(
                    ServerSentEvent.<String>builder("I encountered an error while processing your request.").event("error").build(),
                    ServerSentEvent.<String>builder("[DONE]").event("done").build()
            );
        }
    }

    /** Minimal plain-text system prompt for casual/greeting messages in streaming mode. */
    private String getCasualStreamingPrompt() {
        return """
                You are FinSight AI, a friendly financial intelligence assistant.
                The user sent a casual or conversational message (greeting, introduction,
                thanks, small talk). Reply naturally and warmly in 1-3 sentences as PLAIN TEXT.
                Do NOT output JSON, code blocks, company financials, or chart data unless the
                user explicitly asks for analysis.
                """;
    }

    /** Plain-text system prompt for general (non-financial) questions in streaming mode. */
    private String getGeneralStreamingPrompt() {
        return """
                You are FinSight AI, a helpful assistant. Answer the user's question clearly
                and concisely as PLAIN TEXT (no JSON, no code fences). Use the provided context
                and conversation history if they are relevant; otherwise answer from general
                knowledge.
                """;
    }

    /**
     * Check if question is financial analysis related (NOT just any question about companies)
     * Must have clear financial analysis intent
     */
    private boolean isFinancialAnalysisQuestion(String userMessage) {
        // Reject greetings and casual messages
        String lowerMessage = userMessage.toLowerCase().trim();

        if (lowerMessage.matches("^(hi|hello|hey|greetings|my name is.*|who are you|what is your name|thanks|thank you).*")) {
            return false;
        }

        // Must have explicit financial analysis keywords + company context
        boolean hasFinancialKeyword =
               lowerMessage.contains("financial") ||
               lowerMessage.contains("revenue") ||
               lowerMessage.contains("profit") ||
               lowerMessage.contains("forecast") ||
               lowerMessage.contains("ebitda") ||
               lowerMessage.contains("cash flow") ||
               lowerMessage.contains("valuation") ||
               lowerMessage.contains("pe ratio") ||
               lowerMessage.contains("roe") ||
               lowerMessage.contains("roa") ||
               lowerMessage.contains("margin") ||
               lowerMessage.contains("turnover") ||
               lowerMessage.contains("earnings") ||
               lowerMessage.contains("growth analysis") ||
               lowerMessage.contains("investment outlook");

        boolean hasCompanyName =
               lowerMessage.contains("hcl") ||
               lowerMessage.contains("tcs") ||
               lowerMessage.contains("infosys") ||
               lowerMessage.contains("wipro") ||
               lowerMessage.contains("reliance") ||
               lowerMessage.contains("hdfc") ||
               lowerMessage.contains("bajaj") ||
               lowerMessage.contains("icici") ||
               lowerMessage.contains("axis");

        // Route to FinancialAssistant only if financial keyword + company,
        // OR explicit financial analysis request
        return (hasFinancialKeyword && (hasCompanyName || lowerMessage.contains("analysis"))) ||
               lowerMessage.contains("financial analysis") ||
               lowerMessage.contains("financial health") ||
               lowerMessage.contains("5 year forecast") ||
               lowerMessage.contains("5-year forecast");
    }

    /**
     * Detect casual/greeting messages that should NOT trigger RAG document retrieval
     * or internet search. Prevents irrelevant financial data being injected into
     * simple conversational messages like "Hi".
     */
    private boolean isCasualMessage(String userMessage) {
        if (userMessage == null) {
            return false;
        }
        String lower = userMessage.toLowerCase().trim();

        // Short greetings and introductions
        if (lower.matches("^(hi|hello|hey|hii+|yo|greetings|good morning|good afternoon|good evening)[!. ]*$")) {
            return true;
        }
        if (lower.matches("^(my name is|i am|i'm|this is|call me)\\s+.*")) {
            return true;
        }
        if (lower.matches("^(who are you|what is your name|what can you do|how are you|what are you)[?. ]*$")) {
            return true;
        }
        if (lower.matches("^(thanks|thank you|thx|ok|okay|cool|nice|great|bye|goodbye)[!. ]*$")) {
            return true;
        }

        // Very short messages with no financial intent are treated as casual
        return lower.length() <= 15 && !isFinancialAnalysisQuestion(userMessage);
    }

    /**
     * Log (logs only) which data source(s) fed the response: RAG documents,
     * internet search, or the model's own base knowledge when neither is present.
     */
    private void logResponseSources(String path, boolean ragUsed, int docCount, boolean internetUsed) {
        boolean modelOnly = !ragUsed && !internetUsed;
        log.info("📊 RESPONSE SOURCE [{}] -> RAG(documents): {}{} | Internet(Tavily): {} | Model base-knowledge only: {}",
                path,
                ragUsed ? "YES" : "NO",
                ragUsed ? " (" + docCount + " docs)" : "",
                internetUsed ? "YES" : "NO",
                modelOnly ? "YES" : "NO");
    }

    /**
     * Handle financial questions with detailed analysis
     */
    private ChatResponse handleFinancialQuestion(String userMessage, String conversationId) {
        try {
            String jsonResponse = financialAssistantService.get().analyzeFinancials(userMessage);

            // Parse the JSON response
            ChatResponse response = responseParserService.parseAIResponse(jsonResponse);

            // Save to memory
            memoryService.addMessage(conversationId, "USER", userMessage);
            if (response.getAnswer() != null) {
                memoryService.addMessage(conversationId, "ASSISTANT", response.getAnswer());
            }

            // Validate and clean chart data
            response = validateAndCleanChartData(response);

            log.info("✅ Financial analysis completed - response length: {} characters, chart data points: {}",
                    response.getAnswer() != null ? response.getAnswer().length() : 0,
                    response.getChartData() != null ? response.getChartData().size() : 0);

            return response;
        } catch (Exception e) {
            log.error("Error in financial analysis: {}", e.getMessage());
            ChatResponse fallback = new ChatResponse();
            fallback.setAnswer("Financial analysis failed: " + e.getMessage());
            return fallback;
        }
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
     * Minimal conversational system prompt for casual/greeting messages.
     * Keeps the JSON envelope (so parsing stays consistent) but does NOT push
     * financial analysis, company examples, or chart data.
     */
    private String getCasualSystemPrompt() {
        return """
You are FinSight AI, a friendly financial intelligence assistant.

The user has sent a casual or conversational message (a greeting, an introduction,
a thank-you, or small talk). Respond naturally and warmly in 1-3 sentences.

Do NOT produce any company financial analysis, numbers, historical data, forecasts,
or chart data unless the user explicitly asks for it. If they introduced themselves,
acknowledge it. You may briefly mention you can help with financial analysis when asked.

RESPONSE FORMAT - respond with VALID JSON ONLY:
{
  "answer": "your short, friendly conversational reply",
  "chartType": "none",
  "chartData": []
}

Rules: start with { and end with }, no text outside the JSON, keep chartData empty.
""";
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
     * System prompt for regular chat and financial analysis
     */
    private String getSimplifiedSystemPrompt() {
        return """
RESPONSE FORMAT: You MUST respond with VALID JSON ONLY.

MANDATORY JSON STRUCTURE:
{
  "answer": "Your response here",
  "chartType": "none",
  "chartData": []
}

CRITICAL RULES:
✓ Start with { and end with }
✓ All fields required: answer, chartType, chartData
✓ NO text before { or after }
✓ answer field: Provide complete response without truncation
✓ For casual conversation: conversational response
✓ For financial queries: structured financial analysis
✓ Always end sentences with periods, not ellipsis

JSON RULES (ABSOLUTE):
✓ Start with { and end with }
✓ All fields required: answer, chartType, chartData
✓ NO text before { or after }
✓ NO markdown, NO backticks, NO ```json
✓ NO ESCAPE ISSUES - use proper JSON escaping
✓ chartData: array with minimum 5 entries (max 10)
✓ All values must be integers (10000000 not "10000000")
✓ All years must be strings ("2020" not 2020)

ANSWER FIELD REQUIREMENTS:
- MINIMUM 1000+ words of comprehensive analysis
- NEVER truncate - complete every sentence
- Include ALL historical years with numbers
- Include forecasts with "F" suffix years (2025F, 2026F)
- Use actual numbers from your analysis
- Structure: Overview → Historical Data → Trend Analysis → Forecast → Conclusion
- Financial metrics: Revenue, Growth %, CAGR, Margins, etc.
- ALWAYS end with complete conclusion ending with period
- NEVER end mid-sentence or with "..."

CHARTDATA REQUIREMENTS:
- Minimum 5 data points, maximum 10
- Extract EXACT numbers from answer field
- Format: {"year": "2020", "value": 10000000}
- Historical years: "2020", "2021", "2022", "2023", "2024"
- Forecast years: "2025F", "2026F" (with F suffix)
- Values: Always integers, no decimals or strings
- Include both historical and forecast data

FOR COMPANY/FINANCIAL ANALYSIS QUESTIONS:

Include these sections in order:
1. COMPANY OVERVIEW (100+ words)
   - Business description
   - Market position
   - Key business segments

2. HISTORICAL FINANCIAL DATA (400+ words)
   - FY2020-2024 Revenue figures
   - Year-over-year growth percentages
   - Identify trends and patterns
   - Calculate CAGR

3. CURRENT POSITION (300+ words)
   - Latest financial metrics
   - Margins (Gross, Operating, Net)
   - Key ratios and indicators
   - Financial health assessment

4. TREND ANALYSIS (300+ words)
   - Growth trajectory
   - Comparative analysis
   - Industry position
   - Market dynamics

5. FUTURE PLANS & FORECAST (400+ words)
   - Strategic initiatives
   - Expansion plans
   - 5-year projections (2025-2026)
   - Based on historical CAGR
   - Risk factors and opportunities

6. INVESTMENT PERSPECTIVE (200+ words)
   - Overall outlook
   - Key strengths and weaknesses
   - Growth potential
   - Recommendations

CRITICAL VALIDATION (CHECK BEFORE RESPONDING):
✓ Response is valid, parseable JSON
✓ Answer field contains 1000+ words
✓ Answer ends with period, NOT truncated
✓ All mentioned years appear in both answer and chartData
✓ chartData has 5-10 entries with proper formatting
✓ No escape character issues
✓ Forecast years have "F" suffix in chartData
✓ All numbers are integers
✓ Every number in chartData matches answer text

IF ANALYZING COMPANIES (HCL, TCS, INFOSYS, RELIANCE, HDFC, WIPRO):
- You HAVE access to uploaded documents
- Extract actual financial figures from documents
- Provide specific numbers, not generic statements
- Include historical trends and future guidance
- Give clear financial perspective

COMPLETE YOUR RESPONSE:
✓ Do NOT truncate or cut off
✓ Do NOT end mid-analysis
✓ Do NOT use placeholder text
✓ Finish what you start
✓ Provide comprehensive detail
✓ End with valid JSON closing }

Now respond with COMPLETE, COMPREHENSIVE analysis in valid JSON format:
""";
    }
}