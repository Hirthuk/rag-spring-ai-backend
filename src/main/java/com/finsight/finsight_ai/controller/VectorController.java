package com.finsight.finsight_ai.controller;

import com.finsight.finsight_ai.service.UploadService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vectors")
@RequiredArgsConstructor
@Data
@Slf4j
public class VectorController {

    private final UploadService uploadService;
    private final ChromaApi chromaApi;

    @PostMapping("/reload")
    public String reloadVectorDatabase() {
        try {
            // First, try to delete the collection if it exists
            try {
                chromaApi.deleteCollection(
                        "default_tenant",
                        "default_database",
                        "financial-docs"
                );
                return "✅ Collection deleted. Vector store reset successfully.";
            } catch (Exception e) {
                if (e.getMessage().contains("404")) {
                    return "⚠️ Collection didn't exist. Starting fresh.";
                }
                throw e;
            }
        } catch (Exception e) {
            return "Failed to reset vector database: " + e.getMessage();
        }
    }

    @PostMapping("/reset-hard")
    public String hardResetVectorDatabase() {
        try {
            // Try to delete all possible collections
            String[] tenants = {"default_tenant", "", null};
            String[] databases = {"default_database", "", null};
            String[] collections = {"financial-docs", "default", "spring-ai", "vector_store"};

            int deletedCount = 0;
            for (String tenant : tenants) {
                for (String database : databases) {
                    for (String collection : collections) {
                        try {
                            chromaApi.deleteCollection(tenant, database, collection);
                            deletedCount++;
                            log.info("Deleted collection: tenant={}, database={}, collection={}",
                                    tenant, database, collection);
                        } catch (Exception e) {
                            // Ignore 404s
                            if (!e.getMessage().contains("404")) {
                                log.warn("Error deleting: {}", e.getMessage());
                            }
                        }
                    }
                }
            }

            // Restart the application to ensure clean state
            return String.format("✅ Hard reset complete. Deleted %d collections. Please restart the application.", deletedCount);

        } catch (Exception e) {
            return "Failed to hard reset: " + e.getMessage();
        }
    }

    @PostMapping("/collections")
    public Object collections() {

        return chromaApi.listCollections(
                "default_tenant",
                "default_database"
        );
    }
}