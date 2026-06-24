package com.finsight.finsight_ai.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Manual Chroma wiring that replaces Spring AI's ChromaVectorStoreAutoConfiguration.
 *
 * Why excluded: Spring AI 1.0.1's ChromaVectorStore.afterPropertiesSet() calls
 * chromaApi.getCollection() which THROWS HttpClientErrorException$NotFound (not returns null)
 * when the collection is absent. The initializeSchema branch is dead code — it checks for null
 * return which never happens. On a fresh ChromaDB sidecar (ECS) startup crashes with a 404.
 *
 * Fix: pre-create tenant/database/collection via ChromaApi before constructing
 * ChromaVectorStore, so getCollection() succeeds in afterPropertiesSet().
 */
@Configuration
@Slf4j
public class ChromaVectorStoreConfig {

    @Value("${spring.ai.vectorstore.chroma.host:localhost}")
    private String chromaHost;

    @Value("${spring.ai.vectorstore.chroma.port:8000}")
    private int chromaPort;

    @Value("${spring.ai.vectorstore.chroma.collection-name:financial-docs}")
    private String collectionName;

    @Value("${spring.ai.vectorstore.chroma.tenant-id:default_tenant}")
    private String tenantId;

    @Value("${spring.ai.vectorstore.chroma.database-id:default_database}")
    private String databaseId;

    @Bean
    public ChromaApi chromaApi(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        String baseUrl = "http://" + chromaHost + ":" + chromaPort;
        log.info("Creating ChromaApi — baseUrl={}", baseUrl);
        return new ChromaApi(baseUrl, restClientBuilder, objectMapper);
    }

    @Bean
    public VectorStore vectorStore(ChromaApi chromaApi, EmbeddingModel embeddingModel) {
        ensureChromaReady(chromaApi);
        log.info("Creating ChromaVectorStore — collection={}, tenant={}, database={}",
                collectionName, tenantId, databaseId);
        return ChromaVectorStore.builder(chromaApi, embeddingModel)
                .collectionName(collectionName)
                .tenantName(tenantId)
                .databaseName(databaseId)
                .initializeSchema(true)
                .build();
    }

    /**
     * Pre-creates the tenant → database → collection so that ChromaVectorStore.afterPropertiesSet()
     * finds an existing collection via getCollection() instead of getting a 404.
     *
     * Spring AI 1.0.1 bug: getCollection() throws on 404 instead of returning null, making the
     * initializeSchema code path unreachable. Pre-creating here is the workaround.
     */
    private void ensureChromaReady(ChromaApi chromaApi) {
        // Tenant
        try {
            chromaApi.getTenant(tenantId);
            log.debug("Chroma tenant '{}' already exists", tenantId);
        } catch (Exception e) {
            try {
                chromaApi.createTenant(tenantId);
                log.info("Created Chroma tenant '{}'", tenantId);
            } catch (Exception ce) {
                log.debug("Chroma tenant '{}' creation skipped ({})", tenantId, ce.getMessage());
            }
        }

        // Database
        try {
            chromaApi.getDatabase(tenantId, databaseId);
            log.debug("Chroma database '{}' already exists", databaseId);
        } catch (Exception e) {
            try {
                chromaApi.createDatabase(tenantId, databaseId);
                log.info("Created Chroma database '{}'", databaseId);
            } catch (Exception ce) {
                log.debug("Chroma database '{}' creation skipped ({})", databaseId, ce.getMessage());
            }
        }

        // Collection
        try {
            chromaApi.getCollection(tenantId, databaseId, collectionName);
            log.debug("Chroma collection '{}' already exists", collectionName);
        } catch (Exception e) {
            try {
                chromaApi.createCollection(tenantId, databaseId,
                        new ChromaApi.CreateCollectionRequest(collectionName));
                log.info("Created Chroma collection '{}'", collectionName);
            } catch (Exception ce) {
                log.debug("Chroma collection '{}' creation skipped ({})", collectionName, ce.getMessage());
            }
        }
    }
}
