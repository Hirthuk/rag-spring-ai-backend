package com.finsight.finsight_ai.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentLoaderService {

    private final VectorStore vectorStore;
    private final ChromaApi chromaApi;

    @Value("${spring.ai.vectorstore.chroma.collection-name:financial-docs}")
    private String collectionName;

    @Value("${spring.ai.vectorstore.chroma.tenant-id:default_tenant}")
    private String tenantId;

    @Value("${spring.ai.vectorstore.chroma.database-id:default_database}")
    private String databaseId;

    @PostConstruct
    public void loadDocuments() {
        log.info("=================================");
        log.info("DOCUMENT LOADER STARTED");
        log.info("=================================");

        try {
            loadAllDocuments();
            log.info("DOCUMENT LOADER FINISHED");
        } catch (Exception e) {
            log.error("Failed while loading documents", e);
        }
    }

    @PostConstruct
    public void test() {
        log.info("DOCUMENT LOADER IS ALIVE");
    }

    /**
     * Loads all documents from the financial-docs folder
     * Only indexes files that haven't been indexed yet
     */
    private void loadAllDocuments() throws Exception {
        File folder = new ClassPathResource("financial-docs").getFile();
        File[] files = folder.listFiles();

        if (files == null || files.length == 0) {
            log.warn("No files found in financial-docs folder");
            return;
        }

        // Get all already indexed file names first (batch check)
        List<String> indexedFileNames = getAllIndexedFileNames();
        log.info("Already indexed files: {}", indexedFileNames);

        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }

            String fileName = file.getName();

            if (indexedFileNames.contains(fileName)) {
                log.info("Skipping already indexed file: {}", fileName);
                continue;
            }

            indexFile(file);
        }
    }

    /**
     * Get all unique file names that are already indexed
     */
    private List<String> getAllIndexedFileNames() {
        try {
            List<String> allFileNames = new ArrayList<>();

            // Search for documents with different queries to get broad coverage
            String[] searchQueries = {"company", "revenue", "profit", "financial", "report"};

            for (String query : searchQueries) {
                List<Document> docs = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(query)
                                .topK(100)
                                .build()
                );

                // Extract fileName from metadata safely
                for (Document doc : docs) {
                    Object fileNameObj = doc.getMetadata().get("fileName");
                    if (fileNameObj != null) {
                        String fileName = fileNameObj.toString();
                        if (!fileName.isEmpty() && !allFileNames.contains(fileName)) {
                            allFileNames.add(fileName);
                        }
                    }
                }
            }

            return allFileNames;

        } catch (Exception e) {
            log.warn("Failed to get indexed file names: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Indexes a single file into the vector store
     */
    private void indexFile(File file) {
        try {
            log.info("Indexing file: {}", file.getName());

            TextReader reader = new TextReader(
                    new ClassPathResource("financial-docs/" + file.getName())
            );

            List<Document> docs = reader.get();

            // Add metadata to each document
            for (Document doc : docs) {
                doc.getMetadata().put("fileName", file.getName());
                doc.getMetadata().put("source", "financial-doc");
                doc.getMetadata().put("fileType", getFileExtension(file.getName()));
                doc.getMetadata().put("indexedAt", String.valueOf(System.currentTimeMillis()));
            }

            // Split documents into smaller chunks
            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(500)
                    .withMinChunkSizeChars(100)
                    .withKeepSeparator(true)
                    .build();

            List<Document> splitDocs = splitter.apply(docs);

            // Add to vector store
            vectorStore.add(splitDocs);

            log.info("Indexed {} chunks from {}", splitDocs.size(), file.getName());
        } catch (Exception e) {
            log.error("Failed indexing file {}: {}", file.getName(), e.getMessage(), e);
        }
    }

    /**
     * Helper method to get file extension
     */
    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf(".");
        if (lastDotIndex > 0) {
            return fileName.substring(lastDotIndex + 1);
        }
        return "unknown";
    }

    /**
     * Debug method to check what's in the vector store
     */
    public void printIndexingStatus() {
        try {
            File folder = new ClassPathResource("financial-docs").getFile();
            File[] files = folder.listFiles();

            if (files == null || files.length == 0) {
                log.info("No files found in financial-docs folder");
                return;
            }

            log.info("=== INDEXING STATUS ===");

            List<String> indexedFiles = getAllIndexedFileNames();

            for (File file : files) {
                if (file.isFile()) {
                    boolean indexed = indexedFiles.contains(file.getName());
                    log.info("{}: {}", file.getName(), indexed ? "INDEXED ✓" : "NOT INDEXED ✗");
                }
            }

            log.info("Total indexed files: {}", indexedFiles.size());
            log.info("======================");

        } catch (Exception e) {
            log.error("Failed to get indexing status: {}", e.getMessage(), e);
        }
    }
}