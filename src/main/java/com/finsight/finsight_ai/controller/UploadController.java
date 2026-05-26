package com.finsight.finsight_ai.controller;
import com.finsight.finsight_ai.service.UploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
@CrossOrigin("*")
public class UploadController {

    private final UploadService uploadService;

    @PostMapping
    public ResponseEntity<String> uploadFile(
            @RequestParam("file") MultipartFile file
    ) {

        uploadService.processFile(file);

        return ResponseEntity.ok(
                "File uploaded and embedded successfully"
        );
    }
}
