package com.finsight.finsight_ai.controller;

import com.finsight.finsight_ai.model.ChatResponse;
import com.finsight.finsight_ai.model.FrontendRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor

public class DebugController {

    private final VectorStore vectorStore;

    @PostMapping("/search")
    public List<String> debugSearch(
            @RequestBody FrontendRequest request
            ) {
        String query = request.getUserMessage();
        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(10).build()
        );

        return documents.stream().map(Document::getText).toList();
    }
}
