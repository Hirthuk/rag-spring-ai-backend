package com.finsight.finsight_ai.component;


import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
@Slf4j
public class PropertyDebugger {

    @Value("${spring.ai.vectorstore.chroma.host}")
    private String host;

    @PostConstruct
    public void printProperties() {

        log.info("CHROMA HOST = {}", host);
    }
}
