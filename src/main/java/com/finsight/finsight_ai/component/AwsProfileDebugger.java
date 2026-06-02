package com.finsight.finsight_ai.component;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AwsProfileDebugger {

    @PostConstruct
    public void checkProfile() {

        log.info("AWS_PROFILE ENV = {}",
                System.getenv("AWS_PROFILE"));

        log.info("aws.profile PROP = {}",
                System.getProperty("aws.profile"));
    }
}
