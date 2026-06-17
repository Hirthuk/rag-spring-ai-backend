package com.finsight.finsight_ai.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.pinpoint.PinpointClient;

@Configuration
public class PinpointConfiguration {

    @Bean
    public PinpointClient pinpointClient() {
        return PinpointClient.builder().region(Region.US_EAST_1)
                .build();
    }
}
