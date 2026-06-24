package com.finsight.finsight_ai.service.s3;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3UploadService {

    private final S3Client s3Client;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    public static final String USER_UPLOADS_PREFIX = "User_Upload_Documents/";

    public String uploadFile(MultipartFile file, String userName) throws IOException {
        String key = USER_UPLOADS_PREFIX + userName + "/" + file.getOriginalFilename();

        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucketName).key(key).build(),
                RequestBody.fromInputStream(file.getInputStream(), file.getSize())
        );

        return key;
    }

    /**
     * Returns true when the user already has a file with this exact name in S3.
     * Uses HeadObject — no data is downloaded, just the object metadata is checked.
     */
    public boolean fileExists(String userId, String fileName) {
        String key = USER_UPLOADS_PREFIX + userId + "/" + fileName;
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            log.warn("S3 existence check failed for key={}: {}", key, e.getMessage());
            return false;
        }
    }
}
