package com.finsight.finsight_ai.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.bedrock.cohere.BedrockCohereEmbeddingModel;
import org.springframework.ai.bedrock.cohere.BedrockCohereEmbeddingOptions;
import org.springframework.ai.bedrock.cohere.api.CohereEmbeddingBedrockApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicitly creates the BedrockCohereEmbeddingModel with input_type=SEARCH_DOCUMENT.
 *
 * cohere.embed-english-v3 on Bedrock REQUIRES input_type in every request — omitting it
 * causes "Invalid parameter combination" (ValidationException 400).
 *
 * The Spring AI autoconfiguration's cohereEmbeddingModel method is annotated with
 * @ConditionalOnMissingBean, so providing our own bean here suppresses the
 * autoconfigured one and guarantees the correct options are applied.
 */
@Configuration
@Slf4j
public class BedrockCohereEmbeddingConfig {

    @Bean
    public BedrockCohereEmbeddingModel cohereEmbeddingModel(CohereEmbeddingBedrockApi cohereEmbeddingApi) {
        log.info("Creating BedrockCohereEmbeddingModel with inputType=SEARCH_DOCUMENT");
        // truncate=END: Cohere silently discards tokens beyond its 512-token limit.
        // With NONE, Cohere throws an error when a chunk exceeds 512 Cohere tokens.
        // Our splitter uses CL100K (OpenAI tokenizer); for technical/number-dense PDFs
        // one CL100K chunk can exceed 512 Cohere tokens, causing "Invalid parameter
        // combination" (Bedrock wraps Cohere's "too long" error as a ValidationException).
        return new BedrockCohereEmbeddingModel(cohereEmbeddingApi,
                BedrockCohereEmbeddingOptions.builder()
                        .inputType(CohereEmbeddingBedrockApi.CohereEmbeddingRequest.InputType.SEARCH_DOCUMENT)
                        .truncate(CohereEmbeddingBedrockApi.CohereEmbeddingRequest.Truncate.END)
                        .build());
    }
}
