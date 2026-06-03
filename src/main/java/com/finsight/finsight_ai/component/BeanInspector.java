package com.finsight.finsight_ai.component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;

@Component
@RequiredArgsConstructor
@Slf4j
public class BeanInspector {

    private final ApplicationContext context;

    @PostConstruct
    public void inspect() {

        Arrays.stream(context.getBeanDefinitionNames())
                .filter(bean ->
                        bean.toLowerCase().contains("aws")
                                || bean.toLowerCase().contains("bedrock")
                                || bean.toLowerCase().contains("region"))
                .sorted()
                .forEach(bean ->
                        log.info("BEAN => {}", bean));
    }
}