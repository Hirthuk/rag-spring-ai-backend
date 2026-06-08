package com.finsight.finsight_ai.component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TitanConfigDebugger {

    private final ApplicationContext context;

    @EventListener(ApplicationReadyEvent.class)
    public void run() {

        Object titan =
                context.getBean("titanEmbeddingModel");

        log.info(
                "Titan Bean Class = {}",
                titan.getClass()
        );
    }
}
