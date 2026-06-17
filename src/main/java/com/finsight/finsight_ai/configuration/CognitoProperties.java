package com.finsight.finsight_ai.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "aws.cognito")
public class CognitoProperties {
    private String region = "us-east-1";
    private String userPoolId;
    private String clientId;
    /** Leave blank for public app clients (no client secret configured in Cognito). */
    private String clientSecret = "";
}
