package com.finsight.finsight_ai.service.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.finsight.finsight_ai.model.ChatResponse;
import com.finsight.finsight_ai.model.ChartData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ResponseParserService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Main parser entry point
     */
    public ChatResponse parseAIResponse(String aiResponse) {

        if (aiResponse == null || aiResponse.trim().isEmpty()) {
            return createFallbackResponse("No response received from AI.");
        }

        /*
         * STRATEGY 1 - Direct JSON Parsing
         */
        try {
            String cleaned = cleanAndExtractJson(aiResponse);

            // Parse to JsonNode first to handle dynamic fields
            JsonNode rootNode = objectMapper.readTree(cleaned);

            ChatResponse response = new ChatResponse();

            // Extract answer
            if (rootNode.has("answer")) {
                response.setAnswer(rootNode.get("answer").asText());
            } else {
                response.setAnswer("AI processed the request.");
            }

            // Extract chartType
            if (rootNode.has("chartType")) {
                response.setChartType(rootNode.get("chartType").asText());
            } else {
                response.setChartType("none");
            }

            // Extract chartData with dynamic field handling
            ArrayList<ChartData> chartDataList = new ArrayList<>();

            if (rootNode.has("chartData") && rootNode.get("chartData").isArray()) {
                ArrayNode chartDataArray = (ArrayNode) rootNode.get("chartData");

                for (JsonNode dataPoint : chartDataArray) {
                    String year = dataPoint.has("year") ? dataPoint.get("year").asText() : null;

                    if (year == null) continue;

                    // Find ANY numeric field (growth_rate, profit, revenue, value, etc.)
                    Iterator<Map.Entry<String, JsonNode>> fields = dataPoint.fields();
                    Double numericValue = null;
                    String numericKey = null;

                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        String key = field.getKey();
                        JsonNode value = field.getValue();

                        if (!key.equals("year") && value.isNumber() && !value.isNull()) {
                            // Convert to Double
                            numericValue = value.asDouble();
                            numericKey = key;
                            break; // Take the first numeric field found
                        }
                    }

                    if (numericValue != null) {
                        // Create ChartData with the Double value
                        chartDataList.add(new ChartData(year, numericValue));
                        log.debug("Added chart data: year={}, value={}, originalKey={}", year, numericValue, numericKey);
                    } else {
                        log.warn("No numeric value found for year: {}", year);
                    }
                }
            }

            response.setChartData(chartDataList);

            // Validate, clean, and fix response
            validateAndFixResponse(response);
            response = cleanChartData(response);

            log.debug("Parsed AI Response - Answer: {}, ChartType: {}, ChartDataSize: {}",
                    response.getAnswer(), response.getChartType(), response.getChartData().size());

            return response;

        } catch (Exception e) {
            log.warn("Direct JSON parsing failed: {}", e.getMessage());
        }

        /*
         * STRATEGY 2 - Extract malformed JSON
         */
        try {
            String extracted = extractJsonFromMalformedResponse(aiResponse);
            if (extracted != null) {
                JsonNode rootNode = objectMapper.readTree(extracted);

                ChatResponse response = new ChatResponse();

                if (rootNode.has("answer")) {
                    response.setAnswer(rootNode.get("answer").asText());
                } else {
                    response.setAnswer("AI processed the request.");
                }

                if (rootNode.has("chartType")) {
                    response.setChartType(rootNode.get("chartType").asText());
                } else {
                    response.setChartType("none");
                }

                ArrayList<ChartData> chartDataList = new ArrayList<>();

                if (rootNode.has("chartData") && rootNode.get("chartData").isArray()) {
                    ArrayNode chartDataArray = (ArrayNode) rootNode.get("chartData");

                    for (JsonNode dataPoint : chartDataArray) {
                        String year = dataPoint.has("year") ? dataPoint.get("year").asText() : null;
                        if (year == null) continue;

                        Iterator<Map.Entry<String, JsonNode>> fields = dataPoint.fields();
                        Double numericValue = null;

                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> field = fields.next();
                            String key = field.getKey();
                            JsonNode value = field.getValue();

                            if (!key.equals("year") && value.isNumber() && !value.isNull()) {
                                numericValue = value.asDouble();
                                break;
                            }
                        }

                        if (numericValue != null) {
                            chartDataList.add(new ChartData(year, numericValue));
                        }
                    }
                }

                response.setChartData(chartDataList);
                validateAndFixResponse(response);
                response = cleanChartData(response);

                return response;
            }
        } catch (Exception e) {
            log.warn("Malformed JSON extraction failed: {}", e.getMessage());
        }

        /*
         * STRATEGY 3 - Convert plain text response
         */
        return createResponseFromNaturalLanguage(aiResponse);
    }

    /**
     * Clean markdown and isolate JSON
     */
    private String cleanAndExtractJson(String response) {
        String cleaned = response.trim();

        // Remove markdown JSON wrapper
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        }
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        // Extract JSON object boundaries
        int start = cleaned.indexOf("{");
        int end = cleaned.lastIndexOf("}");
        if (start != -1 && end != -1 && start < end) {
            cleaned = cleaned.substring(start, end + 1);
        }

        // Escape invalid characters
        cleaned = escapeControlCharacters(cleaned);

        return cleaned;
    }

    /**
     * Extract malformed JSON block
     */
    private String extractJsonFromMalformedResponse(String response) {
        int start = response.indexOf("{\"answer\"");
        if (start == -1) {
            start = response.indexOf("{'answer'");
        }
        if (start == -1) {
            return null;
        }

        int braceCount = 0;
        int end = -1;

        for (int i = start; i < response.length(); i++) {
            char c = response.charAt(i);
            if (c == '{') {
                braceCount++;
            }
            if (c == '}') {
                braceCount--;
                if (braceCount == 0) {
                    end = i;
                    break;
                }
            }
        }

        if (end != -1 && end > start) {
            return response.substring(start, end + 1);
        }
        return null;
    }

    /**
     * Convert natural language response into ChatResponse
     */
    private ChatResponse createResponseFromNaturalLanguage(String response) {
        String answer = response;

        answer = answer.replaceAll("\"chartType\":\\s*\"[^\"]*\"", "")
                .replaceAll("\"chartData\":\\s*\\[[^\\]]*\\]", "")
                .replaceAll("\"answer\":\\s*\"?", "")
                .replaceAll("\"\\s*[,}]$", "")
                .replaceAll("\\{[^{}]*\\}", "");

        answer = answer.trim()
                .replaceAll("^\"|\"$", "")
                .replaceAll("\\\\n", "\n")
                .replaceAll("\\\\\"", "\"");

        if (answer.isEmpty()) {
            answer = "Unable to properly parse AI response.";
        }

        ChatResponse chatResponse = new ChatResponse();
        chatResponse.setAnswer(answer);
        chatResponse.setChartType("none");
        chatResponse.setChartData(new ArrayList<>());

        return chatResponse;
    }

    /**
     * Escape invalid JSON control characters
     */
    private String escapeControlCharacters(String json) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean inEscape = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (inEscape) {
                result.append(c);
                inEscape = false;
                continue;
            }

            if (c == '\\' && inString) {
                inEscape = true;
                result.append(c);
                continue;
            }

            if (c == '"' && !inEscape) {
                inString = !inString;
                result.append(c);
                continue;
            }

            if (inString) {
                if (c == '\n') {
                    result.append("\\n");
                } else if (c == '\r') {
                    result.append("\\r");
                } else if (c == '\t') {
                    result.append("\\t");
                } else {
                    result.append(c);
                }
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    /**
     * Validate and fix response
     */
    private void validateAndFixResponse(ChatResponse response) {
        if (response.getAnswer() == null || response.getAnswer().trim().isEmpty()) {
            response.setAnswer("AI processed the request.");
        }

        // Fix escaped content
        String answer = response.getAnswer();
        if (answer != null) {
            answer = answer.replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
            response.setAnswer(answer);
        }

        if (response.getChartType() == null) {
            response.setChartType("none");
        }

        if (response.getChartData() == null) {
            response.setChartData(new ArrayList<>());
        }
    }

    /**
     * Clean chart data - remove entries with null or invalid values
     */
    private ChatResponse cleanChartData(ChatResponse response) {
        if (response.getChartData() == null || response.getChartData().isEmpty()) {
            if (!"none".equals(response.getChartType())) {
                response.setChartType("none");
            }
            return response;
        }

        // Filter out chart data entries with null values
        List<ChartData> validChartData = response.getChartData().stream()
                .filter(data -> {
                    if (data == null || data.getYear() == null) {
                        log.debug("Removing invalid chart data entry: null data or year");
                        return false;
                    }
                    if (data.getValue() == null) {
                        log.debug("Removing null value for year: {}", data.getYear());
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());

        // If all data was invalid, set to empty and chartType to none
        if (validChartData.isEmpty()) {
            response.setChartData(new ArrayList<>());
            response.setChartType("none");
            log.debug("All chart data was invalid, cleared chartData and set chartType to none");
        }
        // If some data was removed, update the response
        else if (validChartData.size() != response.getChartData().size()) {
            response.setChartData(validChartData);
            log.debug("Removed {} invalid chart data entries",
                    response.getChartData().size() - validChartData.size());
        }

        return response;
    }

    /**
     * Generic fallback response
     */
    private ChatResponse createFallbackResponse(String message) {
        ChatResponse response = new ChatResponse();
        response.setAnswer(message);
        response.setChartType("none");
        response.setChartData(new ArrayList<>());
        return response;
    }
}