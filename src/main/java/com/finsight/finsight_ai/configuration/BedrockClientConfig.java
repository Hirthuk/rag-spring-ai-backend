package com.finsight.finsight_ai.configuration;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Custom AWS Bedrock Runtime clients with extended timeouts.
 *
 * Why this exists:
 * Spring AI 1.0.1's BedrockProxyChatModel builds its default sync client with
 * Apache HttpClient's DEFAULT socket timeout (~30s) and only sets apiCallTimeout
 * via the `spring.ai.bedrock.aws.timeout` property. Slow models like Claude Opus
 * can take 90s+ to respond, so the socket read times out ("Read timed out")
 * before the model finishes generating.
 *
 * The Bedrock Converse auto-configuration consumes BedrockRuntimeClient and
 * BedrockRuntimeAsyncClient beans via ObjectProvider.getIfAvailable(), so the
 * beans defined here override the defaults and apply proper socket/read timeouts.
 *
 * Credentials use DefaultCredentialsProvider, which respects the AWS_PROFILE
 * environment variable - matching the application's existing credential setup.
 */
@Configuration
@Slf4j
public class BedrockClientConfig {

    @Value("${spring.ai.bedrock.aws.region:us-east-1}")
    private String region;

    // Socket read timeout - must exceed the model's slowest response time.
    // Opus can take 90s+, so 5 minutes gives comfortable headroom.
    @Value("${finsight.bedrock.socket-timeout-seconds:300}")
    private long socketTimeoutSeconds;

    // Overall API call timeout (per the whole call, including retries).
    @Value("${finsight.bedrock.api-call-timeout-seconds:360}")
    private long apiCallTimeoutSeconds;

    @Value("${finsight.bedrock.connection-timeout-seconds:30}")
    private long connectionTimeoutSeconds;

    private final ObjectProvider<AwsCredentialsProvider> credentialsProviderObjectProvider;

    public BedrockClientConfig(ObjectProvider<AwsCredentialsProvider> credentialsProviderObjectProvider) {
        this.credentialsProviderObjectProvider = credentialsProviderObjectProvider;
    }

    /**
     * Reuse the AwsCredentialsProvider the rest of the app already uses (configured
     * by Spring AI from spring.ai.bedrock.aws.*). Fall back to the default chain
     * (which honors the AWS_PROFILE env var) if no such bean exists.
     */
    private AwsCredentialsProvider resolveCredentialsProvider() {
        AwsCredentialsProvider provider = credentialsProviderObjectProvider.getIfAvailable();
        if (provider != null) {
            log.info("BedrockClientConfig using shared AwsCredentialsProvider: {}", provider.getClass().getSimpleName());
            return provider;
        }
        log.info("BedrockClientConfig falling back to DefaultCredentialsProvider (honors AWS_PROFILE)");
        return DefaultCredentialsProvider.create();
    }

    @Bean
    public BedrockRuntimeClient bedrockRuntimeClient() {
        log.info("Creating custom BedrockRuntimeClient (sync) - region={}, socketTimeout={}s, apiCallTimeout={}s",
                region, socketTimeoutSeconds, apiCallTimeoutSeconds);

        return BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .credentialsProvider(resolveCredentialsProvider())
                .httpClientBuilder(ApacheHttpClient.builder()
                        .socketTimeout(Duration.ofSeconds(socketTimeoutSeconds))
                        .connectionTimeout(Duration.ofSeconds(connectionTimeoutSeconds)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(apiCallTimeoutSeconds))
                        .apiCallAttemptTimeout(Duration.ofSeconds(apiCallTimeoutSeconds))
                        .build())
                .build();
    }

    @Bean
    public BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient() {
        log.info("Creating custom BedrockRuntimeAsyncClient - region={}, readTimeout={}s, apiCallTimeout={}s",
                region, socketTimeoutSeconds, apiCallTimeoutSeconds);

        return BedrockRuntimeAsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(resolveCredentialsProvider())
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .readTimeout(Duration.ofSeconds(socketTimeoutSeconds))
                        .writeTimeout(Duration.ofSeconds(socketTimeoutSeconds))
                        .connectionTimeout(Duration.ofSeconds(connectionTimeoutSeconds))
                        .connectionAcquisitionTimeout(Duration.ofSeconds(connectionTimeoutSeconds)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(apiCallTimeoutSeconds))
                        .apiCallAttemptTimeout(Duration.ofSeconds(apiCallTimeoutSeconds))
                        .build())
                .build();
    }
}
