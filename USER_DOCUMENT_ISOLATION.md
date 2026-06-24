# User Document Isolation — Implementation Guide

## Overview

This document describes how FinsightAI isolates user-uploaded documents so that:
- Each user only sees **their own** uploaded files during RAG queries.
- Uploaded files are **automatically removed** from the vector store when the user logs out.
- Uploaded files are **automatically re-loaded** into the vector store the next time the user logs in.
- **System documents** (pre-loaded at startup from the root S3 bucket) remain visible to **all** authenticated users.

---

## S3 Folder Structure

```
finsight-ai-project/                         ← S3 bucket root
├── system-doc-1.xlsx                        ← System documents (loaded at startup, shared)
├── system-doc-2.csv
│   ...
└── User_Upload_Documents/                   ← User uploads folder
    ├── alice@example.com/
    │   ├── balance-sheet.pdf
    │   └── annual-report.xlsx
    └── bob@example.com/
        └── portfolio.csv
```

- **System documents** live at the bucket root.
- **User documents** live under `User_Upload_Documents/<userEmail>/<filename>`.

---

## How It Works

### 1. Application Startup — System Document Loading

`DocumentLoaderService` runs `@PostConstruct` and loads all documents from S3 **except** anything under `User_Upload_Documents/`. System documents are embedded into Chroma without a `userEmail` metadata field.

```
Startup → DocumentLoaderService.loadDocuments()
        → lists S3 objects (skips User_Upload_Documents/ prefix)
        → embeds each into Chroma with no userEmail metadata
```

### 2. User Login — User Document Loading

When a user logs in via `POST /auth/login`, `AuthController` calls `UserDocumentService.loadUserDocuments(email)` **asynchronously** (does not block the login response).

```
POST /auth/login
  → CognitoAuthService.login(req)   (validates credentials, returns tokens)
  → UserDocumentService.loadUserDocuments(email)   [async, background]
      → lists s3://<bucket>/User_Upload_Documents/<email>/
      → for each file: downloads bytes, calls UploadService.indexDocumentFile()
          → text extraction (PDF, Excel, Word, CSV, image OCR, etc.)
          → adds to Chroma with metadata: { userEmail: "<email>", source: "upload", ... }
```

The login API returns immediately with the JWT tokens. Document loading happens in the background via Spring's `@Async` thread pool.

### 3. User Upload — Storing New Documents

When a user uploads a file via `POST /api/upload`:

```
POST /api/upload (multipart/form-data)
  → UploadController.uploadFile(file, userName)
  → UploadService.processFileAsync(bytes, fileName, contentType, userEmail)   [async]
      → S3UploadService.uploadFile()
          → stores at s3://<bucket>/User_Upload_Documents/<email>/<filename>
      → UploadService.indexDocumentFile(file, userEmail)
          → text extraction
          → adds to Chroma with metadata: { userEmail: "<email>", source: "upload", ... }
```

The file is saved to the correct S3 path **and** immediately indexed in Chroma for the current session.

### 4. User Logout — Removing User Documents

When the user calls `POST /auth/logout` (requires a valid Bearer token):

```
POST /auth/logout
  Authorization: Bearer <accessToken>
  → AuthController.logout(jwt)
  → extracts email from JWT claim "email"
  → CognitoAuthService.globalSignOut(accessToken)   (invalidates tokens in Cognito)
  → UserDocumentService.removeUserDocuments(email)
      → chromaApi.deleteEmbeddings(where: { userEmail: { $eq: "<email>" } })
      → all Chroma embeddings for this user are deleted
```

### 5. RAG Query — Retrieval with Document Isolation

`RetrievalService.retrieveRelevantDocuments(query)` applies in-memory filtering after the Chroma similarity search:

```
Chroma similarity search (topK=20 candidates)
  → post-filter in Java:
      if doc.metadata["userEmail"] == null  → system doc → INCLUDE (visible to all)
      if doc.metadata["userEmail"] == currentUserEmail → INCLUDE (user's own doc)
      else → EXCLUDE (another user's doc — should not appear in practice after logout cleanup)
  → return top 10 results
```

The `currentUserEmail` is extracted from the Cognito JWT via `SecurityContextHolder`.

---

## Modified Files

| File | Change |
|------|--------|
| `service/s3/S3UploadService.java` | Upload path changed to `User_Upload_Documents/<email>/<file>` |
| `service/UploadService.java` | Refactored: S3 upload and Chroma indexing are now separate steps; `userEmail` added to all document metadata |
| `service/DocumentLoaderService.java` | Skips `User_Upload_Documents/` keys during startup loading |
| `service/retrieval/RetrievalService.java` | Post-query in-memory filter: only returns system docs + current user's docs |
| `controller/auth/AuthController.java` | Calls `UserDocumentService.loadUserDocuments` on login; calls `removeUserDocuments` on logout |

## New Files

| File | Purpose |
|------|---------|
| `service/UserDocumentService.java` | `loadUserDocuments(email)` — reloads user's S3 files into Chroma on login; `removeUserDocuments(email)` — deletes user's Chroma embeddings on logout |

---

## Configuration

No new configuration properties are required. The existing settings are used:

```properties
aws.s3.bucket=finsight-ai-project
spring.ai.vectorstore.chroma.collection-name=financial-docs
spring.ai.vectorstore.chroma.tenant-id=SpringAiTenant
spring.ai.vectorstore.chroma.database-id=SpringAiDatabase
```

---

## First-Time Migration

Existing documents already in Chroma were loaded **before** the `userEmail` metadata field existed. They will be treated as **system documents** by the retrieval filter (no `userEmail` → visible to all), which is the correct behavior.

User-uploaded documents that were saved under `<email>/<filename>` (old path) should be **manually moved** in S3 to `User_Upload_Documents/<email>/<filename>` to be picked up by the new login reload flow.

---

## Security Properties

| Property | Guarantee |
|----------|-----------|
| Session isolation | User docs are loaded on login and removed on logout |
| Concurrent users | Users cannot see each other's uploaded documents during active sessions (post-filter in RetrievalService) |
| Persistent storage | User files persist in S3 across sessions; they are re-indexed on every login |
| Unauthorized access | All non-auth endpoints require a valid Cognito JWT; the chat and upload APIs are fully authenticated |

---

## Data Flow Diagram

```
User Login
   │
   ├─ Cognito validates credentials → returns JWT tokens
   │
   └─ [async] S3: User_Upload_Documents/<email>/ → download files
                      │
                      └─ UploadService.indexDocumentFile()
                              │
                              └─ Chroma: add embeddings {userEmail: "<email>", ...}

User Uploads File
   │
   ├─ S3: store at User_Upload_Documents/<email>/<file>
   │
   └─ UploadService.indexDocumentFile()
           │
           └─ Chroma: add embeddings {userEmail: "<email>", ...}

User Sends Chat Query
   │
   └─ RetrievalService.retrieveRelevantDocuments(query)
           │
           ├─ Chroma similarity search (topK=20)
           │
           └─ Post-filter:
                   • userEmail == null        → system doc → INCLUDE
                   • userEmail == currentUser → own doc   → INCLUDE
                   • userEmail != currentUser → other user → EXCLUDE
                   (return top 10)

User Logout
   │
   ├─ Cognito globalSignOut (invalidates tokens)
   │
   └─ Chroma: deleteEmbeddings WHERE userEmail == "<email>"
```
