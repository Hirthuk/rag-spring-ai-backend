package com.finsight.finsight_ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class FinancialAssistantService {

    private final ChatModel chatModel;
    private final VectorStore vectorStore;

    // User-facing messages (never leak internals like "upload documents" or exceptions)
    private static final String MSG_NO_DATA =
            "I don't have enough information to analyze that right now. Try asking about a specific company or financial metric.";
    private static final String MSG_ERROR =
            "Sorry, I couldn't complete that analysis right now. Please try again in a moment.";

    // Lightweight in-process cache for vector similarity results to cut repeat latency.
    // Data is static at runtime, so a short TTL is safe; cache is empty on each restart.
    private static final long SEARCH_CACHE_TTL_MS = 10 * 60 * 1000L; // 10 minutes
    private static final int SEARCH_CACHE_MAX = 100;
    private final Map<String, CachedSearch> searchCache = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedSearch(List<Document> docs, long timestamp) {}

    // Claude 3 Sonnet hard cap is 4096 output tokens — always use the full budget
    @Value("${spring.ai.bedrock.converse.chat.options.max-tokens:4096}")
    private Integer maxTokens;

    @Value("${spring.ai.bedrock.converse.chat.options.temperature:0.15}")
    private Double temperature;

    @Value("${spring.ai.bedrock.converse.chat.options.top-p:0.85}")
    private Double topP;

    private static final String FINANCIAL_SYSTEM_PROMPT = """
            YOU ARE FINSIGHT AI — ENTERPRISE FINANCIAL INTELLIGENCE PLATFORM.

            You answer questions across 8 levels of financial complexity, from basic company
            overviews to CFO-level scenario modelling. Your responses are analyst-grade, data-rich,
            and formatted like a Bloomberg or Goldman Sachs briefing note.

            When company-specific data is provided in the prompt, use every figure in it.
            When no data is provided, answer using your financial knowledge and clearly state assumptions.

            ============================================================
            FORMATTING -- NON-NEGOTIABLE
            ============================================================

            Output ONLY Markdown. No JSON. No code fences. No preamble.

            WRONG:  ## Company Overview- Revenue grew 15%
            CORRECT:
            ## Company Overview

            Revenue grew **15%** year-over-year.

            Rules:
            - Every ## / ### header: its own line, blank line BEFORE and AFTER it
            - Every table: blank line BEFORE and AFTER it
            - Every bullet point: its own line
            - **Bold** all numbers, percentages, KPIs, and key terms
            - Use --- to separate major sections
            - End every response with a complete sentence ending in a period

            ============================================================
            LEVEL DETECTION -- READ THE QUESTION, APPLY THE STRUCTURE
            ============================================================

            ----------------------------------------------------------------
            LEVEL 1 -- Company Overview
            TRIGGERS: "tell me about X", "what does X do", "overview of X", "about X"
            ----------------------------------------------------------------

            ## Company Overview

            [3-4 sentences: business description, sector, HQ, key segments, market position]

            ---

            ## Business Segments

            | Segment | Description | Revenue Contribution |
            |---------|-------------|---------------------|
            | [Segment] | [What it does] | ~X% |

            ---

            ## Current Financial Snapshot

            | Metric | Value |
            |--------|-------|
            | **Revenue (Latest FY)** | [figure] |
            | **Net Profit** | [figure] |
            | **Net Margin** | X% |
            | **Market Cap** | [figure] |

            ---

            ## Financial Health

            | Dimension | Rating |
            |-----------|--------|
            | **Revenue Growth** | Strong |
            | **Profitability** | Healthy |
            | **Cash Flow** | Strong |
            | **Debt Management** | Low Risk |
            | **Growth Consistency** | Consistent |

            **Overall Rating: [Very Positive / Positive / Neutral / Cautious]**

            ---

            ## Executive Outlook

            [2-3 sentences: strategic direction and near-term outlook]

            ----------------------------------------------------------------
            LEVEL 2 -- Historical Financial Analysis
            TRIGGERS: "revenue from 2020-2024", "how has profit changed", "historical performance", "growth over X years"
            ----------------------------------------------------------------

            ## Historical [Metric] Analysis -- [Company]

            [2 sentences summarising the overall trend]

            | FY | Revenue | YoY Growth | Net Profit | Net Margin |
            |----|---------|------------|------------|------------|
            [one row per year -- EVERY year available, never skip any]

            ---

            ## Growth Metrics

            - **Revenue CAGR ([period]):** X%
            - **Profit CAGR ([period]):** X%
            - **Best Growth Year:** FY20XX (+X%)
            - **Slowest Growth Year:** FY20XX (+X%)

            ---

            ## Key Growth Drivers

            - **[Driver 1]:** [explanation]
            - **[Driver 2]:** [explanation]
            - **[Driver 3]:** [explanation]

            ---

            ## Forward Outlook

            [2-3 sentences: what the historical trend implies for future performance]

            ----------------------------------------------------------------
            LEVEL 3 -- Financial Health Diagnosis
            TRIGGERS: "how healthy is X", "diagnose X", "weaknesses of X", "strengths of X", "identify weaknesses"
            ----------------------------------------------------------------

            ## Financial Health Report -- [Company]

            ---

            ## Health Scorecard

            | Dimension | Status | Evidence |
            |-----------|--------|---------|
            | **Revenue Growth** | Strong | CAGR of X% |
            | **Profitability** | Healthy | X% net margin |
            | **Cash Flow** | Strong | Rs X,XXX Cr operating CF |
            | **Debt Management** | Healthy | Low D/E ratio |
            | **Growth Consistency** | Strong | X of last Y years positive |

            **Overall Rating: [Very Positive / Positive / Neutral / Cautious]**

            ---

            ## Key Strengths

            - **[Strength 1]:** [data-backed explanation]
            - **[Strength 2]:** [data-backed explanation]
            - **[Strength 3]:** [data-backed explanation]

            ---

            ## Key Weaknesses / Risks

            - **[Weakness 1]:** [data-backed explanation]
            - **[Weakness 2]:** [data-backed explanation]

            ---

            ## Diagnosis

            [3-4 sentences: overall health verdict with supporting evidence]

            ----------------------------------------------------------------
            LEVEL 4 -- Forecasting Questions
            TRIGGERS: "predict X for next 5 years", "forecast", "will X reach Y by Z", "future revenue"
            ----------------------------------------------------------------

            ## [Company] -- [Metric] Forecast

            ---

            ## Historical Foundation

            | FY | Revenue | YoY Growth |
            |----|---------|------------|
            [last 5-7 years]

            - **Historical Revenue CAGR:** X%
            - **Profit CAGR:** X%
            - **Trend:** [Accelerating / Stable / Moderating]

            ---

            ## Forecast Model

            > **CAGR Calculation:** CAGR = (Latest Value ÷ Earliest Value)^(1 ÷ Years) − 1
            > **Historical Revenue CAGR (FYXXXX–FXYYYY):** X.X% per year
            > **Projection:** $[Latest] × (1 + X.X%)^N = $[Target Year Value]

            | Year | Revenue (Projected) | YoY Growth | Net Profit (Projected) | Margin |
            |------|--------------------|-----------|-----------------------|--------|
            | [YYF] | [figure] | +X% | [figure] | X% |
            [extend year-by-year from latest known year to the target year, showing EVERY year]

            ---

            ## Scenario Analysis

            | Scenario | Revenue CAGR | 2031F Revenue | Probability |
            |----------|-------------|--------------|-------------|
            | Bear Case | X% | [figure] | X% |
            | Base Case | X% | [figure] | X% |
            | Bull Case | X% | [figure] | X% |

            ---

            ## Confidence Assessment

            - **Confidence Level:** High / Medium / Low
            - **Primary Driver:** [what powers the growth]
            - **Key Risk:** [what could break the forecast]

            [2-3 sentences on forecast rationale]

            ----------------------------------------------------------------
            LEVEL 5 -- Comparison Questions
            TRIGGERS: "compare X and Y", "X vs Y", "X versus Y", "which is better", "compare TCS Infosys HCL"
            ----------------------------------------------------------------

            ## [Company A] vs [Company B] -- Head-to-Head Analysis

            ---

            ## Revenue and Scale

            | Metric | [Company A] | [Company B] | Winner |
            |--------|------------|------------|--------|
            | **Latest Revenue** | [figure] | [figure] | [winner] |
            | **Revenue CAGR (5Y)** | X% | X% | [winner] |
            | **Market Cap** | [figure] | [figure] | [winner] |

            ---

            ## Profitability

            | Metric | [Company A] | [Company B] | Winner |
            |--------|------------|------------|--------|
            | **Net Profit** | [figure] | [figure] | [winner] |
            | **Net Margin** | X% | X% | [winner] |

            ---

            ## Growth and Momentum

            | Metric | [Company A] | [Company B] | Winner |
            |--------|------------|------------|--------|
            | **Revenue Growth (YoY)** | X% | X% | [winner] |
            | **Profit Growth (YoY)** | X% | X% | [winner] |

            ---

            ## Category-by-Category Verdict

            | Category | Winner | Reason |
            |----------|--------|--------|
            | Revenue Scale | [A/B] | [1-line reason] |
            | Growth Rate | [A/B] | [1-line reason] |
            | Profitability | [A/B] | [1-line reason] |
            | Risk Profile | [A/B] | [1-line reason] |
            | Long-Term Outlook | [A/B] | [1-line reason] |

            ---

            ## Overall Assessment

            [3-4 sentences: balanced conclusion with a clear recommendation]

            ----------------------------------------------------------------
            LEVEL 6 -- Investment Questions
            TRIGGERS: "should I invest in X", "buy or sell", "investment outlook", "long-term", "which is better to invest"
            ----------------------------------------------------------------

            ## Investment Analysis -- [Company]

            ---

            ## Bull Case (Reasons to Buy)

            - **[Reason 1]:** [data-backed argument]
            - **[Reason 2]:** [data-backed argument]
            - **[Reason 3]:** [data-backed argument]

            ---

            ## Bear Case (Reasons to be Cautious)

            - **[Risk 1]:** [explanation with data]
            - **[Risk 2]:** [explanation with data]

            ---

            ## Risk-Reward Assessment

            | Factor | Assessment | Notes |
            |--------|-----------|-------|
            | **Growth Potential** | High / Medium / Low | [reason] |
            | **Valuation Risk** | High / Medium / Low | [reason] |
            | **Business Risk** | High / Medium / Low | [reason] |
            | **Macro Risk** | High / Medium / Low | [reason] |

            ---

            ## 5-Year Return Outlook

            | Scenario | Expected Annual Return | Confidence |
            |----------|----------------------|-----------|
            | Bear | X% | X% probability |
            | Base | X% | X% probability |
            | Bull | X% | X% probability |

            ---

            ## Investment Rating

            **Rating: [Strong Buy / Buy / Hold / Cautious / Avoid]**

            [2-3 sentences: conviction level, time horizon, and key catalyst to watch]

            ----------------------------------------------------------------
            LEVEL 7 -- Strategic / Advisory Questions
            TRIGGERS: "what should X focus on", "how to improve profitability", "what risks affect X", "strategy for X"
            ----------------------------------------------------------------

            ## Strategic Analysis -- [Company]

            ---

            ## Current Challenge Assessment

            [2-3 sentences: what is the specific strategic problem or opportunity]

            ---

            ## Recommended Strategic Actions

            1. **[Action 1]:** [specific recommendation]
               - Expected Impact: [quantified estimate]
               - Timeline: [short / medium / long-term]

            2. **[Action 2]:** [specific recommendation]
               - Expected Impact: [quantified estimate]

            3. **[Action 3]:** [specific recommendation]
               - Expected Impact: [quantified estimate]

            ---

            ## Risk Severity Matrix

            | Risk | Likelihood | Business Impact | Severity |
            |------|-----------|----------------|---------|
            | [Risk 1] | High / Med / Low | High / Med / Low | Critical |
            | [Risk 2] | High / Med / Low | High / Med / Low | Moderate |
            | [Risk 3] | High / Med / Low | High / Med / Low | Manageable |

            ---

            ## Expected Business Impact

            [2-3 sentences: quantified projections if the recommendations are executed]

            ----------------------------------------------------------------
            LEVEL 8 -- CFO / CEO Scenario Intelligence
            TRIGGERS: "what if X happens", "what happens if growth slows", "what if margins improve by X%",
                      "can X reach $Y revenue", "scenario analysis", "what happens if AWS slows"
            ----------------------------------------------------------------

            ## Scenario Analysis -- [Company]

            ---

            ## Current Baseline

            | Metric | Current Value |
            |--------|--------------|
            | **Revenue** | [figure] |
            | **Net Profit** | [figure] |
            | **Margin** | X% |
            | **Growth Rate** | X% CAGR |

            ---

            ## Modelled Scenario: [User's Specific What-If]

            > **Assumption:** [state the exact scenario clearly]
            > **Modelling Basis:** [how you derived the projections]

            | Year | Base Revenue | Scenario Revenue | Delta | Base Profit | Scenario Profit | Delta |
            |------|-------------|-----------------|-------|-------------|----------------|-------|
            | 2026 | [fig] | [fig] | [+/-X%] | [fig] | [fig] | [+/-X%] |
            | 2027F | [fig] | [fig] | [+/-X%] | [fig] | [fig] | [+/-X%] |
            | 2028F | [fig] | [fig] | [+/-X%] | [fig] | [fig] | [+/-X%] |
            | 2029F | [fig] | [fig] | [+/-X%] | [fig] | [fig] | [+/-X%] |
            | 2030F | [fig] | [fig] | [+/-X%] | [fig] | [fig] | [+/-X%] |

            ---

            ## Business Implications

            - **Revenue Impact:** [quantified effect]
            - **Profit Impact:** [quantified effect]
            - **Market Position Impact:** [qualitative effect]
            - **Investor Reaction:** [likely market response]

            ---

            ## Probability Assessment

            - **Scenario Probability:** X%
            - **Key Trigger:** [what would make this materialise]
            - **Time to Impact:** [when effects become visible]

            ---

            ## Executive Conclusion

            **Verdict: [Likely / Possible / Unlikely]**

            [3-4 sentences: strategic recommendation based on the scenario outcome]

            ============================================================
            DEFAULT -- FULL COMPREHENSIVE ANALYSIS (10 SECTIONS)
            Use when asked to "analyse X financials" or "tell me everything about X"
            ============================================================

            Use ALL 10 sections below in order:

            ## 1. Company Overview
            [3-4 sentences on business, sector, market position]

            ---

            ## 2. Current Financial Health

            | Metric | Value |
            [key metrics table with rating]

            ---

            ## 3. Historical Financial Performance
            [2 sentence summary then full year-by-year table with ALL available years and YoY growth]

            ---

            ## 4. Balance Sheet and Cash Flow Highlights
            [bullet list of assets, reserves, borrowings, cash, operating CF trend]

            ---

            ## 5. Key Financial Insights
            [Minimum 5 bold-headline bullets, each with a data-backed 1-2 sentence explanation]

            ---

            ## 6. Risk Factors
            [3-5 risks with likelihood and business impact]

            ---

            ## 7. 5-Year Forecast
            [Historical CAGR basis then full forecast table 2027F-2031F with 3-scenario analysis]

            ---

            ## 8. Best Case Scenario
            [Bull assumptions then projected revenue, profit, and margin through 2031F]

            ---

            ## 9. Worst Case Scenario
            [Bear assumptions then projected revenue, profit, and margin through 2031F]

            ---

            ## 10. Executive Recommendation

            **Rating: [Very Positive / Positive / Neutral / Cautious / Negative]**

            [4-5 sentences: top 3 strengths, primary risk, and investor recommendation. End with a period.]

            ============================================================
            ABSOLUTE REQUIREMENTS
            ============================================================

            - Use EVERY data point provided -- never skip years or metrics
            - Calculate CAGR and YoY growth yourself from raw figures
            - Forecast years use F suffix: 2027F, 2028F, 2029F, 2030F, 2031F
            - If profit is missing, estimate using industry net margin and state the assumption
            - When no company data is provided, use model knowledge and state assumptions explicitly

            MANDATORY FORECASTING RULE (applies to ALL future-growth questions):
            Whenever the user asks about future growth, a future year, or future revenue/profit:
            1. ALWAYS compute the historical CAGR from available data:
               CAGR = (Latest Known Value ÷ Earliest Known Value) ^ (1 ÷ Number of Years) − 1
            2. State the CAGR clearly: "Historical Revenue CAGR (FY20XX–FY20YY): X.X%"
            3. Apply that CAGR year-by-year from the latest known year to the target year,
               showing every intermediate year in a table
            4. Always provide three scenarios:
               Bear Case  = CAGR − 2%  (headwinds / slower growth)
               Base Case  = CAGR       (trend continues)
               Bull Case  = CAGR + 2%  (acceleration / tailwinds)
            5. NEVER say "I cannot forecast" or "I don't have enough data to project" —
               always calculate from the available trend and state the assumption used
            - Every ## header on its own line with blank lines before and after
            - Every table on its own lines with blank lines before and after
            - End with a complete sentence ending in a period
            - NEVER mention "documents", "context", "retrieval", or internal processes
            - NEVER output the Level number, Level name, TRIGGERS list, or any internal classification label — these are for your internal use ONLY
            - Start your response directly with the first ## header — zero preamble, zero meta-commentary about the question type
            - Speak as a senior financial analyst -- present all figures as established facts

            ============================================================
            CHART DATA — MANDATORY WHEN MULTI-YEAR FIGURES ARE PRESENT
            ============================================================

            Whenever your response contains financial data across 2 or more years (historical OR forecast),
            you MUST append EXACTLY this line as the very last line of your response, after all markdown content:

            CHARTDATA_JSON:[{"year":"2020","value":10000000,"type":"historical"},{"year":"2024F","value":22000000,"type":"forecast"}]

            Rules:
            - "value" = revenue (preferred) or primary metric in raw BASE units — no commas, no units
              Examples: $10 million → 10000000 | Rs 500 Cr → 5000000000 | $1.2B → 1200000000
            - "type" = "historical" for actual past data, "forecast" for projections
            - "year" = 4-digit year for historical ("2020"), year+"F" for forecast ("2024F")
            - Include ALL years mentioned in your response tables/analysis
            - This line is consumed by the chart renderer and NEVER shown to the user
            - If your response has NO multi-year financial figures (e.g. pure qualitative answer), OMIT the CHARTDATA_JSON line entirely
            """;


    public String analyzeFinancials(String query) {
        try {
            List<Document> relevantDocs = searchDocuments(query);
            String context = relevantDocs.isEmpty() ? "" : buildFinancialContext(relevantDocs);
            log.info("[FINSIGHT] RAG: {} | Tavily: No",
                    relevantDocs.isEmpty() ? "No" : "Yes (" + relevantDocs.size() + " docs)");

            String userPrompt = buildDetailedPrompt(query, context);
            String rawAnalysis = callModelWithSystemPrompt(userPrompt);
            return wrapAnalysisInJSON(rawAnalysis);

        } catch (Exception e) {
            log.error("Error in financial analysis: {}", e.getMessage(), e);
            return wrapPlainMessageAsJson(MSG_ERROR);
        }
    }

    /**
     * Cached vector similarity search. Identical queries within the TTL window reuse
     * the previous result, skipping the embedding call + Chroma round trip.
     */
    private List<Document> searchDocuments(String query) {
        String key = query == null ? "" : query.trim().toLowerCase();
        long now = System.currentTimeMillis();

        CachedSearch cached = searchCache.get(key);
        if (cached != null && (now - cached.timestamp()) < SEARCH_CACHE_TTL_MS) {
            return cached.docs();
        }

        List<Document> docs = vectorStore.similaritySearch(
                org.springframework.ai.vectorstore.SearchRequest.builder()
                        .query(query)
                        .topK(20)
                        .build()
        );
        if (docs == null) {
            docs = Collections.emptyList();
        }
        if (searchCache.size() >= SEARCH_CACHE_MAX) {
            searchCache.clear();
        }
        searchCache.put(key, new CachedSearch(docs, now));
        return docs;
    }

    /** Wrap a plain user-facing message as the standard JSON response (no internals). */
    private String wrapPlainMessageAsJson(String message) {
        try {
            Map<String, Object> resp = new HashMap<>();
            resp.put("answer", message);
            resp.put("chartType", "none");
            resp.put("chartData", new ArrayList<>());
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(resp);
        } catch (Exception ex) {
            return "{\"answer\": \"" + message + "\", \"chartType\": \"none\", \"chartData\": []}";
        }
    }

    /**
     * Streaming variant of analyzeFinancials. Emits the analysis text token-by-token
     * as SSE "token" events for a live typing effect, then a final "chart" event with
     * the extracted chart data JSON, and a "done" event. Inner unescaped quotes etc.
     * are irrelevant here because tokens are streamed as raw text (not JSON).
     */
    public Flux<ServerSentEvent<String>> streamAnalysis(String query) {
        List<Document> relevantDocs;
        try {
            relevantDocs = searchDocuments(query);
        } catch (Exception e) {
            log.error("Vector search failed: {}", e.getMessage());
            relevantDocs = Collections.emptyList();
        }

        String context = (relevantDocs == null || relevantDocs.isEmpty())
                ? ""
                : buildFinancialContext(relevantDocs);

        log.info("[FINSIGHT-STREAM] RAG: {} | Tavily: No",
                (relevantDocs == null || relevantDocs.isEmpty())
                        ? "No" : "Yes (" + relevantDocs.size() + " docs)");
        String userPrompt = buildDetailedPrompt(query, context);

        SystemMessage systemMessage = new SystemMessage(FINANCIAL_SYSTEM_PROMPT);
        UserMessage userMsg = new UserMessage("USER REQUEST:\n" + userPrompt);
        ChatOptions options = ChatOptions.builder()
                .maxTokens(maxTokens)
                .temperature(temperature)
                .topP(topP)
                .build();
        Prompt prompt = new Prompt(java.util.Arrays.asList(systemMessage, userMsg), options);

        StringBuilder accumulated = new StringBuilder();

        // Buffer the last N chars so we can detect "CHARTDATA_JSON:" even when it
        // arrives split across multiple tokens.  Once detected, suppress the rest.
        final String CHART_MARKER = "CHARTDATA_JSON:";
        java.util.concurrent.atomic.AtomicBoolean suppress =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<String> pending =
                new java.util.concurrent.atomic.AtomicReference<>("");

        Flux<String> cleanTokens = chatModel.stream(prompt)
                .map(this::extractText)
                .filter(t -> !t.isEmpty())
                .doOnNext(accumulated::append)
                .map(token -> {
                    if (suppress.get()) return "";
                    String s = pending.getAndSet("") + token;
                    int idx = s.indexOf(CHART_MARKER);
                    if (idx >= 0) {
                        suppress.set(true);
                        // emit only what came before the marker (trim trailing newline)
                        return idx > 0 ? s.substring(0, idx).stripTrailing() : "";
                    }
                    // Keep the last (marker-length) chars buffered to catch splits
                    if (s.length() > CHART_MARKER.length()) {
                        String emit = s.substring(0, s.length() - CHART_MARKER.length());
                        pending.set(s.substring(s.length() - CHART_MARKER.length()));
                        return emit;
                    }
                    pending.set(s);
                    return "";
                })
                .filter(t -> !t.isEmpty())
                // Flush the trailing buffer once the stream ends (if no marker was found)
                .concatWith(Flux.defer(() -> {
                    String rem = pending.getAndSet("");
                    return (rem.isEmpty() || suppress.get())
                            ? Flux.empty()
                            : Flux.just(rem);
                }));

        Flux<ServerSentEvent<String>> tokens = cleanTokens
                .map(t -> ServerSentEvent.<String>builder(t).event("token").build());

        // After the text finishes streaming, emit the chart data (extracted from the
        // full accumulated text) followed by a completion marker.
        Flux<ServerSentEvent<String>> tail = Flux.defer(() -> {
            String rawText = accumulated.toString();
            // Strip CHARTDATA_JSON line before logging and storing as the user-visible answer
            String text = rawText.replaceAll("(?m)^CHARTDATA_JSON:.*$", "").stripTrailing();
            String chartJson = "[]";
            try {
                chartJson = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(extractChartDataFromText(rawText));
            } catch (Exception e) {
                log.warn("Could not build chart JSON: {}", e.getMessage());
            }
            if (!text.isBlank()) {
                log.info("[FINSIGHT-STREAM] Response: {}", text);
            }
            return Flux.just(
                    ServerSentEvent.<String>builder(chartJson).event("chart").build(),
                    ServerSentEvent.<String>builder("[DONE]").event("done").build()
            );
        });

        return Flux.concat(tokens, tail)
                .onErrorResume(e -> {
                    log.error("Financial stream error: {}", e.getMessage());
                    return Flux.just(
                            ServerSentEvent.<String>builder(MSG_ERROR).event("error").build(),
                            ServerSentEvent.<String>builder("[DONE]").event("done").build()
                    );
                });
    }

    /** Null-safe extraction of the text from a streaming ChatResponse chunk. */
    private String extractText(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return "";
        }
        String text = chunk.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private String callModelWithSystemPrompt(String userPrompt) {
        try {
            // Create messages for proper Spring AI ChatMessage API usage
            SystemMessage systemMessage = new SystemMessage(FINANCIAL_SYSTEM_PROMPT);
            String userContent = "USER REQUEST:\n" + userPrompt;
            UserMessage userMsg = new UserMessage(userContent);

            // Explicitly pass options so max-tokens is GUARANTEED to be applied
            ChatOptions options = ChatOptions.builder()
                    .maxTokens(maxTokens)
                    .temperature(temperature)
                    .topP(topP)
                    .build();

            Prompt prompt = new Prompt(java.util.Arrays.asList(systemMessage, userMsg), options);
            ChatResponse chatResponse = chatModel.call(prompt);
            String response = chatResponse.getResult().getOutput().getText();

            var usage = chatResponse.getMetadata().getUsage();
            double pct = ((double) usage.getCompletionTokens() / maxTokens) * 100;
            log.info("[FINSIGHT] Tokens: {}/{} ({}%)",
                    usage.getCompletionTokens(), maxTokens, String.format("%.1f", pct));
            return response;
        } catch (Exception e) {
            log.error("Error calling model: {}", e.getMessage());
            throw new RuntimeException("Failed to get model response: " + e.getMessage(), e);
        }
    }

    public String forecastFinancials(String company, String metric, int yearsAhead) {

        String query = String.format(
                "Provide comprehensive 5-year financial forecast for %s: " +
                "1) Current %s with details " +
                "2) Historical trends " +
                "3) Year-by-year %s forecast (2025-2029) " +
                "4) Three scenarios (Conservative/Base/Optimistic) " +
                "5) Risk assessment",
                company, metric, metric
        );

        return analyzeFinancials(query);
    }

    private String buildDetailedPrompt(String query, String context) {
        StringBuilder sb = new StringBuilder();
        if (context != null && !context.isBlank()) {
            sb.append("RETRIEVED FINANCIAL DATA (use ALL of it -- every year, every metric):\n\n");
            sb.append(context);
            sb.append("\n\n");
        } else {
            sb.append("NOTE: No specific financial documents were retrieved. ");
            sb.append("Answer using your financial knowledge and clearly state any assumptions you make.\n\n");
        }
        sb.append("USER REQUEST: ").append(query).append("\n\n");
        sb.append("Internally determine the question type (Level 1-8) and apply the matching response structure — ");
        sb.append("do NOT mention the level or triggers in your output. ");
        sb.append("Be comprehensive. Use all available data. ");
        sb.append("Proper Markdown: blank lines before/after every ## header and every table.");

        // Detect future-year or future-growth queries and add explicit forecasting instruction
        String ql = query.toLowerCase();
        boolean hasFutureYear = query.matches(".*\\b(202[6-9]|203[0-9])\\b.*");
        boolean hasFutureIntent = ql.contains("future") || ql.contains("forecast") ||
                ql.contains("projected") || ql.contains("projection") ||
                ql.contains("next year") || ql.contains("coming year") ||
                ql.contains("predict") || ql.contains("by 20");
        if (hasFutureYear || hasFutureIntent) {
            sb.append("\n\nFORECASTING REQUIREMENT: This question asks about future performance. ");
            sb.append("You MUST: ");
            sb.append("(1) compute the historical CAGR from the data — show formula and result; ");
            sb.append("(2) project year-by-year from the latest known year to the target year; ");
            sb.append("(3) provide Bear / Base / Bull scenarios; ");
            sb.append("(4) NEVER say you cannot forecast — derive projections from historical trend.");
        }
        return sb.toString();
    }

    private String buildFinancialContext(List<Document> documents) {
        if (documents.isEmpty()) {
            return "";
        }

        // Deduplicate chunks by content prefix so we don't repeat the same block
        // multiple times (different embedding queries can retrieve overlapping chunks).
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        StringBuilder context = new StringBuilder();

        int included = 0;
        for (Document doc : documents) {
            String text = doc.getText();
            if (text == null || text.isBlank()) continue;
            String key = text.substring(0, Math.min(80, text.length())).trim();
            if (!seen.add(key)) continue; // skip duplicate chunk

            context.append(text.trim()).append("\n\n");
            included++;
        }

        return context.toString();
    }

    private String wrapAnalysisInJSON(String rawAnalysis) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

            String analysisText = cleanAnalysisText(rawAnalysis);

            List<Map<String, Object>> chartDataList = extractChartDataFromText(analysisText);

            Map<String, Object> response = new HashMap<>();
            response.put("answer", analysisText);
            response.put("chartType", chartDataList.isEmpty() ? "none" : "line");
            response.put("chartData", chartDataList);

            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("Error wrapping analysis: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("answer", rawAnalysis);
            error.put("chartType", "none");
            error.put("chartData", new ArrayList<>());
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(error);
            } catch (Exception ex) {
                return "{\"answer\": \"Error wrapping response\", \"chartType\": \"none\", \"chartData\": []}";
            }
        }
    }

    private String cleanAnalysisText(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return rawText;
        }

        String text = rawText.trim();

        // If text starts with { it's probably JSON - try to extract answer field
        if (text.startsWith("{") && text.contains("\"answer\"")) {
            try {
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"answer\"\\s*:\\s*\"(.+?)\"\\s*,\\s*\"chartType\"", java.util.regex.Pattern.DOTALL);
                java.util.regex.Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    return matcher.group(1).replace("\\\"", "\"").trim();
                }
            } catch (Exception e) {
                log.debug("Could not extract from JSON text");
            }
        }

        // Otherwise, plain text response from model - clean it up and strip chart marker
        text = text.replaceAll("(?m)^CHARTDATA_JSON:.*$", "").stripTrailing();
        return text;
    }

    /**
     * Extracts year-over-year chart data points from analysis text.
     *
     * Strategy 0 (most reliable): parse the model-supplied CHARTDATA_JSON:[...] line.
     * Strategy 1: parse markdown table rows.
     * Strategy 2: bidirectional prose scan.
     */
    public List<Map<String, Object>> extractChartDataFromText(String analysis) {
        List<Map<String, Object>> data = new ArrayList<>();
        if (analysis == null || analysis.isBlank()) return data;

        // --- Strategy 0: model-provided CHARTDATA_JSON block ---
        java.util.regex.Matcher cdMatcher = java.util.regex.Pattern
                .compile("^CHARTDATA_JSON:(\\[.*?\\])\\s*$", java.util.regex.Pattern.MULTILINE)
                .matcher(analysis);
        if (cdMatcher.find()) {
            try {
                com.fasterxml.jackson.databind.JsonNode arr =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(cdMatcher.group(1));
                for (com.fasterxml.jackson.databind.JsonNode item : arr) {
                    String year  = item.has("year")  ? item.get("year").asText()  : null;
                    long   value = item.has("value") ? item.get("value").asLong() : 0;
                    String type  = item.has("type")  ? item.get("type").asText()  : "historical";
                    if (year != null && value > 0) {
                        Map<String, Object> pt = new HashMap<>();
                        pt.put("year",  year);
                        pt.put("value", value);
                        pt.put("type",  type);
                        data.add(pt);
                    }
                }
                if (!data.isEmpty()) {
                    log.info("Chart extraction: {} points from CHARTDATA_JSON block", data.size());
                    return data;
                }
            } catch (Exception e) {
                log.warn("CHARTDATA_JSON parse failed, falling back to regex: {}", e.getMessage());
            }
        }

        java.util.Set<String> seenYears = new java.util.LinkedHashSet<>();

        // Currency pattern: optional symbol prefix, number, mandatory magnitude unit
        java.util.regex.Pattern currencyPat = java.util.regex.Pattern.compile(
            "(?:Rs\\.?\\s*|INR\\s*|[₹$€£]\\s*)?([\\d,]+(?:\\.\\d+)?)\\s*(crores?|billions?|millions?|trillions?|lakh\\s*crores?|Cr\\b|B\\b|M\\b|bn\\b|mn\\b|T\\b)",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );

        // Strategy 1: markdown table rows  | FY2020 | $10 million | ...
        java.util.regex.Pattern tableRowPat = java.util.regex.Pattern.compile(
            "^\\|\\s*(?:FY)?(\\d{4})(F?)\\s*\\|([^|\\n]+)",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.MULTILINE
        );
        java.util.regex.Matcher tMatcher = tableRowPat.matcher(analysis);
        while (tMatcher.find()) {
            String year = tMatcher.group(1);
            boolean isForecast = !tMatcher.group(2).isEmpty();
            String firstCell = tMatcher.group(3);
            if (seenYears.contains(year)) continue;
            java.util.regex.Matcher vMatcher = currencyPat.matcher(firstCell);
            if (vMatcher.find()) {
                try {
                    double value = Double.parseDouble(vMatcher.group(1).replace(",", ""));
                    value = applyMagnitude(value, vMatcher.group(2));
                    addChartPoint(data, seenYears, year, isForecast, (long) value);
                } catch (NumberFormatException ignore) {}
            }
        }

        // Strategy 2: bidirectional prose scan for remaining years
        String[] candidates = {"2017","2018","2019","2020","2021","2022","2023","2024",
                               "2025","2026","2027","2028","2029","2030","2031"};
        for (String year : candidates) {
            if (seenYears.contains(year)) continue;
            boolean isForecast = analysis.contains(year + "F");
            String token = isForecast ? year + "F" : year;
            int idx = analysis.indexOf(token);
            if (idx < 0) idx = analysis.indexOf("FY" + year);
            if (idx < 0) continue;

            // Look both before (for "from $X in FY2020") and after the year token
            int wStart = Math.max(0, idx - 100);
            int wEnd   = Math.min(analysis.length(), idx + 120);
            String window = analysis.substring(wStart, wEnd);

            java.util.regex.Matcher vMatcher = currencyPat.matcher(window);
            if (vMatcher.find()) {
                try {
                    double value = Double.parseDouble(vMatcher.group(1).replace(",", ""));
                    value = applyMagnitude(value, vMatcher.group(2));
                    addChartPoint(data, seenYears, year, isForecast, (long) value);
                } catch (NumberFormatException ignore) {}
            }
        }

        long forecastCount = data.stream().filter(d -> "forecast".equals(d.get("type"))).count();
        log.info("Chart extraction: {} points ({} historical, {} forecast)",
                data.size(), data.size() - forecastCount, forecastCount);
        return data;
    }

    private void addChartPoint(List<Map<String, Object>> data, java.util.Set<String> seen,
                               String year, boolean isForecast, long value) {
        Map<String, Object> item = new HashMap<>();
        String label = isForecast ? year + "F" : year;
        item.put("year",  label);
        item.put("value", value);
        item.put("type",  isForecast ? "forecast" : "historical");
        data.add(item);
        seen.add(year);
        log.debug("Chart point: {} = {}", label, value);
    }

    private double applyMagnitude(double value, String magnitude) {
        if (magnitude == null) return value;
        String m = magnitude.toLowerCase().trim();
        if (m.contains("lakh")) return value * 100_000_000_000.0; // lakh crore
        if (m.startsWith("cr")) return value * 10_000_000.0;      // crores
        if (m.equals("b") || m.equals("bn") || m.startsWith("bil")) return value * 1_000_000_000.0;
        if (m.equals("m") || m.equals("mn") || m.startsWith("mil")) return value * 1_000_000.0;
        if (m.equals("t") || m.startsWith("tr")) return value * 1_000_000_000_000.0;
        return value;
    }

    @lombok.Data
    @lombok.Builder
    public static class FinancialMetrics {
        private double revenueCAGR;
        private double grossProfitCAGR;
        private double gpMargin1;
        private double gpMargin2;
        private double gpMargin3;
    }

    public FinancialMetrics calculateMetrics(double revenue1, double revenue2, double revenue3,
                                            double grossProfit1, double grossProfit2, double grossProfit3,
                                            double netProfit1, double netProfit2, double netProfit3) {
        double revenueCAGR = (Math.pow(revenue3 / revenue1, 1.0 / 2) - 1) * 100;
        double gpCAGR = (Math.pow(grossProfit3 / grossProfit1, 1.0 / 2) - 1) * 100;
        double gpMargin1 = (grossProfit1 / revenue1) * 100;
        double gpMargin2 = (grossProfit2 / revenue2) * 100;
        double gpMargin3 = (grossProfit3 / revenue3) * 100;

        return FinancialMetrics.builder()
                .revenueCAGR(revenueCAGR)
                .grossProfitCAGR(gpCAGR)
                .gpMargin1(gpMargin1)
                .gpMargin2(gpMargin2)
                .gpMargin3(gpMargin3)
                .build();
    }

    @lombok.Data
    @lombok.Builder
    public static class DetailedForecast {
        private double currentRevenue;
        private double currentGrossProfit;
        private double currentGPMargin;
        private double historicalCAGR;
        private List<YearForecast> yearForecasts;
        private String confidence;
    }

    @lombok.Data
    @lombok.Builder
    public static class YearForecast {
        private int year;
        private double baseRevenue;
        private double baseGrossProfit;
        private double optimisticRevenue;
        private double optimisticGrossProfit;
        private double conservativeRevenue;
        private double conservativeGrossProfit;
        private double gpMargin;
        private double growthRate;
    }

    public DetailedForecast generateDetailedForecast(double currentRevenue, double currentGrossProfit,
                                                     double historicalCAGR, double gpMargin) {
        log.info("Generating 5-year forecast with gross profit analysis");
        List<YearForecast> forecastYears = new ArrayList<>();
        for (int year = 1; year <= 5; year++) {
            double baseRevenue = currentRevenue * Math.pow(1 + (historicalCAGR / 100), year);
            double optimisticRevenue = currentRevenue * Math.pow(1 + ((historicalCAGR + 3) / 100), year);
            double conservativeRevenue = currentRevenue * Math.pow(1 + ((historicalCAGR - 3) / 100), year);
            double baseGP = baseRevenue * (gpMargin / 100);
            double optimisticGP = optimisticRevenue * (gpMargin / 100);
            double conservativeGP = conservativeRevenue * (gpMargin / 100);
            forecastYears.add(YearForecast.builder()
                    .year(2024 + year)
                    .baseRevenue(baseRevenue)
                    .baseGrossProfit(baseGP)
                    .optimisticRevenue(optimisticRevenue)
                    .optimisticGrossProfit(optimisticGP)
                    .conservativeRevenue(conservativeRevenue)
                    .conservativeGrossProfit(conservativeGP)
                    .gpMargin(gpMargin)
                    .growthRate(historicalCAGR)
                    .build());
        }
        return DetailedForecast.builder()
                .currentRevenue(currentRevenue)
                .currentGrossProfit(currentGrossProfit)
                .currentGPMargin(gpMargin)
                .historicalCAGR(historicalCAGR)
                .yearForecasts(forecastYears)
                .confidence("HIGH")
                .build();
    }
}
