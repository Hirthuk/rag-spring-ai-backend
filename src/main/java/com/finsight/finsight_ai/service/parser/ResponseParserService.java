package com.finsight.finsight_ai.service.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.finsight.finsight_ai.model.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ResponseParserService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatResponse parseAIResponse(String aiResponse) {

        if (aiResponse == null || aiResponse.trim().isEmpty()) {
            return createFallbackResponse("No response received from AI.");
        }

        /*
         * STRATEGY 1 - Direct JSON Parsing
         */
        try {
            String cleaned = cleanAndExtractJson(aiResponse);
            JsonNode rootNode = objectMapper.readTree(cleaned);
            ChatResponse response = buildResponseFromJsonNode(rootNode);
            validateAndFixResponse(response);
            response = cleanChartData(response);
            response = formatChartNumbersForFrontend(response);
            log.debug("Strategy 1 succeeded");
            return response;
        } catch (Exception e) {
            log.warn("Strategy 1 (direct JSON) failed: {}", e.getMessage());
        }

        /*
         * STRATEGY 2 - Extract JSON block starting with {"answer"
         */
        try {
            String extracted = extractJsonFromMalformedResponse(aiResponse);
            if (extracted != null) {
                JsonNode rootNode = objectMapper.readTree(extracted);
                ChatResponse response = buildResponseFromJsonNode(rootNode);
                validateAndFixResponse(response);
                response = cleanChartData(response);
                response = formatChartNumbersForFrontend(response);
                log.debug("Strategy 2 succeeded");
                return response;
            }
        } catch (Exception e) {
            log.warn("Strategy 2 (malformed JSON extraction) failed: {}", e.getMessage());
        }

        /*
         * STRATEGY 3 - Repair truncated JSON by closing open brackets/strings
         */
        try {
            String repaired = repairTruncatedJson(aiResponse);
            if (repaired != null) {
                JsonNode rootNode = objectMapper.readTree(repaired);
                ChatResponse response = buildResponseFromJsonNode(rootNode);
                validateAndFixResponse(response);
                response = cleanChartData(response);
                response = formatChartNumbersForFrontend(response);
                log.debug("Strategy 3 (JSON repair) succeeded");
                return response;
            }
        } catch (Exception e) {
            log.warn("Strategy 3 (JSON repair) failed: {}", e.getMessage());
        }

        /*
         * STRATEGY 4 - Extract answer field value directly from text
         */
        log.warn("All JSON strategies failed, falling back to text extraction");
        return extractAnswerAsText(aiResponse);
    }

    /**
     * Build a ChatResponse from a parsed JsonNode.
     */
    private ChatResponse buildResponseFromJsonNode(JsonNode rootNode) {
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

        List<Map<String, Object>> chartDataList = new ArrayList<>();
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
                        numericValue = formatNumberToLong(value);
                        break;
                    }
                }

                if (numericValue != null) {
                    Map<String, Object> dataMap = new HashMap<>();
                    dataMap.put("year", year);
                    dataMap.put("value", numericValue);
                    // Preserve the forecast/historical marker so the frontend can
                    // highlight projected (add-on) points distinctly. Fall back to
                    // detecting the "F" suffix on the year label.
                    if (dataPoint.has("type") && !dataPoint.get("type").isNull()) {
                        dataMap.put("type", dataPoint.get("type").asText());
                    } else {
                        dataMap.put("type", year.endsWith("F") ? "forecast" : "historical");
                    }
                    chartDataList.add(dataMap);
                    log.debug("Added chart data: year={}, value={}, type={}", year, numericValue, dataMap.get("type"));
                } else {
                    log.warn("No numeric value found for year: {}", year);
                }
            }
        }

        response.setChartData(chartDataList);
        return response;
    }

    /**
     * Repair truncated JSON by closing any open strings, arrays, and objects.
     * Returns null if the JSON looks already balanced or unrepairable.
     */
    private String repairTruncatedJson(String text) {
        String cleaned = text.trim();

        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        cleaned = cleaned.trim();

        int start = cleaned.indexOf("{");
        if (start == -1) return null;
        cleaned = cleaned.substring(start);

        // Walk the string tracking nesting state
        boolean inString = false;
        boolean inEscape = false;
        int braceCount = 0;
        int bracketCount = 0;

        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (inEscape) {
                inEscape = false;
                continue;
            }
            if (c == '\\' && inString) {
                inEscape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{') braceCount++;
                else if (c == '}') braceCount--;
                else if (c == '[') bracketCount++;
                else if (c == ']') bracketCount--;
            }
        }

        // Already balanced — Strategy 1 should have worked; don't repair here
        if (!inString && braceCount == 0 && bracketCount == 0) return null;
        // Negative counts mean extra closers — can't repair reliably
        if (braceCount < 0 || bracketCount < 0) return null;

        StringBuilder repaired = new StringBuilder(cleaned);
        // Strip any trailing comma before we close
        String trimmed = repaired.toString().stripTrailing();
        if (trimmed.endsWith(",")) {
            repaired = new StringBuilder(trimmed.substring(0, trimmed.length() - 1));
        }

        if (inString) repaired.append('"');
        for (int i = 0; i < bracketCount; i++) repaired.append(']');
        for (int i = 0; i < braceCount; i++) repaired.append('}');

        return escapeControlCharacters(repaired.toString());
    }

    /**
     * Last-resort strategy: extract the "answer" field value by walking the raw text
     * character by character. Works even on truncated or heavily malformed responses.
     */
    private ChatResponse extractAnswerAsText(String text) {
        String answer = extractAnswerFieldValue(text);

        if (answer == null || answer.isBlank()) {
            // Strip leftover JSON punctuation so the user sees readable text
            answer = stripJsonSyntax(text);
        }

        if (answer == null || answer.isBlank()) {
            answer = "Unable to properly parse AI response.";
        }

        ChatResponse chatResponse = new ChatResponse();
        chatResponse.setAnswer(answer);
        chatResponse.setChartType("none");
        chatResponse.setChartData(new ArrayList<>());
        return chatResponse;
    }

    /**
     * Walk the text character by character to extract the value of the "answer" field.
     * Handles escaped characters and truncated JSON gracefully.
     */
    private String extractAnswerFieldValue(String text) {
        int keyIdx = text.indexOf("\"answer\"");
        if (keyIdx == -1) keyIdx = text.indexOf("'answer'");
        if (keyIdx == -1) return null;

        int colonIdx = text.indexOf(":", keyIdx + 8);
        if (colonIdx == -1) return null;

        int valueStart = colonIdx + 1;
        while (valueStart < text.length() && Character.isWhitespace(text.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= text.length() || text.charAt(valueStart) != '"') return null;

        valueStart++; // move past opening quote

        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = valueStart; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> { sb.append('\\'); sb.append(c); }
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                String result = sb.toString().trim();
                return result.isEmpty() ? null : result;
            } else {
                sb.append(c);
            }
        }

        // Truncated response — return what we have if it's meaningful
        String partial = sb.toString().trim();
        return partial.length() > 20 ? partial : null;
    }

    /**
     * Strip JSON structural characters as a last-resort cleanup.
     */
    private String stripJsonSyntax(String text) {
        return text
                .replaceAll("(?s)\"chartData\"\\s*:\\s*\\[.*?\\]", "")
                .replaceAll("\"chartType\"\\s*:\\s*\"[^\"]*\"", "")
                .replaceAll("\"answer\"\\s*:\\s*", "")
                .replaceAll("[{}\\[\\]]", "")
                .replaceAll(",\\s*,", ",")
                .replaceAll("^[\\s,]+|[\\s,]+$", "")
                .replaceAll("\\\\n", "\n")
                .replaceAll("\\\\\"", "\"")
                .trim();
    }

    /**
     * Format numbers to whole-number doubles for the frontend.
     */
    private Double formatNumberToLong(JsonNode numberNode) {
        if (numberNode == null || numberNode.isNull()) return null;
        try {
            if (numberNode.isNumber()) {
                return (double) (long) numberNode.asDouble();
            } else if (numberNode.isTextual()) {
                double v = Double.parseDouble(numberNode.asText());
                return (double) (long) v;
            }
        } catch (Exception e) {
            log.warn("Failed to format number: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Ensure chart value fields are whole-number doubles so the frontend
     * never receives scientific notation like 1.41E10.
     */
    private ChatResponse formatChartNumbersForFrontend(ChatResponse response) {
        if (response.getChartData() == null || response.getChartData().isEmpty()) {
            return response;
        }
        List<Map<String, Object>> formatted = new ArrayList<>();
        for (Map<String, Object> dataPoint : response.getChartData()) {
            // Copy all fields (year, type, and any others) then normalize value
            Map<String, Object> out = new HashMap<>(dataPoint);
            Object value = dataPoint.get("value");
            if (value instanceof Number n) {
                out.put("value", (double) (long) n.doubleValue());
            } else {
                out.put("value", value);
            }
            formatted.add(out);
        }
        response.setChartData(formatted);
        return response;
    }

    /**
     * Strip markdown fences, find the outermost JSON object, and escape
     * in-string control characters so Jackson can parse the result.
     */
    private String cleanAndExtractJson(String response) {
        String cleaned = response.trim();

        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        cleaned = cleaned.trim();

        int start = cleaned.indexOf("{");
        int end = cleaned.lastIndexOf("}");
        if (start != -1 && end != -1 && start < end) {
            cleaned = cleaned.substring(start, end + 1);
        }

        return escapeControlCharacters(cleaned);
    }

    /**
     * Scan for a JSON block that starts with {"answer" and extract it by
     * balanced-brace counting.
     */
    private String extractJsonFromMalformedResponse(String response) {
        int start = response.indexOf("{\"answer\"");
        if (start == -1) start = response.indexOf("{'answer'");
        if (start == -1) return null;

        int braceCount = 0;
        int end = -1;
        for (int i = start; i < response.length(); i++) {
            char c = response.charAt(i);
            if (c == '{') braceCount++;
            else if (c == '}') {
                braceCount--;
                if (braceCount == 0) { end = i; break; }
            }
        }

        if (end != -1 && end > start) {
            return escapeControlCharacters(response.substring(start, end + 1));
        }
        return null;
    }

    /**
     * Escape control characters (0x00-0x1F) inside JSON string values so that
     * Jackson can parse the result.
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

            if (c == '"') {
                inString = !inString;
                result.append(c);
                continue;
            }

            if (inString) {
                if (c == '\n') result.append("\\n");
                else if (c == '\r') result.append("\\r");
                else if (c == '\t') result.append("\\t");
                else if (c < 0x20) result.append(String.format("\\u%04x", (int) c));
                else result.append(c);
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    /**
     * Post-parse cleanup: unescape sequences left in the answer text and
     * ensure required fields have sensible defaults.
     */
    private void validateAndFixResponse(ChatResponse response) {
        if (response.getAnswer() == null || response.getAnswer().trim().isEmpty()) {
            response.setAnswer("AI processed the request.");
        }

        String answer = response.getAnswer();
        if (answer != null) {
            answer = answer.replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
            response.setAnswer(answer);
        }

        if (response.getChartType() == null) response.setChartType("none");
        if (response.getChartData() == null) response.setChartData(new ArrayList<>());
    }

    /**
     * Remove chart data entries that have null year or null value. If all
     * entries are invalid, reset chartType to "none".
     */
    private ChatResponse cleanChartData(ChatResponse response) {
        if (response.getChartData() == null || response.getChartData().isEmpty()) {
            if (!"none".equals(response.getChartType())) response.setChartType("none");
            return response;
        }

        List<Map<String, Object>> valid = response.getChartData().stream()
                .filter(data -> {
                    if (data == null) return false;
                    Object year = data.get("year");
                    Object value = data.get("value");
                    if (year == null || value == null) {
                        log.debug("Removing chart entry with null year or value");
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());

        if (valid.isEmpty()) {
            response.setChartData(new ArrayList<>());
            response.setChartType("none");
        } else {
            response.setChartData(valid);
        }
        return response;
    }

    private ChatResponse createFallbackResponse(String message) {
        ChatResponse response = new ChatResponse();
        response.setAnswer(message);
        response.setChartType("none");
        response.setChartData(new ArrayList<>());
        return response;
    }
}