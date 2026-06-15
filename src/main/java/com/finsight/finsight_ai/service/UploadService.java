package com.finsight.finsight_ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;

@Service
//@ConditionalOnBean(VectorStore.class)
@RequiredArgsConstructor
@Slf4j
public class UploadService {

    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChromaApi chromaApi;
    private final DocumentLoaderService documentLoaderService;
    // Supported image formats
    private static final Set<String> SUPPORTED_IMAGE_FORMATS = Set.of(
            "jpg", "jpeg", "png", "gif", "bmp", "tiff", "webp"
    );

    // Initialize Tesseract OCR
    private Tesseract getTesseractInstance() {
        // 1. Force JNA to look in the Homebrew directory for the native library
        System.setProperty("jna.library.path", "/opt/homebrew/lib");

        Tesseract tesseract = new Tesseract();

        // 2. Point Tesseract to the language data files, otherwise it will crash next!
        tesseract.setDatapath("/opt/homebrew/share/tessdata");

        tesseract.setLanguage("eng");
        tesseract.setOcrEngineMode(3);
        tesseract.setPageSegMode(1);
        return tesseract;
    }

    public void processFile(MultipartFile file) {
        try {

            String fileName = file.getOriginalFilename();
            String contentType = file.getContentType();
            String fileExtension = getFileExtension(fileName);

            log.info("Processing file: {}, type: {}, size: {} bytes, extension: {}",
                    fileName, contentType, file.getSize(), fileExtension);

            String extractedText;
            Map<String,Object> metadata =
                    new HashMap<>();

            metadata.put(
                    "fileName",
                    file.getOriginalFilename()
            );

            metadata.put(
                    "source",
                    "upload"
            );
            metadata.put("fileType", contentType);
            metadata.put("fileExtension", fileExtension);
            metadata.put("fileSize", file.getSize());
            metadata.put("uploadTimestamp", System.currentTimeMillis());

            // Process based on file extension and content type
            if (contentType != null && contentType.startsWith("image/")) {
                extractedText = extractTextFromImage(file);
                metadata.put("documentType", "image");
                metadata.put("ocrEngine", "Tesseract");
                log.info("Extracted {} characters from image", extractedText.length());

            } else if (contentType != null && contentType.equals("application/pdf")) {
                extractedText = extractTextFromPdf(file);
                metadata.put("documentType", "pdf");
                log.info("Extracted {} characters from PDF", extractedText.length());

            } else if (fileExtension.equalsIgnoreCase("csv")) {
                extractedText = extractTextFromCsv(file);
                metadata.put("documentType", "csv");
                int rowCount = extractedText.split("\n").length;
                metadata.put("rowCount", rowCount);
                log.info("Extracted {} rows from CSV", rowCount);

            } else if (fileExtension.equalsIgnoreCase("json")) {
                extractedText = extractTextFromJson(file);
                metadata.put("documentType", "json");
                log.info("Extracted {} characters from JSON", extractedText.length());

            } else if (contentType != null &&
                    (contentType.equals("text/plain") ||
                            contentType.equals("text/csv") ||
                            contentType.equals("application/json"))) {
                extractedText = new String(file.getBytes(), StandardCharsets.UTF_8);
                metadata.put("documentType", "text");
                log.info("Extracted {} characters from text file", extractedText.length());

            } else if (fileExtension.equalsIgnoreCase("docx") ||
                    fileExtension.equalsIgnoreCase("doc")) {
                extractedText = extractTextFromWord(file);
                metadata.put("documentType", "word");
                log.info("Extracted {} characters from Word document", extractedText.length());

            } else if (fileExtension.equalsIgnoreCase("xlsx") ||
                    fileExtension.equalsIgnoreCase("xls")) {

                metadata.put(
                        "documentType",
                        "excel"
                );

                List<Document> companyDocuments =
                        extractCompanyDocumentsFromExcel(
                                file,
                                metadata
                        );

                // Cohere embedding API on Bedrock rejects batches > 128 texts.
                // Split into batches of 96 to stay safely under the limit.
                addInBatches(companyDocuments);

                log.info(
                        "Stored {} company documents from Excel",
                        companyDocuments.size()
                );

                return;

            } else if (fileExtension.equalsIgnoreCase("html") ||
                    fileExtension.equalsIgnoreCase("htm")) {
                extractedText = extractTextFromHtml(file);
                metadata.put("documentType", "html");
                log.info("Extracted {} characters from HTML", extractedText.length());

            } else {
                extractedText = new String(file.getBytes(), StandardCharsets.UTF_8);
                metadata.put("documentType", "unknown");
                log.warn("Unknown file type: {}, treated as text", contentType);
            }

            if (extractedText == null || extractedText.trim().isEmpty()) {
                log.warn("No text extracted from file: {}", fileName);
                metadata.put("extractionStatus", "failed");
                Document emptyDoc = new Document("No text could be extracted from this file.", metadata);
                vectorStore.add(List.of(emptyDoc));
                return;
            }

            Document document = new Document(extractedText, metadata);

            // Cohere embed-english-v3 on Bedrock has a hard 512-token input limit.
            // TokenTextSplitter counts real tokens (CL100K tokenizer), so 800 tokens
            // exceeded the limit and caused "Invalid parameter combination" from Bedrock.
            // 380 tokens keeps every chunk comfortably under 512 even for number-dense
            // financial text that tokenises more heavily than prose.
            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(380)
                    .withMinChunkSizeChars(50)
                    .withMinChunkLengthToEmbed(5)
                    .withMaxNumChunks(10000)
                    .withKeepSeparator(true)
                    .build();

            List<Document> splitDocs = splitter.apply(List.of(document));
            vectorStore.add(splitDocs);

            log.info("✅ File processed successfully: {} -> {} chunks, {} characters",
                    fileName, splitDocs.size(), extractedText.length());

        } catch (Exception e) {
            log.error("Error processing file: {}", file.getOriginalFilename(), e);
            throw new RuntimeException("Failed to process file: " + e.getMessage(), e);
        }
    }

