package com.finsight.finsight_ai.service.retrieval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RetrievalService {

    private final VectorStore vectorStore;

    public List<Document> retrieveRelevantDocuments(
            String query
    ) {

        List<Document> documents =
                vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(query)
                                .topK(10)
                                .build()
                );

        log.info(
                "Retrieved {} documents for query: {}",
                documents.size(),
                query
        );

        documents.forEach(doc -> {

            log.info(
                    "Chunk Metadata: {}",
                    doc.getMetadata()
            );

            log.info(
                    "Chunk Text: {}",
                    doc.getText()
            );
        });

        return documents;
    }

    public String prepareRetrievalQuery(
            String userMessage
    ) {

        return userMessage
                .replace("Tell me about", "")
                .replace("What is", "")
                .replace("sales growth rate", "")
                .replace("growth rate", "")
                .replace("sales", "")
                .trim();
    }
}