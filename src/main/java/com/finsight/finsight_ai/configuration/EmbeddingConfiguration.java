package com.finsight.finsight_ai.configuration;

// This configuration is disabled because Spring AI auto-configures embeddings via application.properties
// Remove this file or keep it commented as shown below

/*
import org.springframework.ai.bedrock.titan.BedrockTitanEmbeddingModel;
import org.springframework.ai.bedrock.titan.BedrockTitanEmbeddingOptions;
import org.springframework.ai.bedrock.titan.api.TitanEmbeddingBedrockApi;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

*/

//@Configuration
public class EmbeddingConfiguration {

//    @Value("${spring.ai.bedrock.aws.region}")
//    private String region;
//
//    @Value("${AWS_PROFILE}")
//    private String awsProfile;
//
//    @Bean
//    @Primary
//    public EmbeddingModel embeddingModel() {
//        // Create Bedrock Runtime Client
//        BedrockRuntimeClient bedrockClient = BedrockRuntimeClient.builder()
//                .region(Region.of(region))
//                .credentialsProvider(ProfileCredentialsProvider.create(awsProfile))
//                .build();
//
//        // Create Titan API wrapper
//        TitanEmbeddingBedrockApi titanApi = new TitanEmbeddingBedrockApi(bedrockClient);
//
//        // Create and return the embedding model
//        return new BedrockTitanEmbeddingModel(titanApi,
//                BedrockTitanEmbeddingOptions.builder()
//                        .withInputType(BedrockTitanEmbeddingOptions.InputType.TEXT)
//                        .build()
//        );
//    }
}

