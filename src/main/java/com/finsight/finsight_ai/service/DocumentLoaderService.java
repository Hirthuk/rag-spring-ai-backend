package com.finsight.finsight_ai.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentLoaderService {

    private final VectorStore vectorStore;

    private static final String MARKER_FILE =
            "data/default-documents-loaded.flag";

    @PostConstruct
    public void loadDocuments() {

        try {

            File markerFile =
                    new File(MARKER_FILE);

            if (markerFile.exists()) {

                log.info(
                        "Default financial documents already loaded. Skipping startup load."
                );

                return;
            }

            loadDefaultDocuments();
            verifyCollection();

            markerFile.getParentFile().mkdirs();

            markerFile.createNewFile();

            log.info(
                    "Created startup marker file: {}",
                    MARKER_FILE
            );

        } catch (Exception e) {

            log.error(
                    "Failed while loading default documents",
                    e
            );
        }
    }

    private void loadDefaultDocuments() {

        log.info(
                "Loading default financial documents..."
        );

        TextReader revenueReader =
                new TextReader(
                        new ClassPathResource(
                                "financial-docs/revenue.txt"
                        )
                );

        TextReader profitReader =
                new TextReader(
                        new ClassPathResource(
                                "financial-docs/profit.txt"
                        )
                );

        List<Document> revenueDocs =
                revenueReader.get();

        List<Document> profitDocs =
                profitReader.get();

        /*
         * Add metadata
         */
        revenueDocs.forEach(doc -> {

            doc.getMetadata().put(
                    "source",
                    "default"
            );

            doc.getMetadata().put(
                    "fileName",
                    "revenue.txt"
            );
        });

        profitDocs.forEach(doc -> {

            doc.getMetadata().put(
                    "source",
                    "default"
            );

            doc.getMetadata().put(
                    "fileName",
                    "profit.txt"
            );
        });

        TokenTextSplitter splitter =
                TokenTextSplitter.builder()
                        .withChunkSize(800)
                        .withMinChunkSizeChars(350)
                        .withMinChunkLengthToEmbed(5)
                        .withMaxNumChunks(10000)
                        .withKeepSeparator(true)
                        .build();

        List<Document> splitDocs =
                splitter.apply(revenueDocs);

        splitDocs.addAll(
                splitter.apply(profitDocs)
        );

        vectorStore.add(splitDocs);

        log.info(
                "Default financial documents loaded successfully. Chunks added: {}",
                splitDocs.size()
        );
    }

    @PostConstruct
    public void verifyCollection() {

        log.info(
                "Collection Name: {}",
                "financial-docs"
        );
    }
}