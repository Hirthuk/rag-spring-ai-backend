package com.finsight.finsight_ai.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

@Service
//@ConditionalOnBean(VectorStore.class)
@RequiredArgsConstructor
@Slf4j
public class DocumentLoaderService {

    private final VectorStore vectorStore;

    @PostConstruct
    public void loadDocuments() {

        log.info("=================================");
        log.info("DOCUMENT LOADER STARTED");
        log.info("=================================");

        try {

            loadDefaultDocuments();

            log.info("DOCUMENT LOADER FINISHED");

        } catch (Exception e) {

            log.error(
                    "Failed while loading default documents",
                    e
            );
        }
    }

        @PostConstruct
        public void test() {

            System.out.println("================================");
            System.out.println("DOCUMENT LOADER IS ALIVE");
            System.out.println("================================");

            log.info("DOCUMENT LOADER IS ALIVE");
        }

    private void loadDefaultDocuments() {

        log.info("Loading financial documents");

        TextReader reportReader =
                new TextReader(
                        new ClassPathResource(
                                "financial-docs/company-report.txt"
                        )
                );

        List<Document> docs =
                reportReader.get();

        docs.forEach(doc -> {

            doc.getMetadata().put(
                    "source",
                    "financial-report"
            );

            doc.getMetadata().put(
                    "company",
                    "FinSight Technologies"
            );

            doc.getMetadata().put(
                    "fileName",
                    "company-report.txt"
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
                splitter.apply(docs);

        log.info(
                "Generated {} chunks",
                splitDocs.size()
        );

        splitDocs.forEach(doc -> {

            log.info(
                    "Chunk Metadata = {}",
                    doc.getMetadata()
            );

            log.info(
                    "Chunk Size = {}",
                    doc.getText().length()
            );
        });

        vectorStore.add(splitDocs);

        log.info(
                "Successfully inserted {} chunks into vector store",
                splitDocs.size()
        );

        List<Document> verification =
                vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query("FY2024 revenue")
                                .topK(3)
                                .build()
                );

        log.info(
                "Verification Search Returned {} Results",
                verification.size()
        );
    }
}