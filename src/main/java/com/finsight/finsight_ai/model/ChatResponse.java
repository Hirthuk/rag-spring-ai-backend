package com.finsight.finsight_ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Setter
@Getter
public class ChatResponse {

    // Getters and Setters
    @JsonProperty("answer")
    private String answer;

    @JsonProperty("chartType")
    private String chartType;

    @JsonProperty("chartData")
    private List<Map<String, Object>> chartData;

    // Constructors
    public ChatResponse() {}

    public ChatResponse(String answer, String chartType, List<Map<String, Object>> chartData) {
        this.answer = answer;
        this.chartType = chartType;
        this.chartData = chartData;
    }

    // Helper method to check if response is valid
    public boolean isValid() {
        return answer != null && !answer.isEmpty() &&
                chartType != null &&
                chartData != null;
    }

    // Create error response
    public static ChatResponse error(String errorMessage) {
        return new ChatResponse(errorMessage, "none", new ArrayList<>());
    }
}