    public String resetAndReload() {

        try {

            chromaApi.deleteCollection(
                    "SpringAiTenant",    // 1. tenantName
                    "SpringAiDatabase",  // 2. databaseName
                    "financial-docs"     // 3. collectionName
            );

            documentLoaderService.loadDocuments();

            return "Vector store reset successfully.";

        } catch (Exception e) {

            log.error(
                    "Failed to reset vector store",
                    e
            );

            return "Failed to reset vector store.";
        }
    }

    /**
     * Extract text from image with WebP support
     */
    private String extractTextFromImage(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            Map<String,Object> metadata =
                    new HashMap<>();

            metadata.put(
                    "fileName",
                    file.getOriginalFilename()
            );

            metadata.put(
                    "source",
                    "upload"
            );
            BufferedImage image = null;
            String fileName = file.getOriginalFilename();
            String fileExtension = getFileExtension(fileName);

            // Try to read the image with standard ImageIO first
            image = ImageIO.read(inputStream);

            // If standard reading fails and it's a WebP, try alternative approach
            if (image == null && "webp".equalsIgnoreCase(fileExtension)) {
                log.info("WebP image detected, trying alternative decoding...");
                image = decodeWebPImage(file.getBytes());
            }

            // If still null, try to read from bytes again
            if (image == null) {
                image = ImageIO.read(new ByteArrayInputStream(file.getBytes()));
            }

            if (image == null) {
                log.warn("Could not read image file: {}. Unsupported format or corrupted file.", fileName);
                return "";
            }

            // Pre-process image for better OCR
            image = preprocessImageForOCR(image);

            Tesseract tesseract = getTesseractInstance();
            String extractedText = tesseract.doOCR(image);

            log.info("OCR extracted {} characters from image: {}", extractedText.length(), fileName);
            return cleanExtractedText(extractedText);

        } catch (TesseractException e) {
            log.error("OCR failed for image: {}", file.getOriginalFilename(), e);
            return "OCR failed to extract text from this image. Please ensure the image contains clear text.";
        } catch (Exception e) {
            log.error("Error processing image: {}", file.getOriginalFilename(), e);
            return "";
        }
    }

    /**
     * Decode WebP image using alternative method
     * Note: This is a simple fallback. For production, use webp-imageio dependency
     */
    private BufferedImage decodeWebPImage(byte[] imageBytes) {
        try {
            // Try to read using standard ImageIO with webp-imageio plugin
            // If plugin is not available, this will return null

            return ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (Exception e) {
            log.warn("WebP decoding failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Pre-process image for better OCR accuracy
     */
    private BufferedImage preprocessImageForOCR(BufferedImage original) {
        if (original == null) return null;

        try {
            // Convert to grayscale for better OCR
            BufferedImage grayscale = new BufferedImage(
                    original.getWidth(),
                    original.getHeight(),
                    BufferedImage.TYPE_BYTE_GRAY
            );

            java.awt.Graphics2D g2d = grayscale.createGraphics();
            g2d.drawImage(original, 0, 0, null);
            g2d.dispose();

            return grayscale;

        } catch (Exception e) {
            log.warn("Image preprocessing failed, using original: {}", e.getMessage());
            return original;
        }
    }

    /**
     * Extract text from PDF using PDFBox 3.x
     */
    private String extractTextFromPdf(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            Map<String,Object> metadata =
                    new HashMap<>();

            metadata.put(
                    "fileName",
                    file.getOriginalFilename()
            );

            metadata.put(
                    "source",
                    "upload"
            );
            PDDocument document = Loader.loadPDF(inputStream.readAllBytes());
            PDFTextStripper stripper = new PDFTextStripper();
            String extractedText = stripper.getText(document);
            document.close();
            return cleanExtractedText(extractedText);
        } catch (Exception e) {
            log.error("Error extracting text from PDF: {}", file.getOriginalFilename(), e);
            return "";
        }
    }

    /**
     * Add documents to the vector store in batches.
     * Cohere embed on Bedrock rejects any batch with more than 128 texts.
     */
    private static final int COHERE_BATCH_SIZE = 96;

    private void addInBatches(List<Document> docs) {
        for (int i = 0; i < docs.size(); i += COHERE_BATCH_SIZE) {
            List<Document> batch = docs.subList(i, Math.min(i + COHERE_BATCH_SIZE, docs.size()));
            vectorStore.add(batch);
            log.info("Embedded batch {}-{} of {} documents",
                    i + 1, i + batch.size(), docs.size());
        }
    }

    /**
     * Extract documents from Excel — header-aware, works with any column structure.
     * Each data row becomes one document. Rows are grouped by company when a company
     * column is detected, so the model gets all years for a company in one context chunk.
     */
    private List<Document> extractCompanyDocumentsFromExcel(
            MultipartFile file,
            Map<String, Object> baseMetadata
    ) {
        List<Document> documents = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {

            for (int sheetIdx = 0; sheetIdx < workbook.getNumberOfSheets(); sheetIdx++) {
                Sheet sheet = workbook.getSheetAt(sheetIdx);

                // Find the first non-empty row and treat it as the header row
                List<String> headers = new ArrayList<>();
                int dataStartRow = -1;

                for (Row row : sheet) {
                    int lastCol = row.getLastCellNum();
                    if (lastCol <= 0) continue;
                    for (int c = 0; c < lastCol; c++) {
                        String val = getCellValue(row.getCell(c)).trim();
                        headers.add(val.isEmpty() ? ("Col" + c) : val);
                    }
                    dataStartRow = row.getRowNum() + 1;
                    break;
                }

                if (headers.isEmpty() || dataStartRow < 0) {
                    log.warn("No headers found in sheet '{}' of {}", sheet.getSheetName(), file.getOriginalFilename());
                    continue;
                }

                // Detect which column holds the company name (case-insensitive search)
                int companyCol = -1;
                for (int i = 0; i < headers.size(); i++) {
                    String h = headers.get(i).toLowerCase();
                    if (h.contains("company") || h.equals("name") || h.contains("entity") || h.contains("firm")) {
                        companyCol = i;
                        break;
                    }
                }

                // Group rows by company so all years for a company land in one document
                java.util.LinkedHashMap<String, List<Row>> byCompany = new java.util.LinkedHashMap<>();
                for (int rowNum = dataStartRow; rowNum <= sheet.getLastRowNum(); rowNum++) {
                    Row row = sheet.getRow(rowNum);
                    if (row == null) continue;
                    // Skip completely empty rows
                    boolean hasAnyData = false;
                    for (int c = 0; c < headers.size(); c++) {
                        if (!getCellValue(row.getCell(c)).trim().isEmpty()) { hasAnyData = true; break; }
                    }
                    if (!hasAnyData) continue;

                    String companyKey = (companyCol >= 0)
                            ? getCellValue(row.getCell(companyCol)).trim()
                            : "row-" + rowNum;
                    byCompany.computeIfAbsent(companyKey, k -> new ArrayList<>()).add(row);
                }

                // Build one document per company (all its rows concatenated)
                for (Map.Entry<String, List<Row>> entry : byCompany.entrySet()) {
                    String companyKey = entry.getKey();
                    StringBuilder text = new StringBuilder();

                    for (Row row : entry.getValue()) {
                        for (int c = 0; c < headers.size(); c++) {
                            String value = getCellValue(row.getCell(c)).trim();
                            if (!value.isEmpty()) {
                                text.append(headers.get(c)).append(": ").append(value).append(" | ");
                            }
                        }
                        text.append("\n");
                    }

                    String content = text.toString().trim();
                    if (content.isEmpty()) continue;

                    Map<String, Object> metadata = new HashMap<>(baseMetadata);
                    metadata.put("sheetName", sheet.getSheetName());
                    metadata.put("companyName", companyKey);
                    metadata.put("rowCount", entry.getValue().size());
                    documents.add(new Document(content, metadata));
                }

                log.info("Sheet '{}': {} headers, {} company documents extracted",
                        sheet.getSheetName(), headers.size(), byCompany.size());
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to process Excel: " + e.getMessage(), e);
        }

        return documents;
    }


    private String getCellValue(
            Cell cell
    ) {
        if (cell == null) {
            return "";
        }

        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    /**
     * Extract text from CSV
     */
    private String extractTextFromCsv(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream();
             java.io.BufferedReader reader = new java.io.BufferedReader(
                     new java.io.InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            StringBuilder content = new StringBuilder();
            String line;
            int rowNum = 0;

            content.append("CSV Data File: ").append(file.getOriginalFilename()).append("\n\n");

            while ((line = reader.readLine()) != null) {
                rowNum++;
                String[] columns = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");

                if (rowNum == 1) {
                    content.append("Headers: ").append(String.join(" | ", columns)).append("\n\n");
                } else {
                    content.append("Row ").append(rowNum - 1).append(":\n");
                    for (int i = 0; i < columns.length; i++) {
                        content.append("  • ").append(columns[i]).append("\n");
                    }
                    content.append("\n");
                }
            }

            return content.toString();

        } catch (Exception e) {
            log.error("Error processing CSV: {}", file.getOriginalFilename(), e);
            return "";
        }
    }

    /**
     * Extract text from JSON
     */
    private String extractTextFromJson(MultipartFile file) {
        try {
            String jsonString = new String(file.getBytes(), StandardCharsets.UTF_8);
            JsonNode jsonNode = objectMapper.readTree(jsonString);

            StringBuilder content = new StringBuilder();
            content.append("JSON Data File: ").append(file.getOriginalFilename()).append("\n\n");
            formatJsonNode(jsonNode, "", content);

            return content.toString();

        } catch (Exception e) {
            log.error("Error processing JSON: {}", file.getOriginalFilename(), e);
            return "";
        }
    }

    /**
     * Recursively format JSON as readable text
     */
    private void formatJsonNode(JsonNode node, String prefix, StringBuilder content) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                content.append(prefix).append(entry.getKey()).append(": ");
                formatJsonNode(entry.getValue(), prefix + "  ", content);
            });
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode item : node) {
                content.append(prefix).append("Item ").append(index++).append(": ");
                formatJsonNode(item, prefix + "  ", content);
            }
        } else {
            content.append(node.asText()).append("\n");
        }
    }

    /**
     * Extract text from HTML
     */
    private String extractTextFromHtml(MultipartFile file) {
        try {
            String html = new String(file.getBytes(), StandardCharsets.UTF_8);
            String text = html.replaceAll("<script[^>]*>.*?</script>", " ")
                    .replaceAll("<style[^>]*>.*?</style>", " ")
                    .replaceAll("<[^>]*>", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            return text;
        } catch (Exception e) {
            log.error("Error processing HTML: {}", file.getOriginalFilename(), e);
            return "";
        }
    }

    /**
     * Extract text from Word document (DOCX and DOC formats)
     */
    private String extractTextFromWord(MultipartFile file) {
        try {
            String fileName = file.getOriginalFilename();
            String fileExtension = getFileExtension(fileName);

            if (fileExtension.equalsIgnoreCase("docx")) {
                return extractTextFromDocx(file);
            } else if (fileExtension.equalsIgnoreCase("doc")) {
                return extractTextFromDoc(file);
            } else {
                log.warn("Unknown Word document format: {}", fileExtension);
                return "";
            }
        } catch (Exception e) {
            log.error("Error processing Word document: {}", file.getOriginalFilename(), e);
            return "";
        }
    }

    /**
     * Extract text from DOCX files using Apache POI XWPF
     */
    private String extractTextFromDocx(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream();
             XWPFDocument document = new XWPFDocument(inputStream)) {

            StringBuilder content = new StringBuilder();

            for (XWPFParagraph paragraph : document.getParagraphs()) {
                if (!paragraph.getText().trim().isEmpty()) {
                    content.append(paragraph.getText()).append("\n");
                }
            }

            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        if (!cell.getText().trim().isEmpty()) {
                            content.append(cell.getText()).append(" | ");
                        }
                    }
                    content.append("\n");
                }
            }

            return cleanExtractedText(content.toString());

        } catch (Exception e) {
            log.error("Error extracting text from DOCX: {}", file.getOriginalFilename(), e);
            return "";
        }
    }

    /**
     * Extract text from legacy DOC files using reflection for HWPF
     * (poi-scratchpad may not always be available, so we use reflection as fallback)
     */
    private String extractTextFromDoc(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            byte[] fileBytes = inputStream.readAllBytes();
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(fileBytes);

            try {
                Class<?> hwpfDocClass = Class.forName("org.apache.poi.hwpf.HWPFDocument");
                Class<?> wordExtractorClass = Class.forName("org.apache.poi.hwpf.extractor.WordExtractor");

                Object document = hwpfDocClass.getConstructor(InputStream.class).newInstance(bais);
                Object extractor = wordExtractorClass.getConstructor(hwpfDocClass).newInstance(document);
                String text = (String) wordExtractorClass.getMethod("getText").invoke(extractor);
                wordExtractorClass.getMethod("close").invoke(extractor);

                return cleanExtractedText(text);

            } catch (ClassNotFoundException e) {
                log.warn("HWPF library not available. .doc file support requires poi-scratchpad dependency.");
                log.info("Consider converting .doc files to .docx format for better compatibility.");
                return "";
            }

        } catch (Exception e) {
            log.error("Error extracting text from DOC: {}", file.getOriginalFilename(), e);
            return "";
        }
    }

    /**
     * Clean extracted text
     */
    private String cleanExtractedText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String cleaned = text.replaceAll("\\s+", " ");
        cleaned = cleaned.trim();
        return cleaned;
    }

    /**
     * Get file extension
     */
    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
    }
}