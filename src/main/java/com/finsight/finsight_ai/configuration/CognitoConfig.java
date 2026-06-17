package com.finsight.finsight_ai.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

@Configuration
@EnableConfigurationProperties(CognitoProperties.class)
@Slf4j
public class CognitoConfig {

    @Bean
    public CognitoIdentityProviderClient cognitoIdentityProviderClient(CognitoProperties props) {
        log.info("Building CognitoIdentityProviderClient — region={}, userPoolId={}",
                props.getRegion(), props.getUserPoolId());
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(props.getRegion()))
                // Reuse the same credential chain (AWS_PROFILE / instance profile / env vars)
                // that the rest of the app uses for Bedrock.
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
