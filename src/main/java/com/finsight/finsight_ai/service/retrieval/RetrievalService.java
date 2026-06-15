package com.finsight.finsight_ai.service.retrieval;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
//@ConditionalOnBean(VectorStore.class)
@RequiredArgsConstructor
@Slf4j
public class RetrievalService {

    private final VectorStore vectorStore;

    public List<Document> retrieveRelevantDocuments(
            String query
    ) {
        try {
            List<Document> documents =
                    vectorStore.similaritySearch(
                            SearchRequest.builder()
                                    .query(query)
                                    .topK(10)
                                    .build()
                    );

            return documents;
        } catch (Exception e) {
            log.warn("Vector store search failed: {}", e.getMessage());
            // Return empty list instead of crashing - the system can still work with the initial system prompt
            return new ArrayList<>();
        }
    }

    public String prepareRetrievalQuery(
            String userMessage
    ) {

        return userMessage;
    }
}