package com.finsight.finsight_ai.component;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class VectorStoreDebugger {

    private final VectorStore vectorStore;

    @Bean
    public ApplicationRunner checkVectorStore() {
        return args -> {

            log.info("=================================");
            log.info("VECTOR STORE FOUND");
            log.info("CLASS = {}", vectorStore.getClass().getName());
            log.info("=================================");
        };
    }
}
