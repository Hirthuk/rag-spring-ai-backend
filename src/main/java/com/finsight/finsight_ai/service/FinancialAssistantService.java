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
            YOU ARE A FINANCIAL ANALYST WRITING A 10-SECTION ANALYSIS.

            Write sections in this format:
            "1. COMPANY OVERVIEW
            [Content here...]

            2. CURRENT FINANCIAL HEALTH
            [Content here...]"

            And continue for all 10 sections:
            1. COMPANY OVERVIEW
            2. CURRENT FINANCIAL HEALTH
            3. REVENUE ANALYSIS
            4. PROFITABILITY ANALYSIS
            5. GROWTH TREND ANALYSIS
            6. KEY STRENGTHS
            7. KEY RISKS
            8. FIVE-YEAR FORECAST
            9. INVESTMENT OUTLOOK
            10. EXECUTIVE SUMMARY

            Each section: 150-250 words minimum. Include specific numbers, data, and analysis.
            Write all 10 sections completely.
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
        return "Financial Data:\n" + context + "\n\n" +
                "Request: " + query + "\n\n" +
                "Please provide all 10 required sections of financial analysis.";
    }

    private String buildFinancialContext(List<Document> documents) {
        StringBuilder context = new StringBuilder();

        // Limit to first 2 documents and first 300 chars each - maximize response tokens
        int docCount = Math.min(2, documents.size());
        for (int i = 0; i < docCount; i++) {
            Document doc = documents.get(i);
            String text = doc.getText();
            // Truncate to 300 chars per doc
            String truncated = text.length() > 300 ? text.substring(0, 300) : text;
            context.append(truncated).append("\n\n");
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
