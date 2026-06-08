package com.finsight.finsight_ai.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;


@RestController
@RequestMapping("/debug")
@RequiredArgsConstructor
public class DebugController {

    private final EmbeddingModel embeddingModel;
    private final ApplicationContext context;
    private final VectorStore vectorStore;
    @GetMapping("/embed")
    public String embed() {

        System.out.println(
                embeddingModel.getClass().getName()
        );

        embeddingModel.embed("hello world");

        return "success";
    }

    @GetMapping("/vectorstore")
    public String vectorStore() {

        String[] beans =
                context.getBeanNamesForType(
                        org.springframework.ai.vectorstore.VectorStore.class
                );

        return Arrays.toString(beans);
    }

    @GetMapping("/search")
    public Object search(
            @RequestParam String query) {

        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(5)
                        .build()
        );
    }
}