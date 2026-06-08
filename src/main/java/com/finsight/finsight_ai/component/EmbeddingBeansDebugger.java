package com.finsight.finsight_ai.component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmbeddingBeansDebugger {

    private final ApplicationContext context;

    @PostConstruct
    public void printBeans() {

        String[] beans =
                context.getBeanNamesForType(
                        org.springframework.ai.embedding.EmbeddingModel.class
                );

        log.info("================================");

        for (String bean : beans) {
            log.info("Embedding Bean => {}", bean);
        }

        log.info("================================");
    }
}
