package com.finsight.finsight_ai.service;

import com.finsight.finsight_ai.service.s3.S3UploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Manages the lifecycle of user-uploaded documents in the vector store.
 *
 * On login  → loadUserDocuments()  : pulls the user's files from
 *   s3://<bucket>/User_Upload_Documents/<email>/ and indexes them into Chroma
 *   with the userEmail metadata tag so retrieval can scope results per user.
 *
 * On logout → removeUserDocuments(): deletes every Chroma embedding whose
 *   userEmail metadata matches the signed-out user's email.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserDocumentService {

    private final S3Client s3Client;
    private final UploadService uploadService;
    private final ChromaApi chromaApi;
    private final FinancialAssistantService financialAssistantService;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    private static final String COLLECTION_NAME = "financial-docs";
    private static final String TENANT_NAME = "SpringAiTenant";
    private static final String DATABASE_NAME = "SpringAiDatabase";

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(
            ".xlsx", ".xls", ".txt", ".csv", ".json", ".md", ".log", ".xml",
            ".pdf", ".docx", ".doc", ".jpg", ".jpeg", ".png", ".gif", ".bmp",
            ".tiff", ".webp", ".html", ".htm"
    );

    /**
     * Asynchronously loads all documents uploaded by the user from S3 into the
     * vector store. Purges any previously indexed documents for this user first
     * so that repeated logins never create duplicates.
     *
     * @param userId the JWT subject (sub) — stable unique identifier for this user
     */
    @Async
    public void loadUserDocuments(String userId) {
        if (userId == null || userId.isBlank()) return;

        // Remove stale embeddings from previous sessions before re-indexing.
        removeUserDocuments(userId);

        String prefix = S3UploadService.USER_UPLOADS_PREFIX + userId + "/";
        log.info("Loading user documents for userId={} from s3://{}/{}", userId, bucketName, prefix);

        int loaded = 0;
        int failed = 0;

        try {
            String continuationToken = null;
            do {
                var requestBuilder = ListObjectsV2Request.builder()
                        .bucket(bucketName)
                        .prefix(prefix);
                if (continuationToken != null) requestBuilder.continuationToken(continuationToken);

                var response = s3Client.listObjectsV2(requestBuilder.build());

                for (S3Object obj : response.contents()) {
                    String key = obj.key();
                    if (key.endsWith("/")) continue;

                    String ext = getFileExtension(key);
                    if (!SUPPORTED_EXTENSIONS.contains(ext)) {
                        log.debug("Skipping unsupported type: {}", key);
                        continue;
                    }

                    try {
                        byte[] bytes = s3Client.getObjectAsBytes(b -> b.bucket(bucketName).key(key)).asByteArray();
                        String filename = key.substring(key.lastIndexOf('/') + 1);
                        String contentType = guessContentType(ext);

                        log.info("Re-indexing {} ({} bytes) for userId={}", filename, bytes.length, userId);
                        uploadService.indexDocumentFile(new SimpleMultipartFile(bytes, filename, contentType), userId);
                        loaded++;
                    } catch (Exception e) {
                        log.error("Failed to reload {} for userId={}: {}", key, userId, e.getMessage());
                        failed++;
                    }
                }

                continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
            } while (continuationToken != null);

        } catch (Exception e) {
            log.error("Failed to list S3 objects for userId={}: {}", userId, e.getMessage(), e);
        }

        log.info("User document load complete for userId={}: {} loaded, {} failed", userId, loaded, failed);
        financialAssistantService.invalidateUserCache(userId);
    }

    /**
     * Removes all Chroma embeddings that were indexed for the given user.
     * Called on logout so the user's private documents leave the shared vector store.
     *
     * deleteEmbeddings requires the collection's internal UUID, not its name.
     * We resolve the UUID via getCollection() first.
     *
     * @param userId the JWT subject (sub) stored in the "userEmail" metadata field
     */
    public void removeUserDocuments(String userId) {
        if (userId == null || userId.isBlank()) return;

        try {
            // deleteEmbeddings expects the collection UUID, not the human-readable name.
            ChromaApi.Collection collection = chromaApi.getCollection(TENANT_NAME, DATABASE_NAME, COLLECTION_NAME);
            String collectionId = collection.id();

            Map<String, Object> where = Map.of("userEmail", Map.of("$eq", userId));
            int deleted = chromaApi.deleteEmbeddings(
                    TENANT_NAME, DATABASE_NAME, collectionId,
                    new ChromaApi.DeleteEmbeddingsRequest(null, where)
            );
            log.info("Removed {} embeddings for userId={} from vector store", deleted, userId);
            financialAssistantService.invalidateUserCache(userId);
        } catch (Exception e) {
            log.error("Failed to remove documents for userId={}: {}", userId, e.getMessage(), e);
        }
    }

    private String getFileExtension(String key) {
        int dot = key.lastIndexOf('.');
        if (dot < 0) return "";
        return key.substring(dot).toLowerCase();
    }

    private String guessContentType(String ext) {
        return switch (ext) {
            case ".pdf"  -> "application/pdf";
            case ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".xls"  -> "application/vnd.ms-excel";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".doc"  -> "application/msword";
            case ".csv"  -> "text/csv";
            case ".json" -> "application/json";
            case ".txt", ".md", ".log", ".xml" -> "text/plain";
            case ".html", ".htm" -> "text/html";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".png"  -> "image/png";
            case ".gif"  -> "image/gif";
            case ".bmp"  -> "image/bmp";
            case ".tiff" -> "image/tiff";
            case ".webp" -> "image/webp";
            default      -> "application/octet-stream";
        };
    }

    /** Minimal MultipartFile backed by a byte array — needed to call UploadService. */
    private static final class SimpleMultipartFile implements MultipartFile {
        private final byte[] bytes;
        private final String name;
        private final String contentType;

        SimpleMultipartFile(byte[] bytes, String name, String contentType) {
            this.bytes = bytes;
            this.name = name != null ? name : "file";
            this.contentType = contentType;
        }

        @Override public String getName()             { return name; }
        @Override public String getOriginalFilename() { return name; }
        @Override public String getContentType()      { return contentType; }
        @Override public boolean isEmpty()            { return bytes.length == 0; }
        @Override public long getSize()               { return bytes.length; }
        @Override public byte[] getBytes()            { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }

        @Override
        public void transferTo(File dest) throws IOException {
            java.nio.file.Files.write(dest.toPath(), bytes);
        }

        @Override
        public void transferTo(Path dest) throws IOException {
            java.nio.file.Files.write(dest, bytes);
        }
    }
}
