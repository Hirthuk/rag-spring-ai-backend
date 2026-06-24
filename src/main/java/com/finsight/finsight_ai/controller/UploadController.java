package com.finsight.finsight_ai.controller;

import com.finsight.finsight_ai.service.UploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
@Slf4j
public class UploadController {

    private final Optional<UploadService> uploadService;

    /**
     * Primary upload endpoint.
     *
     * Returns 409 CONFLICT when the user already has a file with the same name,
     * so the frontend can show a "do you want to overwrite?" dialog before
     * calling POST /api/upload/overwrite.
     *
     * Returns 202 ACCEPTED when the file is new — processing is async.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt
    ) {
        if (uploadService.isEmpty()) {
            return ResponseEntity.status(503).body(error("Upload service not available"));
        }

        String userId   = jwt.getSubject();
        String fileName = file.getOriginalFilename();

        if (fileName == null || fileName.isBlank()) {
            return ResponseEntity.badRequest().body(error("File name is missing"));
        }

        // Duplicate check — file name match within the user's S3 folder
        if (uploadService.get().isDuplicate(userId, fileName)) {
            log.info("Duplicate detected: file={} userId={}", fileName, userId);
            return ResponseEntity.status(409).body(Map.of(
                    "status",   "DUPLICATE",
                    "fileName", fileName,
                    "message",  fileName + " already exists. Do you want to overwrite it?"
            ));
        }

        try {
            log.info("Upload started: {} ({} bytes) userId={}", fileName, file.getSize(), userId);
            uploadService.get().processFile(file, userId);

            return ResponseEntity.ok(Map.of(
                    "status",   "DONE",
                    "fileName", fileName,
                    "message",  fileName + " uploaded and indexed successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to process upload: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(error("Failed to process file: " + e.getMessage()));
        }
    }

    /**
     * Overwrite endpoint — called when the user confirms they want to replace an
     * existing file.
     *
     * Removes the old file's Chroma embeddings, re-uploads to S3 (overwriting the
     * existing object), and re-indexes the new content.  All heavy work is async;
     * the caller gets a 202 immediately.
     */
    @PostMapping("/overwrite")
    public ResponseEntity<Map<String, Object>> overwriteFile(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt
    ) {
        if (uploadService.isEmpty()) {
            return ResponseEntity.status(503).body(error("Upload service not available"));
        }

        String userId   = jwt.getSubject();
        String fileName = file.getOriginalFilename();

        if (fileName == null || fileName.isBlank()) {
            return ResponseEntity.badRequest().body(error("File name is missing"));
        }

        try {
            log.info("Overwrite started: {} ({} bytes) userId={}", fileName, file.getSize(), userId);
            uploadService.get().overwriteFile(file, userId);

            return ResponseEntity.ok(Map.of(
                    "status",   "DONE",
                    "fileName", fileName,
                    "message",  fileName + " overwritten and re-indexed successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to process overwrite: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(error("Failed to overwrite file: " + e.getMessage()));
        }
    }

    private static Map<String, Object> error(String message) {
        return Map.of("status", "ERROR", "message", message);
    }
}
