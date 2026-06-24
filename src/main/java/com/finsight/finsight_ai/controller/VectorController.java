package com.finsight.finsight_ai.controller;

import com.finsight.finsight_ai.service.DocumentLoaderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin-only operations for the vector store.
 * All endpoints require a valid Bearer token (enforced by Spring Security).
 */
@RestController
@RequestMapping("/api/admin/vectors")
@RequiredArgsConstructor
@Slf4j
public class VectorController {

    private final ChromaApi chromaApi;
    private final DocumentLoaderService documentLoaderService;

    private static final String TENANT = "SpringAiTenant";
    private static final String DATABASE = "SpringAiDatabase";
    private static final String COLLECTION = "financial-docs";

    /**
     * Wipes the ChromaDB collection and re-indexes all base documents from S3.
     * Use this to clean up duplicates or refresh the document set.
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, String>> reload(@AuthenticationPrincipal Jwt jwt) {
        log.info("Vector store reload requested by user: {}", jwt.getSubject());
        try {
            // Wipe existing collection
            try {
                chromaApi.deleteCollection(TENANT, DATABASE, COLLECTION);
                log.info("Deleted ChromaDB collection '{}'", COLLECTION);
            } catch (Exception e) {
                log.info("Collection '{}' not found, proceeding with fresh create", COLLECTION);
            }

            // Recreate collection so vectorStore.add() works immediately
            chromaApi.createCollection(TENANT, DATABASE, new ChromaApi.CreateCollectionRequest(COLLECTION));
            log.info("Recreated ChromaDB collection '{}'", COLLECTION);

            // Clear tracking file and re-index from S3
            documentLoaderService.reloadDocuments();

            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Collection wiped and re-indexed from S3"
            ));
        } catch (Exception e) {
            log.error("Vector store reload failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", e.getMessage()
            ));
        }
    }

    /** Returns the list of collections — useful to verify state. */
    @GetMapping("/collections")
    public ResponseEntity<?> collections(@AuthenticationPrincipal Jwt jwt) {
        log.info("Collection list requested by user: {}", jwt.getSubject());
        return ResponseEntity.ok(chromaApi.listCollections(TENANT, DATABASE));
    }
}
