package com.finsight.finsight_ai.component;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class BeanDebugger {

    @Autowired
    private ApplicationContext context;

    @PostConstruct
    public void printBeans() {

        Arrays.stream(context.getBeanDefinitionNames())
                .filter(bean -> bean.toLowerCase().contains("bedrock"))
                .sorted()
                .forEach(System.out::println);
    }
}