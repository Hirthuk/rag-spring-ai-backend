package com.finsight.finsight_ai.configuration;

import org.springframework.ai.bedrock.titan.BedrockTitanEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class EmbeddingConfiguration {

    @Bean
    @Primary
    public EmbeddingModel primaryEmbeddingModel(
            BedrockTitanEmbeddingModel titanEmbeddingModel
    ) {

        return titanEmbeddingModel;
    }
}
