package com.finsight.finsight_ai.service;

import com.finsight.finsight_ai.model.ChartData;
import com.finsight.finsight_ai.model.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient.Builder chatClientBuilder;
    private final VectorStore vectorStore;

    public ChatResponse askQuestion(String question) {

//      Step 1 - Search similar documents from the vector store
        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(3).build()
        );

//        Step 2 - Combined retrieved Context
        String context = documents.stream()
                .map(Document::getText).collect(Collectors.joining("\n"));

//        Step 3 - Build RAG Prompt
        String prompt = """
                You are a Financial AI Assistant.
                
                Answer ONLY using the provided financial data.
                
                Financial Data:
                %s
                
                User Question:
                %s
                """.formatted(context, question);

//        Step 4 - Call the Chat API
        ChatClient chatClient = chatClientBuilder.build();
        String answer = chatClient.prompt().user(prompt).call().content();

        List<ChartData> chartData = new ArrayList<>();

        // Revenue Chart
        if (question.toLowerCase().contains("revenue")) {

            chartData.add(new ChartData("2020", 10.0));
            chartData.add(new ChartData("2021", 12.0));
            chartData.add(new ChartData("2022", 15.0));
            chartData.add(new ChartData("2023", 18.0));
            chartData.add(new ChartData("2024", 22.0));

            return new ChatResponse(
                    answer,
                    "line",
                    chartData
            );
        }

        // Profit Chart
        if (question.toLowerCase().contains("profit")) {

            chartData.add(new ChartData("2020", 2.0));
            chartData.add(new ChartData("2021", 3.0));
            chartData.add(new ChartData("2022", 4.0));
            chartData.add(new ChartData("2023", 5.0));
            chartData.add(new ChartData("2024", 7.0));

            return new ChatResponse(
                    answer,
                    "bar",
                    chartData
            );
        }
        return new ChatResponse(
                answer,
                "none",
                chartData
        );
    }
}
