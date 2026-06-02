package com.finsight.finsight_ai.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
public class EmbeddingConfiguration {

    @Bean
    @Primary
    public EmbeddingModel embeddingModel(
            @Qualifier("titanEmbeddingModel")
            EmbeddingModel titanEmbeddingModel) {

        return titanEmbeddingModel;
    }
}