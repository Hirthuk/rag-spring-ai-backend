package com.finsight.finsight_ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class FinancialAssistantService {

    private final ChatModel chatModel;
    private final VectorStore vectorStore;

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

            FIVE-YEAR FORECAST REQUIREMENTS (mandatory when data available):
            - Analyze historical CAGR
            - Analyze revenue growth trends
            - Analyze profit growth trends
            - Analyze operating efficiency
            - Generate projected revenue for 2025F-2029F
            - Generate projected profit for 2025F-2029F
            - Forecasts must be realistic and evidence-based
            - Clearly label as projected values

            RESPONSE FORMAT (REQUIRED):

            CURRENT COMPANY HEALTH
            Revenue:
            Profit:
            Growth Rate:
            Financial Status:

            HISTORICAL PERFORMANCE
            2020 Revenue:
            2020 Profit:
            2021 Revenue:
            2021 Profit:
            [Continue for all available years]

            KEY INSIGHTS
            * Insight 1
            * Insight 2
            * Insight 3
            [Add more as needed]

            FIVE-YEAR FORECAST
            2025F
            Projected Revenue:
            Projected Profit:
            2026F
            Projected Revenue:
            Projected Profit:
            [Continue through 2029F]

            RISKS
            * Risk 1
            * Risk 2
            * Risk 3
            [Add more as needed]

            EXECUTIVE OUTLOOK
            [Concise investment-style conclusion on growth outlook]

            CRITICAL REQUIREMENTS:
            ✓ Always show historical revenue and profit
            ✓ Always show all historical years available
            ✓ Always include forecast revenue and profit
            ✓ Always show forecast years (2025F-2029F)
            ✓ Always explain growth drivers
            ✓ Always provide executive conclusion
            ✓ Never answer with only historical data
            ✓ Always include five-year forecast when 3+ data points exist
            ✓ Mark forecasts as projections (2025F format)
            """;

    public String analyzeFinancials(String query) {
        log.info("Comprehensive Financial Analysis Request: {}", query);

        try {
            List<Document> relevantDocs = vectorStore.similaritySearch(query);

            if (relevantDocs.isEmpty()) {
                log.warn("No financial documents found for query: {}", query);
                return "{\"answer\": \"No financial data available. Please upload financial documents first.\", \"chartType\": \"none\", \"chartData\": []}";
            }

            String context = buildFinancialContext(relevantDocs);
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

            // Create prompt with proper message structure
            Prompt prompt = new Prompt(java.util.Arrays.asList(systemMessage, userMsg));

            // Call model with proper prompt structure (will use configured options from application.properties)
            ChatResponse chatResponse = chatModel.call(prompt);
            String response = chatResponse.getResult().getOutput().getText();

            log.info("Model response received, length: {} characters", response.length());
            if (response.length() < 2000) {
                log.warn("WARNING: Response is very short ({} chars). Expected 3000+. Full response: {}", response.length(), response);
            }
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
        context.append("FINANCIAL DATA:\n");

        if (documents.isEmpty()) {
            context.append("No financial documents found.");
            return context.toString();
        }

        // Include up to 4 documents with more content for financial analysis
        int docCount = Math.min(4, documents.size());
        for (int i = 0; i < docCount; i++) {
            Document doc = documents.get(i);
            String text = doc.getText();
            // Include more content - up to 800 chars per document
            String truncated = text.length() > 800 ? text.substring(0, 800) + "..." : text;
            context.append("\nDocument ").append(i + 1).append(":\n").append(truncated).append("\n");
        }

        return context.toString();
    }

    private String wrapAnalysisInJSON(String rawAnalysis) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

            String analysisText = cleanAnalysisText(rawAnalysis);

            Map<String, Object> chartData = extractChartDataFromText(analysisText);
            List<Map<String, Object>> chartDataList = new ArrayList<>();
            if (!chartData.isEmpty()) {
                chartData.forEach((k, v) -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("year", k);
                    item.put("value", v);
                    chartDataList.add(item);
                });
            }

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

    private Map<String, Object> extractChartDataFromText(String analysis) {
        Map<String, Object> data = new HashMap<>();

        String[] years = {"2020", "2021", "2022", "2023", "2024", "2025", "2026", "2027", "2028", "2029", "2030"};

        for (String year : years) {
            if (analysis.contains(year)) {
                int index = analysis.indexOf(year);
                int start = Math.max(0, index - 200);
                int end = Math.min(analysis.length(), index + 200);
                String context = analysis.substring(start, end);

                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "[₹$€£]\\s*([\\d,]+(?:\\.\\d+)?)\\s*(crore|billion|million|trillion|B|M|bn|mn|cr|T)\\b"
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

                        data.put(year, (long) value);
                        log.debug("Extracted value for year {}: {}", year, (long) value);
                    } catch (NumberFormatException e) {
                        log.debug("Could not parse value for year {}", year);
                    }
                }
            }
        }

        log.info("Extracted {} chart data points from analysis", data.size());
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
