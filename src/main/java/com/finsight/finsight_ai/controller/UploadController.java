package com.finsight.finsight_ai.controller;
import com.finsight.finsight_ai.service.UploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:5174"})
public class UploadController {

    private final Optional<UploadService> uploadService;

    @PostMapping
    public ResponseEntity<String> uploadFile(
            @RequestParam("file") MultipartFile file, String userName
    ) {
        if (uploadService.isEmpty()) {
            return ResponseEntity.status(503).body(
                    "Upload service not available - vector store may not be configured"
            );
        }

        uploadService.get().processFile(file, userName);

        return ResponseEntity.ok(
                "File uploaded and embedded successfully"
        );
    }
}
