package com.finsight.finsight_ai.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
    private final PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(
            ".xlsx", ".xls", ".txt", ".csv", ".json", ".md", ".log", ".xml", ".pdf", ".docx", ".doc"
    );

    // Conservative chunk sizes for Bedrock Cohere embedding model
    private static final int MAX_CHUNK_SIZE = 1500;  // Reduced from 2000 to be safer
    private static final int MIN_CHUNK_SIZE = 100;    // Minimum meaningful chunk size
    private static final int OVERLAP_SIZE = 100;      // Overlap between chunks

    @PostConstruct
    public void loadDocuments() {
        log.info("=================================");
        log.info("DOCUMENT LOADER STARTED");
        log.info("=================================");

        try {
            List<Resource> allResources = new ArrayList<>();

            for (String extension : SUPPORTED_EXTENSIONS) {
                try {
                    Resource[] resources = resourceResolver.getResources(
                            "classpath:financial-docs/*" + extension
                    );
                    for (Resource r : resources) {
                        allResources.add(r);
                    }
                } catch (Exception e) {
                    log.debug("No files found with extension: {}", extension);
                }
            }

            if (allResources.isEmpty()) {
                log.warn("No supported files found in src/main/resources/financial-docs/");
                log.info("Supported file types: {}", SUPPORTED_EXTENSIONS);
                return;
            }

            log.info("Found {} file(s) in financial-docs folder", allResources.size());

            Map<String, Integer> fileTypeCount = new HashMap<>();
            for (Resource resource : allResources) {
                String extension = getFileExtension(resource.getFilename());
                fileTypeCount.put(extension, fileTypeCount.getOrDefault(extension, 0) + 1);
            }
            log.info("File types found: {}", fileTypeCount);

            int successCount = 0;
            int failCount = 0;

            for (Resource resource : allResources) {
                String fileName = resource.getFilename();
                log.info("Processing file: {}", fileName);

                try {
                    List<Document> documents = extractDocuments(resource);
                    if (!documents.isEmpty()) {
                        // Add documents one by one for better error handling
                        int indexedCount = 0;
                        for (int i = 0; i < documents.size(); i++) {
                            Document doc = documents.get(i);
                            if (addDocumentWithRetry(doc, fileName, i, documents.size())) {
                                indexedCount++;
                            }
                            // Small delay between documents to avoid rate limiting
                            if (i < documents.size() - 1) {
                                Thread.sleep(500);
                            }
                        }

                        if (indexedCount > 0) {
                            successCount++;
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
            log.info("Total files: {}, Successful: {}, Failed: {}",
                    allResources.size(), successCount, failCount);
            log.info("=================================");

        } catch (Exception e) {
            log.error("Error loading documents from resources", e);
        }
    }

    /**
     * Add document with retry logic and progressive chunk reduction
     */
    private boolean addDocumentWithRetry(Document doc, String fileName, int chunkIndex, int totalChunks) {
        String originalContent = doc.getText();
        int maxRetries = 3;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    // On retry, try a progressively smaller chunk
                    int reductionFactor = attempt + 1;
                    int newLength = originalContent.length() / reductionFactor;

                    if (newLength < MIN_CHUNK_SIZE) {
                        log.warn("Chunk {} of {} would be too small ({} chars), skipping",
                                chunkIndex, fileName, newLength);
                        return false;
                    }

                    // Find a good breaking point
                    int breakPoint = Math.min(newLength, originalContent.length());
                    if (breakPoint < originalContent.length()) {
                        int lastSpace = originalContent.lastIndexOf(' ', breakPoint);
                        int lastNewline = originalContent.lastIndexOf('\n', breakPoint);
                        breakPoint = Math.max(lastSpace, lastNewline);
                        if (breakPoint < MIN_CHUNK_SIZE) {
                            breakPoint = newLength;
                        }
                    }

                    String truncated = originalContent.substring(0, breakPoint);
                    Document smallerDoc = new Document(truncated, doc.getMetadata());
                    smallerDoc.getMetadata().put("truncated", true);
                    smallerDoc.getMetadata().put("originalLength", originalContent.length());
                    smallerDoc.getMetadata().put("truncatedLength", truncated.length());
                    smallerDoc.getMetadata().put("retryAttempt", attempt);

                    // Clean the truncated document
                    smallerDoc = validateAndCleanDocument(smallerDoc);
                    if (smallerDoc == null) {
                        log.warn("Cleaned document became empty for chunk {} of {}", chunkIndex, fileName);
                        continue;
                    }

                    vectorStore.add(List.of(smallerDoc));
                    log.info("✅ Indexed truncated document ({} chars from {} original) from {} chunk {}/{} on attempt {}",
                            truncated.length(), originalContent.length(), fileName, chunkIndex + 1, totalChunks, attempt + 1);
                } else {
                    // First attempt: validate and clean the document
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
                    // Exponential backoff before retry
                    try {
                        long delay = TimeUnit.SECONDS.toMillis(1L << attempt); // 1, 2, 4 seconds
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Validate and clean document content for Cohere embedding model
     */
    private Document validateAndCleanDocument(Document doc) {
        String content = doc.getText();

        // Check if content is null or empty
        if (content == null || content.trim().isEmpty()) {
            log.warn("Document has empty content, skipping");
            return null;
        }

        // Check minimum length (Cohere typically needs at least 1 token)
        if (content.trim().length() < 3) {
            log.warn("Document too short ({} chars), may cause embedding issues", content.length());
        }

        // Clean the content
        String cleaned = cleanTextForCohere(content);

        if (cleaned.length() < content.length()) {
            log.debug("Cleaned document: removed {} characters", content.length() - cleaned.length());
        }

        // Check if cleaning removed everything
        if (cleaned.trim().isEmpty()) {
            log.warn("Document became empty after cleaning, skipping");
            return null;
        }

        // Create new document with cleaned content
        Document cleanedDoc = new Document(cleaned, new HashMap<>(doc.getMetadata()));
        cleanedDoc.getMetadata().put("originalLength", content.length());
        cleanedDoc.getMetadata().put("cleanedLength", cleaned.length());

        return cleanedDoc;
    }

    /**
     * Clean text for Cohere embedding model compatibility
     */
    private String cleanTextForCohere(String text) {
        // Remove control characters (except newlines, tabs, and carriage returns)
        String cleaned = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");

        // Replace multiple spaces with single space
        cleaned = cleaned.replaceAll("\\s+", " ");

        // Remove excessive newlines (more than 2 in a row)
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");

        // Remove any remaining weird characters
        cleaned = cleaned.replaceAll("[\\uFFFD\\uFFFF]", "");

        // Trim whitespace
        cleaned = cleaned.trim();

        // Ensure content ends with proper punctuation if possible and not too long
        if (!cleaned.isEmpty() && cleaned.length() > 10 && !cleaned.matches(".*[.!?]$")) {
            cleaned = cleaned + ".";
        }

        // Limit to MAX_CHUNK_SIZE characters (safety check)
        if (cleaned.length() > MAX_CHUNK_SIZE) {
            cleaned = cleaned.substring(0, MAX_CHUNK_SIZE);
            // Try to end at a sentence boundary
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

        if (!resource.exists()) {
            log.error("File does not exist: {}", fileName);
            return new ArrayList<>();
        }

        return switch (extension.toLowerCase()) {
            case ".xlsx", ".xls" -> extractFromExcel(resource);
            case ".txt", ".csv", ".json", ".md", ".log", ".xml" -> extractFromTextFile(resource);
            default -> {
                log.warn("Unsupported file type: {} for file {}", extension, fileName);
                yield new ArrayList<>();
            }
        };
    }

    /**
     * Extract from TEXT files with safe chunking for embedding models
     */
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

            // For very short files, keep as single document
            if (text.length() <= MAX_CHUNK_SIZE) {
                Map<String, Object> metadata = createMetadata(resource, "text");
                metadata.put("lineCount", lineCount);
                metadata.put("characterCount", text.length());
                Document doc = new Document(text, metadata);
                doc = validateAndCleanDocument(doc);
                if (doc != null) {
                    documents.add(doc);
                    log.info("Text file {} added as single document ({} chars, {} lines)",
                            fileName, text.length(), lineCount);
                } else {
                    log.warn("Text file {} was invalid after cleaning", fileName);
                }
            } else {
                // Split into smaller, safe chunks
                documents = splitTextIntoSafeChunks(text, resource, fileName, lineCount);
                log.info("Text file {} split into {} safe chunks (total {} chars, {} lines)",
                        fileName, documents.size(), text.length(), lineCount);
            }
        }

        return documents;
    }

    /**
     * Split text into chunks that are safe for embedding models
     */
    private List<Document> splitTextIntoSafeChunks(String text, Resource resource,
                                                   String fileName, int lineCount) {
        List<Document> documents = new ArrayList<>();
        int startIndex = 0;
        int chunkNumber = 0;

        while (startIndex < text.length()) {
            int endIndex = Math.min(startIndex + MAX_CHUNK_SIZE, text.length());

            // Try to find a good break point (newline or space)
            if (endIndex < text.length()) {
                int lastNewline = text.lastIndexOf('\n', endIndex);
                int lastSpace = text.lastIndexOf(' ', endIndex);
                int lastPeriod = text.lastIndexOf('.', endIndex);
                int breakPoint = Math.max(lastPeriod, Math.max(lastNewline, lastSpace));

                if (breakPoint > startIndex + MAX_CHUNK_SIZE / 2) {
                    endIndex = breakPoint + 1; // Include the punctuation/newline
                }
            }

            String chunk = text.substring(startIndex, Math.min(endIndex, text.length()));

            // Validate chunk size
            if (chunk.length() > MAX_CHUNK_SIZE) {
                log.warn("Chunk {} from {} is {} chars, truncating to {}",
                        chunkNumber, fileName, chunk.length(), MAX_CHUNK_SIZE);
                chunk = chunk.substring(0, MAX_CHUNK_SIZE);
            }

            if (!chunk.trim().isEmpty()) {
                Map<String, Object> metadata = createMetadata(resource, "text");
                metadata.put("chunkIndex", chunkNumber);
                metadata.put("totalChunks", -1); // Will be set after we know total
                metadata.put("chunkSize", chunk.length());
                metadata.put("lineCount", lineCount);

                Document doc = new Document(chunk, metadata);
                doc = validateAndCleanDocument(doc);

                if (doc != null) {
                    documents.add(doc);
                } else {
                    log.warn("Skipped invalid chunk {} from {}", chunkNumber, fileName);
                }
            }

            chunkNumber++;
            startIndex = endIndex;

            // Add overlap for the next chunk
            if (startIndex < text.length()) {
                startIndex = Math.max(startIndex - OVERLAP_SIZE, startIndex);
            }
        }

        // Update total chunks in metadata
        int totalChunks = documents.size();
        for (Document doc : documents) {
            doc.getMetadata().put("totalChunks", totalChunks);
        }

        return documents;
    }

    /**
     * Extract from Excel files with better error handling
     */
    private List<Document> extractFromExcel(Resource resource) throws Exception {
        List<Document> documents = new ArrayList<>();
        String fileName = resource.getFilename();

        try (InputStream inputStream = resource.getInputStream();
             Workbook workbook = WorkbookFactory.create(inputStream)) {

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                Iterator<Row> rows = sheet.iterator();

                if (!rows.hasNext()) {
                    log.debug("Sheet {} is empty: {}", sheetIndex, fileName);
                    continue;
                }

                // Skip header row
                Row headerRow = rows.next();
                int rowCount = 0;

                while (rows.hasNext()) {
                    Row row = rows.next();
                    rowCount++;

                    if (row.getPhysicalNumberOfCells() < 2) {
                        continue;
                    }

                    try {
                        String companyName = getCellValue(row.getCell(0));
                        if (companyName == null || companyName.trim().isEmpty()) {
                            continue;
                        }

                        String companyText = buildCompanyDocument(row);

                        // Validate document size
                        if (companyText.length() > MAX_CHUNK_SIZE) {
                            log.warn("Excel row {} for {} is {} chars, truncating",
                                    rowCount, companyName, companyText.length());
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

                        if (doc != null) {
                            documents.add(doc);
                        }

                    } catch (Exception e) {
                        log.error("Error processing row {} in sheet {} of file {}",
                                rowCount, sheetIndex, fileName, e);
                    }
                }

                log.info("Processed {} rows from sheet '{}' in {}, extracted {} documents",
                        rowCount, sheet.getSheetName(), fileName, documents.size());
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

        // Make the document more concise to avoid token limits
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
        metadata.put("source", "financial-docs");
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
                        String stringValue = cell.getStringCellValue().trim();
                        yield stringValue;
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
        log.info("Manual reload triggered - reloading all documents");
        loadDocuments();
    }
}