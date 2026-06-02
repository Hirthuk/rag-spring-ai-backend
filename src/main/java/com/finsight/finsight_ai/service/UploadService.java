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

@Service
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

                vectorStore.add(
                        companyDocuments
                );

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

            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(800)
                    .withMinChunkSizeChars(350)
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
                    "default_tenant",    // 1. tenantName
                    "default_database",  // 2. databaseName
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
     * Extract company documents from Excel - each row becomes a separate document
     */
    private List<Document> extractCompanyDocumentsFromExcel(
            MultipartFile file,
            Map<String, Object> baseMetadata
    ) {

        List<Document> documents =
                new ArrayList<>();

        try (
                Workbook workbook =
                        WorkbookFactory.create(
                                file.getInputStream()
                        )
        ) {

            Sheet sheet =
                    workbook.getSheetAt(0);

            Iterator<Row> rows =
                    sheet.iterator();

            if (!rows.hasNext()) {

                return documents;
            }

            // Skip header row
            rows.next();

            while (rows.hasNext()) {

                Row row = rows.next();

                if (row.getPhysicalNumberOfCells() < 11) {

                    continue;
                }

                String companyText =
                        buildCompanyDocument(
                                row
                        );

                Map<String,Object> metadata =
                        new HashMap<>(
                                baseMetadata
                        );

                metadata.put(
                        "companyName",
                        getCellValue(
                                row.getCell(0)
                        )
                );

                metadata.put(
                        "ticker",
                        getCellValue(
                                row.getCell(1)
                        )
                );

                metadata.put(
                        "sector",
                        getCellValue(
                                row.getCell(2)
                        )
                );

                Document companyDocument =
                        new Document(
                                companyText,
                                metadata
                        );

                documents.add(
                        companyDocument
                );
            }

            return documents;

        } catch (Exception e) {

            throw new RuntimeException(
                    "Failed to process Excel",
                    e
            );
        }
    }

    private String buildCompanyDocument(
            Row row
    ) {

        String company =
                getCellValue(row.getCell(0));

        String fiscalYear =
                getCellValue(row.getCell(3));

        String revenue =
                getCellValue(row.getCell(4));

        String netProfit =
                getCellValue(row.getCell(8));

        String margin =
                getCellValue(row.getCell(9));

        return """
                Company Name: %s

                Ticker Symbol: %s

                Sector: %s

                Fiscal Year: %s

                Revenue: %s Million USD

                Cost Of Goods Sold: %s Million USD

                Operating Expenses: %s Million USD

                EBITDA: %s Million USD

                Net Profit: %s Million USD

                Profit Margin: %s Percent

                Earnings Per Share: %s

                Financial Summary:

                %s generated revenue of %s million USD
                and net profit of %s million USD
                with profit margin of %s percent
                during fiscal year %s.
                """
                .formatted(
                        company,
                        getCellValue(row.getCell(1)),
                        getCellValue(row.getCell(2)),
                        fiscalYear,
                        revenue,
                        getCellValue(row.getCell(5)),
                        getCellValue(row.getCell(6)),
                        getCellValue(row.getCell(7)),
                        netProfit,
                        margin,
                        getCellValue(row.getCell(10)),
                        company,
                        revenue,
                        netProfit,
                        margin,
                        fiscalYear
                );
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
     * Extract text from Word document
     */
    private String extractTextFromWord(MultipartFile file) {
        String filename = file.getOriginalFilename();
        return "Word document detected: " + filename + "\n" +
                "For full Word support, add Apache POI dependency to your project.";
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