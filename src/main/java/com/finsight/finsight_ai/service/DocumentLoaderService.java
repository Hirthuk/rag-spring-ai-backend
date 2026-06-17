package com.finsight.finsight_ai.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentLoaderService {

    private final VectorStore vectorStore;
    private final ChromaApi chromaApi;
    private final S3Client s3Client;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(
            ".xlsx", ".xls", ".txt", ".csv", ".json", ".md", ".log", ".xml", ".pdf", ".docx", ".doc"
    );

    private static final int MAX_CHUNK_SIZE = 700;
    private static final int MIN_CHUNK_SIZE = 100;
    private static final int OVERLAP_SIZE = 100;

    private static final String COLLECTION_NAME = "financial-docs";
    private static final String TENANT_NAME = "SpringAiTenant";
    private static final String DATABASE_NAME = "SpringAiDatabase";
    private static final String HASH_TRACKING_FILE = ".document-hashes";

    private final Set<String> processedFileHashes = new HashSet<>();

    @PostConstruct
    public void loadDocuments() {
        log.info("=================================");
        log.info("DOCUMENT LOADER STARTED (source: s3://{})", bucketName);
        log.info("=================================");

        try {
            loadExistingDocumentHashes();

            if (!processedFileHashes.isEmpty() && isVectorStoreEmpty()) {
                log.warn("Tracking file lists {} documents but the vector store is EMPTY — forcing full re-ingest.",
                        processedFileHashes.size());
                processedFileHashes.clear();
                resetHashTrackingFile();
            }

            List<Resource> allResources = listS3Resources();

            if (allResources.isEmpty()) {
                log.warn("No supported files found in s3://{}", bucketName);
                log.info("Supported file types: {}", SUPPORTED_EXTENSIONS);
                return;
            }

            log.info("Found {} file(s) in s3://{}", allResources.size(), bucketName);

            Map<String, Integer> fileTypeCount = new HashMap<>();
            for (Resource resource : allResources) {
                String extension = getFileExtension(resource.getFilename());
                fileTypeCount.put(extension, fileTypeCount.getOrDefault(extension, 0) + 1);
            }
            log.info("File types found: {}", fileTypeCount);

            int successCount = 0;
            int failCount = 0;
            int skipCount = 0;

            for (Resource resource : allResources) {
                String fileName = resource.getFilename();
                try {
                    String fileHash = calculateFileHash(resource);

                    if (processedFileHashes.contains(fileHash)) {
                        log.info("⏭️ Skipping duplicate file: {} (already in Chroma)", fileName);
                        skipCount++;
                        continue;
                    }

                    log.info("Processing file: {} (hash: {})", fileName, fileHash);

                    List<Document> documents = extractDocuments(resource);
                    if (!documents.isEmpty()) {
                        int indexedCount = 0;
                        for (int i = 0; i < documents.size(); i++) {
                            Document doc = documents.get(i);
                            doc.getMetadata().put("fileHash", fileHash);
                            if (addDocumentWithRetry(doc, fileName, i, documents.size())) {
                                indexedCount++;
                            }
                            if (i < documents.size() - 1) {
                                Thread.sleep(500);
                            }
                        }

                        if (indexedCount > 0) {
                            successCount++;
                            processedFileHashes.add(fileHash);
                            saveDocumentHash(fileHash);
                            log.info("✅ Successfully indexed {}/{} documents from {}",
                                    indexedCount, documents.size(), fileName);
                        } else {
                            failCount++;
                            log.error("❌ Failed to index any documents from {}", fileName);
                        }
                    } else {
                        log.warn("No documents extracted from {}", fileName);
                    }
                } catch (Exception e) {
                    failCount++;
                    log.error("Failed to process file: {}", fileName, e);
                }
            }

            log.info("=================================");
            log.info("DOCUMENT LOADER FINISHED");
            log.info("Total: {}, Successful: {}, Failed: {}, Skipped: {}",
                    allResources.size(), successCount, failCount, skipCount);
            log.info("=================================");

        } catch (Exception e) {
            log.error("Error loading documents from s3://{}", bucketName, e);
        }
    }

    /**
     * Lists all objects in the S3 bucket whose extension is supported,
     * downloads each one eagerly, and wraps it in a ByteArrayResource so the
     * existing extraction logic (which calls getInputStream() and
     * getContentAsByteArray()) works without any further changes.
     */
    private List<Resource> listS3Resources() {
        List<Resource> resources = new ArrayList<>();

        String continuationToken = null;
        do {
            var requestBuilder = ListObjectsV2Request.builder().bucket(bucketName);
            if (continuationToken != null) {
                requestBuilder.continuationToken(continuationToken);
            }

            var response = s3Client.listObjectsV2(requestBuilder.build());

            for (S3Object obj : response.contents()) {
                String key = obj.key();
                // Skip S3 "folder" markers (keys ending with /)
                if (key.endsWith("/")) continue;

                String ext = getFileExtension(key);
                if (!SUPPORTED_EXTENSIONS.contains(ext)) {
                    log.debug("Skipping unsupported file type: {}", key);
                    continue;
                }

                try {
                    byte[] bytes = s3Client.getObjectAsBytes(
                            b -> b.bucket(bucketName).key(key)
                    ).asByteArray();

                    // Use the filename part of the key (strips any S3 prefix/folder path)
                    String filename = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
                    resources.add(new NamedByteArrayResource(bytes, filename));
                    log.debug("Downloaded s3://{}/{} ({} bytes)", bucketName, key, bytes.length);
                } catch (Exception e) {
                    log.error("Failed to download s3://{}/{}: {}", bucketName, key, e.getMessage());
                }
            }

            continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
        } while (continuationToken != null);

        return resources;
    }

    /** ByteArrayResource with a real filename — needed because ByteArrayResource.getFilename() returns null. */
    private static class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        NamedByteArrayResource(byte[] bytes, String filename) {
            super(bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }

    // -------------------------------------------------------------------------
    // Everything below is unchanged from the previous implementation
    // -------------------------------------------------------------------------

    private boolean isVectorStoreEmpty() {
        try {
            var results = vectorStore.similaritySearch("financial revenue profit company");
            boolean empty = (results == null || results.isEmpty());
            log.info("Vector store emptiness check: {}", empty ? "EMPTY" : "populated");
            return empty;
        } catch (Exception e) {
            log.warn("Vector store emptiness check failed ({}), treating as empty", e.getMessage());
            return true;
        }
    }

    private void resetHashTrackingFile() {
        try {
            File trackingFile = new File(HASH_TRACKING_FILE);
            if (trackingFile.exists() && trackingFile.delete()) {
                log.info("Deleted stale hash tracking file: {}", HASH_TRACKING_FILE);
            }
        } catch (Exception e) {
            log.warn("Could not delete stale hash tracking file: {}", e.getMessage());
        }
    }

    private void loadExistingDocumentHashes() {
        try {
            File trackingFile = new File(HASH_TRACKING_FILE);
            if (trackingFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(trackingFile))) {
                    String line;
                    int loadedCount = 0;
                    while ((line = reader.readLine()) != null) {
                        if (!line.trim().isEmpty()) {
                            processedFileHashes.add(line.trim());
                            loadedCount++;
                        }
                    }
                    log.info("Loaded {} document hashes from tracking file", loadedCount);
                }
            } else {
                log.info("No existing tracking file found - first time loading documents");
            }
        } catch (Exception e) {
            log.warn("Could not load existing document hashes: {}", e.getMessage());
        }
    }

    private void saveDocumentHash(String hash) {
        try {
            File trackingFile = new File(HASH_TRACKING_FILE);
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(trackingFile, true))) {
                writer.write(hash);
                writer.newLine();
            }
        } catch (Exception e) {
            log.warn("Could not save document hash to tracking file: {}", e.getMessage());
        }
    }

    private String calculateFileHash(Resource resource) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] fileBytes = resource.getContentAsByteArray();
            byte[] hash = md.digest(fileBytes);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.warn("Error calculating file hash for {}: {}", resource.getFilename(), e.getMessage());
            return System.nanoTime() + "_" + resource.getFilename();
        }
    }

    private boolean addDocumentWithRetry(Document doc, String fileName, int chunkIndex, int totalChunks) {
        String originalContent = doc.getText();
        int maxRetries = 3;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    int reductionFactor = attempt + 1;
                    int newLength = originalContent.length() / reductionFactor;
                    if (newLength < MIN_CHUNK_SIZE) {
                        log.warn("Chunk {} of {} would be too small ({} chars), skipping",
                                chunkIndex, fileName, newLength);
                        return false;
                    }
                    int breakPoint = Math.min(newLength, originalContent.length());
                    if (breakPoint < originalContent.length()) {
                        int lastSpace = originalContent.lastIndexOf(' ', breakPoint);
                        int lastNewline = originalContent.lastIndexOf('\n', breakPoint);
                        breakPoint = Math.max(lastSpace, lastNewline);
                        if (breakPoint < MIN_CHUNK_SIZE) breakPoint = newLength;
                    }
                    String truncated = originalContent.substring(0, breakPoint);
                    Document smallerDoc = new Document(truncated, doc.getMetadata());
                    smallerDoc.getMetadata().put("truncated", true);
                    smallerDoc.getMetadata().put("originalLength", originalContent.length());
                    smallerDoc.getMetadata().put("truncatedLength", truncated.length());
                    smallerDoc.getMetadata().put("retryAttempt", attempt);
                    smallerDoc = validateAndCleanDocument(smallerDoc);
                    if (smallerDoc == null) {
                        log.warn("Cleaned document became empty for chunk {} of {}", chunkIndex, fileName);
                        continue;
                    }
                    vectorStore.add(List.of(smallerDoc));
                } else {
                    Document cleanedDoc = validateAndCleanDocument(doc);
                    if (cleanedDoc == null) {
                        log.warn("Document chunk {} of {} is invalid after cleaning, skipping", chunkIndex, fileName);
                        return false;
                    }
                    vectorStore.add(List.of(cleanedDoc));
                    log.info("✅ Indexed document from {} chunk {}/{}", fileName, chunkIndex + 1, totalChunks);
                }
                return true;
            } catch (Exception e) {
                log.warn("Attempt {} failed for chunk {}/{} of {}: {}",
                        attempt + 1, chunkIndex + 1, totalChunks, fileName, e.getMessage());
                if (attempt == maxRetries - 1) {
                    log.error("Failed to index chunk {}/{} of {} after {} attempts",
                            chunkIndex + 1, totalChunks, fileName, maxRetries);
                } else {
                    try {
                        Thread.sleep(TimeUnit.SECONDS.toMillis(1L << attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return false;
    }

    private Document validateAndCleanDocument(Document doc) {
        String content = doc.getText();
        if (content == null || content.trim().isEmpty()) {
            log.warn("Document has empty content, skipping");
            return null;
        }
        if (content.trim().length() < 3) {
            log.warn("Document too short ({} chars), may cause embedding issues", content.length());
        }
        String cleaned = cleanTextForCohere(content);
        if (cleaned.length() < content.length()) {
            log.debug("Cleaned document: removed {} characters", content.length() - cleaned.length());
        }
        if (cleaned.trim().isEmpty()) {
            log.warn("Document became empty after cleaning, skipping");
            return null;
        }
        Document cleanedDoc = new Document(cleaned, new HashMap<>(doc.getMetadata()));
        cleanedDoc.getMetadata().put("originalLength", content.length());
        cleanedDoc.getMetadata().put("cleanedLength", cleaned.length());
        return cleanedDoc;
    }

    private String cleanTextForCohere(String text) {
        String cleaned = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        cleaned = cleaned.replaceAll("\\s+", " ");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");
        cleaned = cleaned.replaceAll("[\\uFFFD\\uFFFF]", "");
        cleaned = cleaned.trim();
        if (!cleaned.isEmpty() && cleaned.length() > 10 && !cleaned.matches(".*[.!?]$")) {
            cleaned = cleaned + ".";
        }
        if (cleaned.length() > MAX_CHUNK_SIZE) {
            cleaned = cleaned.substring(0, MAX_CHUNK_SIZE);
            int lastPeriod = cleaned.lastIndexOf('.');
            int lastNewline = cleaned.lastIndexOf('\n');
            int breakPoint = Math.max(lastPeriod, lastNewline);
            if (breakPoint > MAX_CHUNK_SIZE / 2) {
                cleaned = cleaned.substring(0, breakPoint + 1);
            }
        }
        return cleaned;
    }

    private List<Document> extractDocuments(Resource resource) throws Exception {
        String fileName = resource.getFilename();
        String extension = getFileExtension(fileName);
        return switch (extension.toLowerCase()) {
            case ".xlsx", ".xls" -> extractFromExcel(resource);
            case ".txt", ".csv", ".json", ".md", ".log", ".xml" -> extractFromTextFile(resource);
            default -> {
                log.warn("Unsupported file type: {} for file {}", extension, fileName);
                yield new ArrayList<>();
            }
        };
    }

    private List<Document> extractFromTextFile(Resource resource) throws Exception {
        List<Document> documents = new ArrayList<>();
        String fileName = resource.getFilename();

        try (InputStream inputStream = resource.getInputStream();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            StringBuilder content = new StringBuilder();
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
                lineCount++;
            }

            String text = content.toString().trim();
            if (text.isEmpty()) {
                log.warn("Text file is empty: {}", fileName);
                return documents;
            }

            if (text.length() <= MAX_CHUNK_SIZE) {
                Map<String, Object> metadata = createMetadata(resource, "text");
                metadata.put("lineCount", lineCount);
                metadata.put("characterCount", text.length());
                Document doc = new Document(text, metadata);
                doc = validateAndCleanDocument(doc);
                if (doc != null) documents.add(doc);
            } else {
                documents = splitTextIntoSafeChunks(text, resource, fileName, lineCount);
                log.info("Text file {} split into {} chunks", fileName, documents.size());
            }
        }
        return documents;
    }

    private List<Document> splitTextIntoSafeChunks(String text, Resource resource,
                                                    String fileName, int lineCount) {
        List<Document> documents = new ArrayList<>();
        int startIndex = 0;
        int chunkNumber = 0;

        while (startIndex < text.length()) {
            int endIndex = Math.min(startIndex + MAX_CHUNK_SIZE, text.length());
            if (endIndex < text.length()) {
                int lastNewline = text.lastIndexOf('\n', endIndex);
                int lastSpace = text.lastIndexOf(' ', endIndex);
                int lastPeriod = text.lastIndexOf('.', endIndex);
                int breakPoint = Math.max(lastPeriod, Math.max(lastNewline, lastSpace));
                if (breakPoint > startIndex + MAX_CHUNK_SIZE / 2) {
                    endIndex = breakPoint + 1;
                }
            }

            String chunk = text.substring(startIndex, Math.min(endIndex, text.length()));
            if (chunk.length() > MAX_CHUNK_SIZE) chunk = chunk.substring(0, MAX_CHUNK_SIZE);

            if (!chunk.trim().isEmpty()) {
                Map<String, Object> metadata = createMetadata(resource, "text");
                metadata.put("chunkIndex", chunkNumber);
                metadata.put("totalChunks", -1);
                metadata.put("chunkSize", chunk.length());
                metadata.put("lineCount", lineCount);
                Document doc = new Document(chunk, metadata);
                doc = validateAndCleanDocument(doc);
                if (doc != null) documents.add(doc);
            }

            chunkNumber++;
            startIndex = endIndex;
            if (startIndex < text.length()) {
                startIndex = Math.max(startIndex - OVERLAP_SIZE, startIndex);
            }
        }

        int totalChunks = documents.size();
        for (Document doc : documents) {
            doc.getMetadata().put("totalChunks", totalChunks);
        }
        return documents;
    }

    private List<Document> extractFromExcel(Resource resource) throws Exception {
        List<Document> documents = new ArrayList<>();
        String fileName = resource.getFilename();

        try (InputStream inputStream = resource.getInputStream();
             Workbook workbook = WorkbookFactory.create(inputStream)) {

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                Iterator<Row> rows = sheet.iterator();
                if (!rows.hasNext()) continue;

                Row headerRow = rows.next();
                int rowCount = 0;

                while (rows.hasNext()) {
                    Row row = rows.next();
                    rowCount++;
                    if (row.getPhysicalNumberOfCells() < 2) continue;

                    try {
                        String companyName = getCellValue(row.getCell(0));
                        if (companyName == null || companyName.trim().isEmpty()) continue;

                        String companyText = buildCompanyDocument(row);
                        if (companyText.length() > MAX_CHUNK_SIZE) {
                            companyText = companyText.substring(0, MAX_CHUNK_SIZE);
                        }

                        Map<String, Object> metadata = createMetadata(resource, "excel");
                        metadata.put("sheetName", sheet.getSheetName());
                        metadata.put("sheetIndex", sheetIndex);
                        metadata.put("rowNumber", rowCount);
                        metadata.put("companyName", companyName);
                        metadata.put("ticker", getCellValue(row.getCell(1)));
                        metadata.put("sector", getCellValue(row.getCell(2)));
                        metadata.put("fiscalYear", getCellValue(row.getCell(3)));

                        Document doc = new Document(companyText, metadata);
                        doc = validateAndCleanDocument(doc);
                        if (doc != null) documents.add(doc);
                    } catch (Exception e) {
                        log.error("Error processing row {} in sheet {} of {}", rowCount, sheetIndex, fileName, e);
                    }
                }
                log.info("Processed {} rows from sheet '{}' in {}", rowCount, sheet.getSheetName(), fileName);
            }
        }
        return documents;
    }

    private String buildCompanyDocument(Row row) {
        String company = getCellValue(row.getCell(0));
        String ticker = getCellValue(row.getCell(1));
        String sector = getCellValue(row.getCell(2));
        String fiscalYear = getCellValue(row.getCell(3));
        String revenue = getCellValue(row.getCell(4));
        String cogs = getCellValue(row.getCell(5));
        String operatingExpenses = getCellValue(row.getCell(6));
        String ebitda = getCellValue(row.getCell(7));
        String netProfit = getCellValue(row.getCell(8));
        String profitMargin = getCellValue(row.getCell(9));
        String eps = getCellValue(row.getCell(10));

        return String.format("""
            Company: %s | Ticker: %s | Sector: %s | FY: %s
            Revenue: %sM | COGS: %sM | OpEx: %sM | EBITDA: %sM
            Net Profit: %sM | Margin: %s%% | EPS: %s
            Summary: %s reported revenue of %sM and net profit of %sM (margin %s%%) in FY%s.
            """,
                company, ticker, sector, fiscalYear,
                revenue, cogs, operatingExpenses, ebitda,
                netProfit, profitMargin, eps,
                company, revenue, netProfit, profitMargin, fiscalYear
        ).trim();
    }

    private Map<String, Object> createMetadata(Resource resource, String fileType) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fileName", resource.getFilename());
        metadata.put("source", "s3://" + bucketName);
        metadata.put("fileType", fileType);
        metadata.put("timestamp", System.currentTimeMillis());
        try {
            metadata.put("fileSize", resource.contentLength());
        } catch (Exception e) {
            metadata.put("fileSize", "unknown");
        }
        return metadata;
    }

    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        try {
            return switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue().trim();
                case NUMERIC -> {
                    double value = cell.getNumericCellValue();
                    yield value == Math.floor(value) ?
                            String.valueOf((long) value) :
                            String.format("%.2f", value);
                }
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                case FORMULA -> {
                    try {
                        yield cell.getStringCellValue().trim();
                    } catch (Exception e) {
                        double numericValue = cell.getNumericCellValue();
                        yield numericValue == Math.floor(numericValue) ?
                                String.valueOf((long) numericValue) :
                                String.format("%.2f", numericValue);
                    }
                }
                default -> "";
            };
        } catch (Exception e) {
            log.warn("Failed to get cell value: {}", e.getMessage());
            return "";
        }
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "";
        return fileName.substring(fileName.lastIndexOf(".")).toLowerCase();
    }

    public void reloadDocuments() {
        log.info("Manual reload triggered - clearing cache and reloading all documents from s3://{}", bucketName);
        processedFileHashes.clear();
        try {
            File trackingFile = new File(HASH_TRACKING_FILE);
            if (trackingFile.exists()) {
                trackingFile.delete();
                log.info("Deleted tracking file");
            }
        } catch (Exception e) {
            log.warn("Could not delete tracking file: {}", e.getMessage());
        }
        loadDocuments();
    }
}
