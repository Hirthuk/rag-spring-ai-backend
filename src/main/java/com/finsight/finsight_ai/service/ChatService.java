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
            // Check if this is a financial analysis question - route to FinancialAssistantService
            if (isFinancialAnalysisQuestion(userMessage) && financialAssistantService.isPresent()) {
                return handleFinancialQuestion(userMessage, conversationId);
            }

            // Check memory size before adding
            int currentMessageCount = memoryService.getMessageCount(conversationId);
            if (userMessage == null || userMessage.trim().isEmpty()) {
                return ChatResponse.error("User message cannot be empty.");
            }

            List<ChatMemoryMessage> history = memoryService.getHistory(conversationId);
            String memoryContext = promptBuilderService.buildConversationMemory(history);

            List<Document> documents = new ArrayList<>();
            boolean isCasual = isCasualMessage(userMessage);
            if (!isCasual && retrievalService.isPresent()) {
                try {
                    RetrievalService service = retrievalService.get();
                    documents = service.retrieveRelevantDocuments(service.prepareRetrievalQuery(userMessage));
                } catch (Exception e) {
                    log.warn("RAG retrieval failed: {}", e.getMessage());
                }
            }

            String ragContext = promptBuilderService.buildContext(documents);

            String internetContext = "";
            boolean tavilyUsed = false;
            if (!isCasual && searchRoutingService.shouldUseInternetSearch(userMessage)) {
                internetContext = tavilySearchService.search(userMessage);
                tavilyUsed = internetContext != null && !internetContext.isEmpty();
            }

            String userPrompt = promptBuilderService.buildUserPrompt(
                    memoryContext, ragContext, internetContext, userMessage);

            String finalSystemPrompt = isCasual
                    ? getCasualSystemPrompt()
                    : buildFinalSystemPrompt(systemMessage);

            log.info("[CHAT] RAG: {} | Tavily: {}",
                    documents.isEmpty() ? "No" : "Yes (" + documents.size() + " docs)",
                    tavilyUsed ? "Yes" : "No");
            if (!documents.isEmpty()) {
                log.info("[CHAT] Documents used: {}", extractDocumentNames(documents));
            }

            memoryService.addMessage(conversationId, "USER", userMessage);

            String aiRawResponse = aiResponseService.getAIResponse(finalSystemPrompt, userPrompt);
            ChatResponse parsedResponse = responseParserService.parseAIResponse(aiRawResponse);

            // VALIDATE AND CLEAN CHART DATA
            parsedResponse = validateAndCleanChartData(parsedResponse);

            // Ensure response has valid chart data
            if (parsedResponse.getChartData() == null) {
                parsedResponse.setChartData(new ArrayList<>());
            }
            if (parsedResponse.getChartType() == null) {
                parsedResponse.setChartType("none");
            }

            String answerText = parsedResponse.getAnswer();
            if (answerText != null && !answerText.trim().isEmpty()) {
                memoryService.addMessage(conversationId, "ASSISTANT", answerText);
                log.info("[CHAT] Response: {}", answerText);
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
            // FINANCIAL ROUTE - stream the FinSight analysis + chart data
            if (isFinancialAnalysisQuestion(userMessage) && financialAssistantService.isPresent()) {
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
                                // The raw accumulated text from FinancialAssistantService
                                // already has CHARTDATA_JSON stripped before token emission,
                                // so acc contains the clean user-visible text.
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
                    documents = service.retrieveRelevantDocuments(service.prepareRetrievalQuery(userMessage));
                } catch (Exception e) {
                    log.warn("RAG retrieval failed: {}", e.getMessage());
                }
            }
            String ragContext = promptBuilderService.buildContext(documents);
            String userPrompt = promptBuilderService.buildUserPrompt(memoryContext, ragContext, "", userMessage);
            String sysPrompt = casual ? getCasualStreamingPrompt() : getGeneralStreamingPrompt();

            log.info("[CHAT-STREAM] RAG: {} | Tavily: No",
                    documents.isEmpty() ? "No" : "Yes (" + documents.size() + " docs)");
            if (!documents.isEmpty()) {
                log.info("[CHAT-STREAM] Documents used: {}", extractDocumentNames(documents));
            }

            memoryService.addMessage(conversationId, "USER", userMessage);
            StringBuilder acc = new StringBuilder();

            Flux<ServerSentEvent<String>> tokens = aiResponseService.streamResponse(sysPrompt, userPrompt)
                    .doOnNext(acc::append)
                    .map(t -> ServerSentEvent.<String>builder(t).event("token").build());

            Flux<ServerSentEvent<String>> tail = Flux.defer(() -> {
                if (acc.length() > 0) {
                    memoryService.addMessage(conversationId, "ASSISTANT", acc.toString());
                    log.info("[CHAT-STREAM] Response: {}", acc);
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
                                ServerSentEvent.<String>builder("Sorry, something went wrong. Please try again in a moment.").event("error").build(),
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

    /** Markdown-formatted system prompt for general (non-financial) questions in streaming mode. */
    private String getGeneralStreamingPrompt() {
        return """
                You are FinSight AI, a financial intelligence assistant. Answer the user's question
                clearly and concisely using Markdown formatting.

                ABSOLUTE PROHIBITION — RAW JSON IS A CRITICAL ERROR:
                Your text answer must NEVER contain any JSON object, JSON array, code fence, or
                backtick block — even if the user explicitly asks for "JSON format", "CSV format",
                "code format", or any structured data format. The user interface ONLY renders
                Markdown; raw JSON/code is displayed as unreadable noise.
                If the user asks for JSON, silently ignore that request and present the same
                information as Markdown tables and bullet points. Never mention that you are
                ignoring the format request — just deliver clean Markdown.
                A response containing { }, [ ], ``` or any code fence is a CRITICAL ERROR.

                Formatting rules:
                - Use ## or ### headers for distinct sections
                - Use **bold** for key numbers, KPIs, and important terms
                - Use bullet lists or numbered lists when enumerating facts or steps
                - For financial data: present as clean Markdown tables, NOT raw JSON
                - Keep paragraphs short (2-3 sentences max)

                Speak as a senior financial analyst. NEVER mention documents, context, retrieval,
                datasets, JSON, or your internal process. Just deliver the analysis directly.
                """;
    }

    /**
     * Routes any company, market, investment, comparison, strategic, or scenario question
     * to FinancialAssistantService which handles all 8 levels of financial intelligence.
     */
    private boolean isFinancialAnalysisQuestion(String userMessage) {
        if (userMessage == null) return false;
        String q = userMessage.toLowerCase().trim();

        // Never route greetings or personal questions
        if (q.matches("^(hi|hello|hey|hii+|yo|greetings|good morning|good afternoon|good evening)[!. ]*$")) return false;
        if (q.matches("^(who are you|what is your name|what can you do|how are you|what are you)[?. ]*$")) return false;
        if (q.matches("^(thanks|thank you|thx|ok|okay|cool|nice|great|bye|goodbye)[!. ]*$")) return false;
        if (q.matches("^(my name is|i am|i'm|this is|call me)\\s+.*")) return false;

        // Block personal / generic questions whose subject is NOT a company
        // e.g. "What is my name?", "Who am I?", "What time is it?", "Where do I live?"
        // GUARD: if the query also has financial keywords ("What is the Apex Company growth?"),
        // do NOT block it — let it fall through to the financial routing checks below.
        if (q.matches("^(what|who|how|where|when|why)\\s+(is|are|was|were|am|do|does|did|can|could|should|would|will)\\s+(i|my|me|we|our|you|your|it|the|this|that|a|an)\\b.*")
                && !hasFinancialKeywordsQuick(q)) return false;
        // "Can you tell me your name?", "Do you know me?"
        if (q.matches("^(can|could|would|do|did|have|has|is|are)\\s+(you|i|we|they)\\b.*") && !hasFinancialKeywordsQuick(q)) return false;

        // --- Level 1: company overview (known companies) ---
        if (q.matches(".*(tell me about|what does|overview of|give me an overview|about|who is|describe).*(company|corp|inc|ltd|technologies|tech|bank|industries|motors|pharma|amazon|microsoft|google|apple|meta|netflix|nvidia|tesla|tcs|infosys|hcl|wipro|reliance|hdfc|bajaj|icici).*")) return true;

        // --- Level 2: historical financial data ---
        boolean hasFinancialWord =
                q.contains("revenue") || q.contains("profit") || q.contains("earnings") ||
                q.contains("ebitda") || q.contains("cash flow") || q.contains("turnover") ||
                q.contains("margin") || q.contains("growth") || q.contains("financial") ||
                q.contains("sales") || q.contains("income") || q.contains("performance");
        if (hasFinancialWord && (hasCompanyMention(q) || q.contains("historical") || q.contains("trend") || q.contains("last 5 year") || q.contains("last 3 year"))) return true;

        // --- Level 1 / 2: ANY company (unknown names) with overview / growth intent ---
        // Catches "Tell me about Presidio growth", "Show me Accenture revenue", etc.
        boolean hasOverviewIntent =
                q.contains("tell me about") || q.contains("overview of") || q.contains("give me an overview") ||
                q.contains("growth of") || q.contains("performance of") || q.contains("history of") ||
                q.contains("show me") || q.contains("analyse") || q.contains("analyze") ||
                q.matches(".*how (is|was|has|did|have)\\b.*");
        if (hasOverviewIntent && hasFinancialWord) return true;

        // --- Level 3: health / weakness / strength diagnosis ---
        if ((q.contains("health") || q.contains("diagnos") || q.contains("weakness") || q.contains("weakness") ||
             q.contains("strength") || q.contains("identify risk") || q.contains("how is") || q.contains("how healthy")) && hasCompanyMention(q)) return true;

        // --- Level 4: forecasting ---
        if ((q.contains("predict") || q.contains("forecast") || q.contains("next 5 year") || q.contains("next 3 year") ||
             q.contains("future revenue") || q.contains("future profit") || q.contains("will") && q.contains("reach")) && (hasCompanyMention(q) || hasFinancialWord)) return true;

        // Future year mentions: "Where will X be in 2030?", "X revenue by 2028", "growth in 2031"
        boolean hasFutureYear = q.matches(".*\\b(202[6-9]|203[0-9])\\b.*");
        boolean hasFutureGrowthPhrase = q.contains("future growth") || q.contains("future of") ||
                q.contains("by 20") || q.contains("in coming year") || q.contains("next few year") ||
                q.contains("going forward") || q.contains("years from now") || q.contains("projected growth");
        if (hasFutureYear || hasFutureGrowthPhrase) return true;

        // --- Level 5: comparison ---
        // NOTE: "comparison" does NOT contain "compare" as a substring — check both
        if ((q.contains("compare") || q.contains("comparison") || q.contains(" vs ") || q.contains(" versus ") ||
             q.contains("better than") || q.contains("which is better") || q.contains("head-to-head") ||
             q.contains("head to head") || q.contains("between") && hasCompanyMention(q)) &&
            (hasCompanyMention(q) || hasFinancialWord)) return true;

        // --- Level 6: investment ---
        if ((q.contains("invest") || q.contains("should i buy") || q.contains("buy or sell") ||
             q.contains("portfolio") || q.contains("long-term") || q.contains("stock") || q.contains("share price")) && hasCompanyMention(q)) return true;

        // --- Level 7: strategic / advisory ---
        if ((q.contains("focus on") || q.contains("strategy") || q.contains("improve profit") ||
             q.contains("improve margin") || q.contains("what risks") || q.contains("risks affecting") ||
             q.contains("what should") || q.contains("how can") && q.contains("improve")) && hasCompanyMention(q)) return true;

        // --- Level 8: scenario / what-if ---
        if (q.startsWith("what if") || q.contains("what happens if") || q.contains("scenario analysis") ||
            q.contains("what would happen") || (q.contains("can") && q.contains("become") && hasCompanyMention(q)) ||
            q.contains("trillion dollar") || q.contains("lakh crore") || q.contains("billion dollar revenue")) return true;

        // --- Explicit financial terms always route ---
        if (q.contains("financial analysis") || q.contains("financial health") ||
               q.contains("5 year forecast") || q.contains("5-year forecast") ||
               q.contains("valuation") || q.contains("pe ratio") || q.contains("roe") ||
               q.contains("investment outlook") || q.contains("growth analysis")) return true;

        // --- Follow-up questions with a proper noun AFTER the trigger word ---
        // The capital letter must come AFTER "about", "on", etc — not just be the first
        // letter of the sentence. We check on the ORIGINAL userMessage so that [A-Z]
        // stays case-sensitive ((?i) would make [A-Z] match lowercase too).
        //
        // "what about Presidio?" → capital P after "about " ✓
        // "What is my name?"    → no "about" trigger, blocked by exclusion above ✓
        // "what about the weather?" → lowercase 't' after "about " ✗
        boolean looksLikeFollowUp =
                // "what about Presidio?", "how about TCS?"
                userMessage.matches("[Ww]hat\\s+about\\s+[A-Z][a-zA-Z]\\w*.*") ||
                userMessage.matches("[Hh]ow\\s+about\\s+[A-Z][a-zA-Z]\\w*.*") ||
                // "tell me about Presidio", "show me Accenture", "give me info on HCL"
                userMessage.matches("[Tt]ell\\s+me\\s+(more\\s+)?(about\\s+)?[A-Z][a-zA-Z]\\w*.*") ||
                userMessage.matches("[Ss]how\\s+me\\s+(more\\s+)?(about\\s+)?[A-Z][a-zA-Z]\\w*.*") ||
                userMessage.matches("[Gg]ive\\s+me\\s+(more\\s+)?(about\\s+)?[A-Z][a-zA-Z]\\w*.*") ||
                // "More on Presidio", "Details on TCS", "Info about Wipro"
                userMessage.matches("[Mm]ore\\s+on\\s+[A-Z][a-zA-Z]\\w*.*") ||
                userMessage.matches("[Dd]etails\\s+(on|about)\\s+[A-Z][a-zA-Z]\\w*.*") ||
                userMessage.matches("[Ii]nfo\\s+(on|about)\\s+[A-Z][a-zA-Z]\\w*.*");
        if (looksLikeFollowUp) return true;

        return false;
    }

    /** Quick check used only inside routing exclusions — avoids circular calls. */
    private boolean hasFinancialKeywordsQuick(String q) {
        return q.contains("revenue") || q.contains("profit") || q.contains("stock") ||
               q.contains("invest") || q.contains("financial") || q.contains("market cap") ||
               q.contains("earnings") || q.contains("growth") || q.contains("forecast");
    }

    private boolean hasCompanyMention(String lower) {
        // Indian IT
        if (lower.contains("hcl") || lower.contains("tcs") || lower.contains("infosys") || lower.contains("wipro")) return true;
        // Indian financial / conglomerates
        if (lower.contains("reliance") || lower.contains("hdfc") || lower.contains("bajaj") ||
            lower.contains("icici") || lower.contains("axis") || lower.contains("rossell")) return true;
        // Global tech
        if (lower.contains("amazon") || lower.contains("microsoft") || lower.contains("google") ||
            lower.contains("apple") || lower.contains("meta") || lower.contains("netflix") ||
            lower.contains("nvidia") || lower.contains("tesla") || lower.contains("alphabet") ||
            lower.contains("aws") || lower.contains("azure")) return true;
        // Generic company suffixes
        if (lower.matches(".*(ltd|inc|corp|technologies|bank|industries|pharma|motors).*")) return true;
        return false;
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

    // kept for any remaining callers; new code logs directly
    private void logResponseSources(String path, boolean ragUsed, int docCount, boolean internetUsed) {
        log.info("[{}] RAG: {} | Tavily: {}", path,
                ragUsed ? "Yes (" + docCount + " docs)" : "No",
                internetUsed ? "Yes" : "No");
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

            // If the model returned empty chartData but the answer has year-over-year figures,
            // extract them as a fallback so the frontend always gets chart points.
            if ((response.getChartData() == null || response.getChartData().isEmpty())
                    && response.getAnswer() != null && !response.getAnswer().isBlank()) {
                List<Map<String, Object>> extracted =
                        financialAssistantService.get().extractChartDataFromText(response.getAnswer());
                if (!extracted.isEmpty()) {
                    response.setChartData(extracted);
                    response.setChartType("line");
                }
            }

            if (response.getAnswer() != null) {
                log.info("[FINSIGHT] Response: {}", response.getAnswer());
            }

            return response;
        } catch (Exception e) {
            log.error("Error in financial analysis: {}", e.getMessage());
            ChatResponse fallback = new ChatResponse();
            fallback.setAnswer("Sorry, I couldn't complete that analysis right now. Please try again in a moment.");
            fallback.setChartType("none");
            fallback.setChartData(new ArrayList<>());
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
     * System prompt for regular chat and financial analysis (non-FinancialAssistantService path).
     */
    private String getSimplifiedSystemPrompt() {
        return """
You are FinSight AI, an enterprise financial intelligence assistant.

════════════════════════════════════════════════════════════
OUTPUT FORMAT — STRICT JSON (one object, no other text)
════════════════════════════════════════════════════════════

You MUST respond with ONLY this JSON structure:
{
  "answer": "<markdown string>",
  "chartType": "line" | "bar" | "none",
  "chartData": [{"year": "YYYY", "value": 123456789}]
}

JSON RULES (no exceptions):
• Start with { and end with } — no text outside
• The answer value is a JSON string: use \\n for newlines, \\" for quotes
• No markdown code fences, no backticks, no ```json wrapper
• chartData values must be plain integers (706760000000 not "706.76B")
• chartData years must be strings ("2020" not 2020), forecast years use "F" suffix ("2025F")
• chartType must be "line", "bar", or "none"

════════════════════════════════════════════════════════════
ANSWER FIELD — MARKDOWN FORMATTING
════════════════════════════════════════════════════════════

The answer value is rendered as Markdown. Rules:
• Use ## for section headers — always followed by \\n\\n before content
• Use **bold** for key numbers, KPIs, and important terms
• Use - bullet lists for facts, metrics, or enumerated points
• Use | Markdown tables | for structured data comparisons
• NEVER concatenate a header onto the same line as content
• Keep paragraphs short — 2-3 sentences max
• End every response with a complete sentence and a period

════════════════════════════════════════════════════════════
USE-CASE EXAMPLES — FOLLOW THESE PATTERNS EXACTLY
════════════════════════════════════════════════════════════

─── EXAMPLE 1: General knowledge / definition question ───
USER: "What is EBITDA?"
OUTPUT:
{
  "answer": "## What is EBITDA?\\n\\n**EBITDA** stands for **Earnings Before Interest, Taxes, Depreciation, and Amortisation**. It is a widely used measure of a company's core operational profitability, stripping out non-operating costs.\\n\\n## Why It Matters\\n\\n- It allows **apples-to-apples comparison** across companies with different capital structures.\\n- Investors use it to estimate **enterprise value** via the EV/EBITDA multiple.\\n- A higher EBITDA margin (EBITDA ÷ Revenue) indicates **greater operating efficiency**.\\n\\n## Quick Formula\\n\\nEBITDA = Net Profit + Interest + Taxes + Depreciation + Amortisation\\n\\nFor example, if a company earns **Rs 10,000 Cr net profit** with Rs 500 Cr interest, Rs 2,000 Cr taxes, and Rs 1,500 Cr D&A, its EBITDA is **Rs 14,000 Cr**.",
  "chartType": "none",
  "chartData": []
}

─── EXAMPLE 2: Single-metric lookup question ───
USER: "What is HCL's latest revenue?"
OUTPUT:
{
  "answer": "## HCL Technologies — Latest Revenue\\n\\nHCL Technologies reported **Rs 1,30,144 Crore** in revenue for **FY2026** (year ended 31 March 2026), marking an **11.2% year-over-year increase** from Rs 1,17,055 Crore in FY2025.\\n\\n## Revenue Trend (Last 3 Years)\\n\\n| FY | Revenue (Rs Crore) | YoY Growth |\\n|----|-------------------|------------|\\n| FY2024 | 1,09,913 | +8.3% |\\n| FY2025 | 1,17,055 | +6.5% |\\n| FY2026 | 1,30,144 | +11.2% |\\n\\nRevenue growth has **re-accelerated in FY2026** after a brief moderation, driven by expansion in cloud services and digital transformation contracts.",
  "chartType": "bar",
  "chartData": [{"year": "2024", "value": 1099130000000}, {"year": "2025", "value": 1170550000000}, {"year": "2026", "value": 1301440000000}]
}

─── EXAMPLE 3: Full financial analysis question ───
USER: "Analyse HCL Technologies financials" or "Show me HCL revenue trend"
OUTPUT:
{
  "answer": "## 🏢 Company Overview\\n\\nHCL Technologies Ltd is a leading Indian IT services company headquartered in Noida, India, operating in **Information Technology Services**. It provides application development, IT infrastructure management, cloud solutions, and engineering services to global enterprise clients. With a **market capitalisation of Rs 3,09,819 Crore** and a current share price of **Rs 1,141.7**, HCL is one of India's Big-4 IT firms.\\n\\n---\\n\\n## 📊 Current Financial Snapshot\\n\\n| Metric | Value |\\n|--------|-------|\\n| **Revenue (FY2026)** | Rs 1,30,144 Crore |\\n| **Net Profit (FY2026)** | Rs 16,642 Crore |\\n| **Net Margin** | 12.8% |\\n| **9-Year Revenue CAGR** | ~11.9% |\\n| **Financial Health** | Strong |\\n\\n---\\n\\n## 📈 Historical Financial Performance\\n\\nHCL has delivered consistent double-digit revenue growth over nine fiscal years, growing from **Rs 47,568 Crore in FY2017** to **Rs 1,30,144 Crore in FY2026** — a **2.7× increase** in nine years.\\n\\n| FY | Revenue (Rs Crore) | YoY Growth | Net Profit (Rs Crore) | Net Margin |\\n|----|-------------------|------------|----------------------|------------|\\n| FY2017 | 47,568 | — | 8,606 | 18.1% |\\n| FY2018 | 50,569 | +6.3% | 8,721 | 17.2% |\\n| FY2019 | 60,427 | +19.5% | 10,120 | 16.7% |\\n| FY2020 | 70,676 | +17.0% | 11,057 | 15.6% |\\n| FY2021 | 75,379 | +6.7% | 11,145 | 14.8% |\\n| FY2022 | 85,651 | +13.6% | 13,499 | 15.8% |\\n| FY2023 | 1,01,456 | +18.5% | 14,851 | 14.6% |\\n| FY2024 | 1,09,913 | +8.3% | 15,702 | 14.3% |\\n| FY2025 | 1,17,055 | +6.5% | 17,390 | 14.9% |\\n| FY2026 | 1,30,144 | +11.2% | 16,642 | 12.8% |\\n\\n---\\n\\n## 💡 Key Insights\\n\\n- **Consistent Revenue Compounder:** 9-year revenue CAGR of ~11.9% reflects durable demand for HCL's IT services across geographies.\\n- **Margin Compression Trend:** Net margin declined from **18.1% (FY2017)** to **12.8% (FY2026)** driven by rising depreciation, interest costs, and wage inflation.\\n- **FY2026 Profit Dip:** Despite record revenue, net profit fell to Rs 16,642 Cr from Rs 17,390 Cr in FY2025, primarily due to higher interest expenses and lower other income.\\n- **Strong Cash Generation:** Operating cash flow has grown from Rs 8,995 Cr (FY2017) to Rs 19,975 Cr (FY2026), funding dividends and capex organically.\\n\\n---\\n\\n## 🔮 5-Year Forecast (Projected)\\n\\n> **Projected Revenue CAGR:** ~10% per year\\n> **Basis:** 9-year historical CAGR of 11.9%, moderated slightly for market maturity and margin pressure\\n\\n| Year | Revenue (Rs Crore) | YoY Growth | Net Profit (Rs Crore) | Margin |\\n|------|--------------------|------------|----------------------|--------|\\n| 2027F | 1,43,158 | +10% | 18,310 | 12.8% |\\n| 2028F | 1,57,474 | +10% | 20,141 | 12.8% |\\n| 2029F | 1,73,221 | +10% | 22,155 | 12.8% |\\n| 2030F | 1,90,543 | +10% | 24,370 | 12.8% |\\n| 2031F | 2,09,597 | +10% | 26,807 | 12.8% |\\n\\nBased on its historical growth trajectory, HCL is projected to cross **Rs 2 lakh Crore in revenue by FY2031**, assuming a stable 10% annual growth rate. Margin recovery is contingent on operating leverage gains in cloud and products businesses.\\n\\n---\\n\\n## 🎯 Executive Outlook\\n\\n**Rating: Positive**\\n\\nHCL Technologies is a fundamentally strong IT compounder with a proven track record of consistent revenue growth and robust cash generation. The near-term headwind is margin compression, but the structural demand for cloud migration and digital transformation services keeps the long-term story intact. Investors should monitor the FY2027 margin trajectory as a key signal for re-rating.",
  "chartType": "line",
  "chartData": [{"year": "2017", "value": 475680000000}, {"year": "2018", "value": 505690000000}, {"year": "2019", "value": 604270000000}, {"year": "2020", "value": 706760000000}, {"year": "2021", "value": 753790000000}, {"year": "2022", "value": 856510000000}, {"year": "2023", "value": 1014560000000}, {"year": "2024", "value": 1099130000000}, {"year": "2025", "value": 1170550000000}, {"year": "2026", "value": 1301440000000}]
}

─── EXAMPLE 4: Risk / specific-topic question ───
USER: "What are the risks of investing in HCL?"
OUTPUT:
{
  "answer": "## ⚠️ Key Investment Risks — HCL Technologies\\n\\n- **Margin Compression:** Net margin has declined from **18.1% (FY2017)** to **12.8% (FY2026)**, a 530 basis point erosion over nine years. Continued cost inflation could push margins below the 12% threshold.\\n- **Currency Risk:** HCL earns a significant portion of revenue in USD and EUR. Rupee appreciation compresses revenue realisation and reported profits in INR terms.\\n- **Client Concentration:** Heavy reliance on top-20 enterprise clients in the US and Europe creates exposure to budget cuts during economic downturns.\\n- **Talent Cost Inflation:** India's IT talent market remains competitive; salary increments and attrition costs pressure operating margins.\\n- **FY2026 Profit Decline:** Net profit fell **-4.3% YoY** to Rs 16,642 Cr in FY2026 despite 11.2% revenue growth — a warning sign that revenue growth is not fully translating to bottom-line improvement.\\n\\n## Balancing Factors\\n\\n- Strong operating cash flow of **Rs 19,975 Crore** (FY2026) provides resilience.\\n- Debt remains manageable at Rs 5,215 Crore with cash reserves of Rs 23,425 Crore.",
  "chartType": "none",
  "chartData": []
}

════════════════════════════════════════════════════════════
FINAL CHECKLIST BEFORE OUTPUTTING
════════════════════════════════════════════════════════════

✓ Response starts with { and ends with }
✓ answer contains proper \\n escape sequences (not literal newlines)
✓ Every ## header has \\n\\n after it before the next content
✓ No text outside the JSON object
✓ chartData values are plain integers, chartData years are strings
✓ NEVER mention documents, context, retrieval, or your internal process
""";
    }

    private List<String> extractDocumentNames(List<Document> documents) {
        return documents.stream()
                .map(doc -> {
                    Object name = doc.getMetadata().get("fileName");
                    return name != null ? name.toString() : "unknown";
                })
                .distinct()
                .collect(Collectors.toList());
    }
}