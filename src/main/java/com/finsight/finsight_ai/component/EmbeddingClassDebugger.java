package com.finsight.finsight_ai.component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmbeddingClassDebugger {

    private final EmbeddingModel embeddingModel;

    @PostConstruct
    public void debug() {

        log.info(
                "PRIMARY EMBEDDING CLASS = {}",
                embeddingModel.getClass().getName()
        );
    }
}
