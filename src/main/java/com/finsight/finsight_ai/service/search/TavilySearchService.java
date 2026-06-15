package com.finsight.finsight_ai.service.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class TavilySearchService {

    @Value("${tavily.api.key}")
    private String apiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public String search(String query) {
        try {
            String jsonBody = """
                    {
                      "api_key":"%s",
                      "query":"%s",
                      "search_depth":"advanced",
                      "include_answer":true,
                      "max_results":5
                    }
                    """.formatted(apiKey, query.replace("\"", "\\\""));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.tavily.com/search"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            JsonNode root = objectMapper.readTree(response.body());
            StringBuilder result = new StringBuilder();

            // Include the synthesised answer if Tavily produced one
            String answer = root.path("answer").asText("");
            if (!answer.isBlank()) {
                result.append(answer).append("\n\n");
            }

            // Append top-3 result snippets for richer financial context
            JsonNode results = root.path("results");
            if (results.isArray()) {
                int count = 0;
                for (JsonNode r : results) {
                    if (count++ >= 3) break;
                    String title   = r.path("title").asText("");
                    String content = r.path("content").asText("");
                    if (!content.isBlank()) {
                        if (!title.isBlank()) result.append("Source: ").append(title).append("\n");
                        result.append(content).append("\n\n");
                    }
                }
            }

            return result.toString().trim();

        } catch (Exception e) {
            return "";
        }
    }
}