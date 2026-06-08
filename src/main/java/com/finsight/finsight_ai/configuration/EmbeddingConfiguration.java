package com.finsight.finsight_ai.configuration;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class EmbeddingConfiguration {

    @Bean
    @Primary
    public EmbeddingModel primaryEmbeddingModel(
            @Qualifier("cohereEmbeddingModel") EmbeddingModel embeddingModel
    ) {
        // This takes the auto-configured Cohere model and makes it the default
        // for any component that asks for a generic EmbeddingModel
        return embeddingModel;
    }
}