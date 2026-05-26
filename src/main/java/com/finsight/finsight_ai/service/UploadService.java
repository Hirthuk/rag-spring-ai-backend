package com.finsight.finsight_ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class UploadService {

    private final VectorStore vectorStore;

    // Initialize Tesseract OCR
    private Tesseract getTesseractInstance() {
        Tesseract tesseract = new Tesseract();
        tesseract.setLanguage("eng");
        tesseract.setOcrEngineMode(3);
        tesseract.setPageSegMode(1);
        return tesseract;
    }

    public void processFile(MultipartFile file) {
        try {
            String fileName = file.getOriginalFilename();
            String contentType = file.getContentType();

            log.info("Processing file: {}, type: {}, size: {} bytes",
                    fileName, contentType, file.getSize());

            String extractedText;
            Map<String, Object> metadata = new HashMap<>();

            metadata.put("fileName", fileName);
            metadata.put("fileType", contentType);
            metadata.put("fileSize", file.getSize());
            metadata.put("uploadTimestamp", System.currentTimeMillis());

            // Process based on file type
            if (contentType != null && contentType.startsWith("image/")) {
                extractedText = extractTextFromImage(file);
                metadata.put("documentType", "image");
                metadata.put("ocrEngine", "Tesseract");
                log.info("Extracted {} characters from image", extractedText.length());

            } else if (contentType != null && contentType.equals("application/pdf")) {
                extractedText = extractTextFromPdf(file);
                metadata.put("documentType", "pdf");
                log.info("Extracted {} characters from PDF", extractedText.length());

            } else if (contentType != null &&
                    (contentType.equals("text/plain") ||
                            contentType.equals("text/csv") ||
                            contentType.equals("application/json"))) {
                extractedText = new String(file.getBytes(), StandardCharsets.UTF_8);
                metadata.put("documentType", "text");
                log.info("Extracted {} characters from text file", extractedText.length());

            } else if (contentType != null &&
                    (contentType.equals("application/msword") ||
                            contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))) {
                extractedText = extractTextFromWord(file);
                metadata.put("documentType", "word");
                log.info("Extracted {} characters from Word document", extractedText.length());

            } else {
                extractedText = new String(file.getBytes(), StandardCharsets.UTF_8);
                metadata.put("documentType", "unknown");
                log.warn("Unknown file type: {}, treated as text", contentType);
            }

            if (extractedText.trim().isEmpty()) {
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

            log.info("✅ File processed successfully: {} -> {} chunks",
                    fileName, splitDocs.size());

        } catch (Exception e) {
            log.error("Error processing file: {}", file.getOriginalFilename(), e);
            throw new RuntimeException("Failed to process file: " + e.getMessage(), e);
        }
    }

    private String extractTextFromImage(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            BufferedImage image = ImageIO.read(inputStream);

            if (image == null) {
                log.warn("Could not read image file: {}", file.getOriginalFilename());
                return "";
            }

            Tesseract tesseract = getTesseractInstance();
            String extractedText = tesseract.doOCR(image);
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
     * ✅ FIXED: Extract text from PDF using PDFBox 3.x
     */
    private String extractTextFromPdf(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            // ✅ Fixed: Use Loader.loadPDF() instead of PDDocument.load()
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

    private String extractTextFromWord(MultipartFile file) {
        try {
            String filename = file.getOriginalFilename();
            if (filename != null && filename.endsWith(".docx")) {
                return "Word document processing requires additional configuration. Please convert to PDF or text format.";
            } else {
                return "Legacy Word documents (.doc) are not fully supported. Please convert to PDF or .docx format.";
            }
        } catch (Exception e) {
            log.error("Error processing Word document: {}", file.getOriginalFilename(), e);
            return "";
        }
    }

    private String cleanExtractedText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String cleaned = text.replaceAll("\\s+", " ");
        cleaned = cleaned.replaceAll("[^\\w\\s$%,\\.\\-:/(\\)]", "");
        cleaned = cleaned.trim();

        return cleaned;
    }

    public void processImageWithMetadata(MultipartFile image) {
        try {
            Map<String, Object> metadata = new HashMap<>();

            try (InputStream inputStream = image.getInputStream()) {
                com.drew.imaging.ImageMetadataReader.readMetadata(inputStream)
                        .getDirectories()
                        .forEach(directory -> directory.getTags()
                                .forEach(tag -> metadata.put(
                                        directory.getName() + ":" + tag.getTagName(),
                                        tag.getDescription())));
            } catch (Exception e) {
                log.warn("Could not extract image metadata: {}", e.getMessage());
            }

            String extractedText = extractTextFromImage(image);
            metadata.put("extractedText", extractedText);

            Document doc = new Document(extractedText, metadata);
            vectorStore.add(List.of(doc));

            log.info("✅ Image processed with metadata: {}", metadata.keySet());

        } catch (Exception e) {
            log.error("Error processing image with metadata", e);
        }
    }
}