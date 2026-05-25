package com.finsight.finsight_ai.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DocumentLoaderService {

    private final VectorStore vectorStore;

    @PostConstruct
    public void LoadDocuments() {
        TextReader revenueReader = new TextReader(new ClassPathResource("financial-docs/revenue.txt"));
        TextReader profitReader = new TextReader(new ClassPathResource("financial-docs/profit.txt"));

        List<Document> revenueDocs = revenueReader.get();
        List<Document> profitDocs = profitReader.get();

        // ✅ Works in both 1.x and 2.0
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(800)           // Target token count per chunk
                .withMinChunkSizeChars(350)   // Minimum characters before cutting
                .withMinChunkLengthToEmbed(5) // Discard chunks smaller than this
                .withMaxNumChunks(10000)      // Max chunks per document
                .withKeepSeparator(true)      // Preserve separators like newlines
                .build();

        List<Document> splitDocs = splitter.apply(revenueDocs);
        splitDocs.addAll(splitter.apply(profitDocs));
        vectorStore.add(splitDocs);

        System.out.println("Documents Loaded Successfully");


    }
}
