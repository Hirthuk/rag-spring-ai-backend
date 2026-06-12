package com.finsight.finsight_ai.controller;

import com.finsight.finsight_ai.service.FinancialAssistantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/financial")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:5174"})
public class FinancialAssistantController {

    private final FinancialAssistantService financialAssistantService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Comprehensive financial analysis with detailed 5-year forecast
     * Returns detailed analysis with chart data
     */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyzeFinancials(
            @RequestParam(value = "query", required = true) String query) {

        log.info("Financial Analysis Request: {}", query);

        try {
            String jsonResponse = financialAssistantService.analyzeFinancials(query);
            // Parse and return the JSON response
            return ResponseEntity.ok(objectMapper.readValue(jsonResponse, Object.class));
        } catch (Exception e) {
            log.error("Error in financial analysis: {}", e.getMessage());
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

    /**
     * Detailed 5-year forecast for financial metrics with gross profit analysis
     *
     * Example: /api/financial/forecast?company=Apple&metric=Revenue&years=5
     */
    @PostMapping("/forecast")
    public ResponseEntity<?> forecastFinancials(
            @RequestParam(value = "company") String company,
            @RequestParam(value = "metric", defaultValue = "Revenue") String metric,
            @RequestParam(value = "years", defaultValue = "5") Integer years) {

        log.info("5-Year Forecast Request: Company={}, Metric={}, Years={}", company, metric, years);

        try {
            String jsonResponse = financialAssistantService.forecastFinancials(company, metric, years);
            return ResponseEntity.ok(objectMapper.readValue(jsonResponse, Object.class));
        } catch (Exception e) {
            log.error("Error in forecasting: {}", e.getMessage());
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

    /**
     * Compare financial metrics between two companies or entities
     *
     * Example: /api/financial/compare?entity1=Apple&entity2=Microsoft&metric=Revenue
     */
    @PostMapping("/compare")
    public ResponseEntity<?> compareFinancials(
            @RequestParam(value = "entity1") String entity1,
            @RequestParam(value = "entity2") String entity2,
            @RequestParam(value = "metric", defaultValue = "Revenue") String metric) {

        log.info("Comparison Request: {} vs {}, Metric: {}", entity1, entity2, metric);

        String query = String.format(
                "Compare %s between %s and %s. Provide detailed analysis of %s performance, trends, and 5-year forecast for both. " +
                "Include gross profit analysis and competitive comparison.",
                metric, entity1, entity2, metric
        );

        try {
            String jsonResponse = financialAssistantService.analyzeFinancials(query);
            return ResponseEntity.ok(objectMapper.readValue(jsonResponse, Object.class));
        } catch (Exception e) {
            log.error("Error in comparison: {}", e.getMessage());
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

    /**
     * Calculate financial metrics and ratios from raw data
     *
     * Example: POST /api/financial/calculate-metrics
     * Body: {
     *   "revenue1": 100000000,
     *   "revenue2": 112000000,
     *   "revenue3": 125440000,
     *   "grossProfit1": 40000000,
     *   "grossProfit2": 44800000,
     *   "grossProfit3": 50176000,
     *   "netProfit1": 10000000,
     *   "netProfit2": 12000000,
     *   "netProfit3": 15000000
     * }
     */
    @PostMapping("/calculate-metrics")
    public ResponseEntity<FinancialAssistantService.FinancialMetrics> calculateMetrics(
            @RequestBody MetricsRequest request) {

        log.info("Calculating financial metrics with gross profit analysis");

        try {
            var metrics = financialAssistantService.calculateMetrics(
                    request.getRevenue1(),
                    request.getRevenue2(),
                    request.getRevenue3(),
                    request.getGrossProfit1(),
                    request.getGrossProfit2(),
                    request.getGrossProfit3(),
                    request.getNetProfit1(),
                    request.getNetProfit2(),
                    request.getNetProfit3()
            );
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            log.error("Error calculating metrics: {}", e.getMessage());
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Generate detailed 5-year forecast with gross profit
     *
     * Example: POST /api/financial/generate-forecast
     * Body: {
     *   "currentRevenue": 125440000,
     *   "currentGrossProfit": 50000000,
     *   "cagr": 11.8,
     *   "gpMargin": 40.0
     * }
     */
    @PostMapping("/generate-forecast")
    public ResponseEntity<?> generateDetailedForecast(
            @RequestBody DetailedForecastRequest request) {

        log.info("Generating 5-year forecast: Revenue={}, CAGR={}%, Margin={}%",
                request.getCurrentRevenue(), request.getCagr(), request.getGpMargin());

        try {
            var forecast = financialAssistantService.generateDetailedForecast(
                    request.getCurrentRevenue(),
                    request.getCurrentGrossProfit(),
                    request.getCagr(),
                    request.getGpMargin()
            );
            return ResponseEntity.ok(forecast);
        } catch (Exception e) {
            log.error("Error generating forecast: {}", e.getMessage());
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

    /**
     * Request DTOs
     */
    @lombok.Data
    public static class MetricsRequest {
        private double revenue1;
        private double revenue2;
        private double revenue3;
        private double grossProfit1;
        private double grossProfit2;
        private double grossProfit3;
        private double netProfit1;
        private double netProfit2;
        private double netProfit3;
    }

    @lombok.Data
    public static class DetailedForecastRequest {
        private double currentRevenue;
        private double currentGrossProfit;
        private double cagr;
        private double gpMargin;
    }
}
