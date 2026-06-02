package com.finsight.finsight_ai.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

//@Configuration
public class BedrockConfig {
//    @Value("${AWS_PROFILE}")
//    private String awsProfile;
//
//    @Bean
//    public BedrockRuntimeClient bedrockRuntimeClient() {
//        return BedrockRuntimeClient.builder().credentialsProvider(
//                ProfileCredentialsProvider.create(awsProfile)
//        ).build();
//    }
}
