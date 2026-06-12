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
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class FinancialAssistantService {

    private final ChatModel chatModel;
    private final VectorStore vectorStore;

    @Value("${spring.ai.bedrock.converse.chat.options.max-tokens:10000}")
    private Integer maxTokens;

    @Value("${spring.ai.bedrock.converse.chat.options.temperature:0.15}")
    private Double temperature;

    @Value("${spring.ai.bedrock.converse.chat.options.top-p:0.85}")
    private Double topP;

    private static final String FINANCIAL_SYSTEM_PROMPT = """
            YOU ARE FINSIGHT AI - ENTERPRISE FINANCIAL INTELLIGENCE AND FORECASTING ASSISTANT.

            CORE OBJECTIVE:
            1. Analyze current company performance
            2. Analyze historical growth trends
            3. Diagnose financial health
            4. Forecast the next 5 years of growth
            5. Predict future revenue and profit
            6. Explain growth drivers and risks
            7. Provide an executive investment outlook

            FORECASTING IS MANDATORY when 3+ historical periods exist.

            FINANCIAL DIAGNOSTIC FRAMEWORK - Evaluate:
            - Revenue Growth (Strong/Moderate/Weak)
            - Profitability (Excellent/Healthy/Concerning)
            - Operating Margin
            - Cost Efficiency
            - Business Scalability
            - Market Position
            - Growth Consistency
            - Financial Stability

            Overall Outlook Classification: Very Positive / Positive / Neutral / Negative

            FIVE-YEAR FORECAST REQUIREMENTS (mandatory when 3+ years of data exist):
            - Analyze historical CAGR
            - Analyze revenue growth trends
            - Analyze profit growth trends
            - Analyze operating efficiency
            - Generate projected revenue for 2025F-2029F
            - Generate projected profit for 2025F-2029F
            - Forecasts must be realistic and evidence-based
            - Clearly label as projected values
            - IMPORTANT: If at least 3 years of REVENUE history exist, you MUST still
              produce a full revenue forecast using the historical revenue CAGR - do
              NOT skip the forecast just because profit data is missing.
            - If profit figures are unavailable, ESTIMATE projected profit by applying a
              reasonable industry net margin (state the assumed margin %, e.g. "assuming
              ~14% net margin"). Never output placeholders like "Data gap" or "N/A" when
              revenue history is available - always compute concrete projected numbers.

            RESPONSE FORMAT (STRICT - follow exactly):

            CURRENT COMPANY HEALTH
            Revenue: [Latest figure with year]
            Profit: [Latest figure with year]
            Growth Rate: [CAGR %]
            Financial Status: [Classification]

            HISTORICAL PERFORMANCE
            [List 5-7 most recent years: Year Revenue: Amount, Profit: Amount]

            KEY INSIGHTS
            * [Brief insight 1]
            * [Brief insight 2]
            * [Brief insight 3]

            ==================================================
            >>> FIVE-YEAR FORECAST (PROJECTED) -- KEY HIGHLIGHT
            ==================================================
            Projected Growth Rate (CAGR): [X% per year]
            Forecast Basis: [e.g. derived from N-year historical CAGR and margin trend]

            2025F  Revenue: [Amount]  |  Profit: [Amount]  |  YoY Growth: [+X%]
            2026F  Revenue: [Amount]  |  Profit: [Amount]  |  YoY Growth: [+X%]
            2027F  Revenue: [Amount]  |  Profit: [Amount]  |  YoY Growth: [+X%]
            2028F  Revenue: [Amount]  |  Profit: [Amount]  |  YoY Growth: [+X%]
            2029F  Revenue: [Amount]  |  Profit: [Amount]  |  YoY Growth: [+X%]

            FORECAST ANALYSIS:
            [2-3 sentences highlighting the projected PROFIT trajectory, expected GROWTH
            momentum, and the key drivers/assumptions behind the projection. State clearly
            whether profit and growth are expected to accelerate, stay steady, or slow down.]
            ==================================================

            RISKS
            * [Brief risk 1]
            * [Brief risk 2]

            EXECUTIVE OUTLOOK
            [1-2 sentences: Classification + brief reason, referencing the forecast]

            CRITICAL REQUIREMENTS - ABSOLUTELY MANDATORY:
            ✓ COMPLETE ALL SECTIONS - No truncation, no skipping
            ✓ Always show historical revenue and profit
            ✓ Always show all historical years available (minimum 5 years)
            ✓ The FIVE-YEAR FORECAST is the most important add-on section - make it
              prominent with the ==== highlight banners exactly as shown above
            ✓ Forecast MUST include projected revenue, projected profit AND YoY growth %
              for every year 2025F-2029F
            ✓ Forecast MUST include the FORECAST ANALYSIS commentary on future profit & growth
            ✓ Always include RISKS section
            ✓ Always provide EXECUTIVE OUTLOOK conclusion
            ✓ Mark forecasts as projections (2025F format with the F suffix)
            ✓ END RESPONSE WITH PERIOD - Signal completion
            """;

    public String analyzeFinancials(String query) {
        log.info("Comprehensive Financial Analysis Request: {}", query);

        try {
            List<Document> relevantDocs = vectorStore.similaritySearch(query);
            log.info("Document search returned {} documents", relevantDocs.size());

            if (relevantDocs.isEmpty()) {
                log.warn("No financial documents found for query: {}", query);
                return "{\"answer\": \"No financial data available. Please upload financial documents first.\", \"chartType\": \"none\", \"chartData\": []}";
            }

            for (int i = 0; i < relevantDocs.size(); i++) {
                log.info("Document {}: {} chars", i + 1, relevantDocs.get(i).getText().length());
            }

            String context = buildFinancialContext(relevantDocs);
            log.info("Context built: {} characters", context.length());
            String userPrompt = buildDetailedPrompt(query, context);

            String rawAnalysis = callModelWithSystemPrompt(userPrompt);
            log.info("Raw analysis received: {} characters", rawAnalysis.length());

            String formattedResponse = wrapAnalysisInJSON(rawAnalysis);
            log.info("Formatted response: {} characters", formattedResponse.length());

            return formattedResponse;

        } catch (Exception e) {
            log.error("Error in financial analysis: {}", e.getMessage(), e);
            return "{\"answer\": \"Error: " + e.getMessage() + "\", \"chartType\": \"none\", \"chartData\": []}";
        }
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

            log.info("FinancialAssistant calling model with Max Tokens: {}, Temperature: {}", maxTokens, temperature);

            // Create prompt with message structure AND explicit options
            Prompt prompt = new Prompt(java.util.Arrays.asList(systemMessage, userMsg), options);

            ChatResponse chatResponse = chatModel.call(prompt);
            String response = chatResponse.getResult().getOutput().getText();

            // Log token usage to verify full utilization
            var usage = chatResponse.getMetadata().getUsage();
            log.info("FinancialAssistant Token Usage - Prompt: {}, Completion: {}/{}, Total: {}",
                    usage.getPromptTokens(),
                    usage.getCompletionTokens(),
                    maxTokens,
                    usage.getTotalTokens());

            log.info("Model response received, length: {} characters", response.length());
            return response;
        } catch (Exception e) {
            log.error("Error calling model: {}", e.getMessage());
            throw new RuntimeException("Failed to get model response: " + e.getMessage(), e);
        }
    }

    public String forecastFinancials(String company, String metric, int yearsAhead) {
        log.info("Detailed Forecast: Company={}, Metric={}, Years={}", company, metric, yearsAhead);

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
        return "FINANCIAL DATA:\n" + context + "\n\n" +
                "ANALYSIS REQUEST: " + query + "\n\n" +
                "Please provide comprehensive FinSight AI financial analysis following the required format:\n" +
                "- Current company health snapshot\n" +
                "- Complete historical performance (all years available)\n" +
                "- Key insights\n" +
                "- Five-year forecast (2025F-2029F) if 3+ historical periods exist\n" +
                "- Risk assessment\n" +
                "- Executive investment outlook";
    }

    private String buildFinancialContext(List<Document> documents) {
        StringBuilder context = new StringBuilder();
        context.append("FINANCIAL DATA:\n\n");

        if (documents.isEmpty()) {
            return "";
        }

        // With Opus 4.8 and 20k tokens, include more documents and content
        // Opus can handle larger context windows efficiently
        int docCount = Math.min(5, documents.size());
        for (int i = 0; i < docCount; i++) {
            Document doc = documents.get(i);
            String text = doc.getText();
            // Include more content per document - up to 1200 chars
            String truncated = text.length() > 1200 ? text.substring(0, 1200) + "[...]" : text;
            context.append("Document ").append(i + 1).append(":\n");
            context.append(truncated).append("\n\n");
        }

        log.info("Financial context prepared: {} characters from {} documents", context.length(), docCount);
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

            String result = mapper.writeValueAsString(response);
            log.info("Wrapped JSON response: {} characters", result.length());
            return result;
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

        // Otherwise, plain text response from model - just clean it up
        return text;
    }

    /**
     * Extracts an ordered list of chart points (revenue) from the analysis text.
     * Each point is tagged as "historical" or "forecast" so the frontend can
     * highlight the projected (add-on) portion of the trend distinctly.
     * Forecast years are detected by the "F" suffix (e.g. "2025F") and labelled
     * with that suffix.
     */
    private List<Map<String, Object>> extractChartDataFromText(String analysis) {
        List<Map<String, Object>> data = new ArrayList<>();

        String[] years = {"2017", "2018", "2019", "2020", "2021", "2022", "2023", "2024",
                "2025", "2026", "2027", "2028", "2029", "2030"};

        for (String year : years) {
            // A year is a FORECAST if it appears with the F suffix (e.g. "2025F")
            boolean isForecast = analysis.contains(year + "F");
            String searchToken = isForecast ? year + "F" : year;

            int index = analysis.indexOf(searchToken);
            if (index < 0) {
                continue;
            }

            // Look at the text right after the year token so we capture that
            // year's revenue figure (the first currency amount following it).
            int start = index;
            int end = Math.min(analysis.length(), index + 120);
            String context = analysis.substring(start, end);

            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "[₹$€£]\\s*([\\d,]+(?:\\.\\d+)?)\\s*(crores?|billions?|millions?|trillions?|B|M|bn|mn|cr|T)\\b"
            );
            java.util.regex.Matcher matcher = pattern.matcher(context);

            if (matcher.find()) {
                String valueStr = matcher.group(1).replace(",", "").trim();
                String magnitude = matcher.group(2).toLowerCase();

                try {
                    double value = Double.parseDouble(valueStr);

                    if (magnitude.startsWith("cr")) {
                        value *= 10_000_000;
                    } else if (magnitude.startsWith("b")) {
                        value *= 1_000_000_000;
                    } else if (magnitude.startsWith("m") && magnitude.length() <= 3) {
                        value *= 1_000_000;
                    } else if (magnitude.startsWith("t")) {
                        value *= 1_000_000_000_000L;
                    }

                    Map<String, Object> item = new HashMap<>();
                    item.put("year", isForecast ? year + "F" : year);
                    item.put("value", (long) value);
                    item.put("type", isForecast ? "forecast" : "historical");
                    data.add(item);
                    log.debug("Extracted {} value for {}: {}", isForecast ? "forecast" : "historical", year, (long) value);
                } catch (NumberFormatException e) {
                    log.debug("Could not parse value for year {}", year);
                }
            }
        }

        long forecastCount = data.stream().filter(d -> "forecast".equals(d.get("type"))).count();
        log.info("Extracted {} chart data points ({} historical, {} forecast)",
                data.size(), data.size() - forecastCount, forecastCount);
        return data;
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
