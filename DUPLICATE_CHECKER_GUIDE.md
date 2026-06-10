# Document Duplicate Checker - Implementation Guide

**Completion Date**: 2026-06-10  
**Status**: ✅ Fully Implemented

---

## Overview

A duplicate detection system has been implemented for the DocumentLoaderService to ensure that documents from `src/main/resources/financial-docs/` are added to the Chroma database collection **only once**, even if the application restarts multiple times.

### Problem Solved
- ❌ **Before**: Every application restart would re-index all documents, creating duplicates in Chroma
- ✅ **After**: Application tracks indexed files using MD5 hashing; duplicates are automatically skipped

---

## How It Works

### 1. **File Hashing Strategy**
Each document file is assigned a unique **MD5 hash** based on its content:
```
File Content → MD5 Hash → Unique Identifier
```

**Why MD5?**
- Fast computation
- Deterministic (same file = same hash)
- Collision-resistant for practical use
- Works across application restarts

### 2. **Tracking Mechanism**

#### File-Based Persistence
Hash records are stored in `.document-hashes` file in the project root:
```
project-root/
├── .document-hashes          # ← Hash tracking file
├── src/
├── pom.xml
└── ...
```

**File Format**:
```
a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6
b5c6d7e8f9g0h1i2j3k4l5m6n7o8p9q0
c9d0e1f2g3h4i5j6k7l8m9n0o1p2q3r4
```
Each line contains one MD5 hash of a processed file.

### 3. **Duplicate Detection Flow**

```
Application Start
    ↓
loadDocuments() called
    ↓
loadExistingDocumentHashes()
    ├─→ Read .document-hashes file
    ├─→ Load all hashes into Set<String>
    └─→ Log count of previously processed files
    ↓
For each file in financial-docs/:
    ├─→ Calculate MD5 hash of file content
    ├─→ Check if hash exists in processedFileHashes set
    │   ├─→ YES: Skip file (duplicate) ⏭️
    │   └─→ NO: Extract & index documents ✅
    ├─→ If indexing succeeds:
    │   ├─→ Add hash to in-memory Set
    │   ├─→ Write hash to .document-hashes file
    │   └─→ Mark as processed
    └─→ Continue to next file
    ↓
Application Startup Complete
```

---

## Code Implementation Details

### Modified Files

#### 1. **DocumentLoaderService.java**

**New Imports Added**:
```java
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Paths;
```

**New Fields**:
```java
private final ChromaApi chromaApi;  // Added to constructor
private final Set<String> processedFileHashes = new HashSet<>();
private static final String HASH_TRACKING_FILE = ".document-hashes";
```

**New Methods**:

1. **`loadExistingDocumentHashes()`** - Loads persisted hashes at startup
   ```java
   // Reads .document-hashes file
   // Populates processedFileHashes set
   // Logs how many documents are already indexed
   ```

2. **`calculateFileHash(Resource)`** - Computes MD5 of file content
   ```java
   // Takes file resource
   // Returns hex-encoded MD5 hash
   // Fallback: uses timestamp if MD5 fails
   ```

3. **`saveDocumentHash(String)`** - Persists hash for future runs
   ```java
   // Appends hash to .document-hashes file
   // Called only after successful indexing
   ```

**Modified Methods**:

1. **`loadDocuments()`** - Added duplicate checking
   ```java
   // Calculate fileHash for each document
   // Check if hash already processed
   // Skip if duplicate (⏭️ log message)
   // Add hash to metadata before indexing
   // Save hash on successful indexing
   ```

2. **`reloadDocuments()`** - Force reload capability
   ```java
   // Clear in-memory hash set
   // Delete .document-hashes file
   // Reload all documents
   // Use when you want to force re-indexing
   ```

---

## Usage Examples

### Scenario 1: First Application Startup
```
1. Application starts
2. loadDocuments() is called via @PostConstruct
3. .document-hashes doesn't exist (first run)
4. All financial-docs/*.* files are processed
5. Each successful file's hash is saved to .document-hashes
6. Chroma now contains all indexed documents

Log Output:
=================================
DOCUMENT LOADER STARTED
=================================
No existing tracking file found - first time loading documents
Found 5 file(s) in financial-docs folder
Processing file: companies.xlsx (hash: a1b2c3d4...)
✅ Successfully indexed 150 documents from companies.xlsx
Processing file: report.pdf (hash: b5c6d7e8...)
✅ Successfully indexed 12 documents from report.pdf
...
DOCUMENT LOADER FINISHED
Total files: 5, Successful: 5, Failed: 0, Skipped (duplicates): 0
=================================
```

### Scenario 2: Application Restart (No Changes)
```
1. Application restarts
2. loadDocuments() is called via @PostConstruct
3. .document-hashes file exists from previous run
4. All hashes are loaded into memory
5. Each file hash is compared - all match
6. All 5 files are skipped (duplicates detected)
7. No documents added to Chroma

Log Output:
=================================
DOCUMENT LOADER STARTED
=================================
Loaded 5 document hashes from tracking file - duplicates will be skipped
Found 5 file(s) in financial-docs folder
Processing file: companies.xlsx (hash: a1b2c3d4...)
⏭️ Skipping duplicate file: companies.xlsx (already in Chroma)
Processing file: report.pdf (hash: b5c6d7e8...)
⏭️ Skipping duplicate file: report.pdf (already in Chroma)
...
DOCUMENT LOADER FINISHED
Total files: 5, Successful: 0, Failed: 0, Skipped (duplicates): 5
=================================
```

### Scenario 3: Add New File (One File Added)
```
1. New file added to financial-docs/: new_data.csv
2. Application restarts
3. Old 5 hashes are loaded from .document-hashes
4. Processing all 6 files
5. First 5 are skipped (hashes match)
6. new_data.csv is new (hash doesn't match)
7. new_data.csv is indexed and hash saved

Log Output:
...
⏭️ Skipping duplicate file: companies.xlsx
⏭️ Skipping duplicate file: report.pdf
Processing file: new_data.csv (hash: c9d0e1f2...)
✅ Successfully indexed 45 documents from new_data.csv
...
DOCUMENT LOADER FINISHED
Total files: 6, Successful: 1, Failed: 0, Skipped (duplicates): 5
=================================
```

### Scenario 4: Force Reload (Reset Everything)
```
// In your code or API endpoint:
documentLoaderService.reloadDocuments();

Result:
1. In-memory hash cache is cleared
2. .document-hashes file is deleted
3. loadDocuments() is called
4. All files are reprocessed and re-indexed
5. New hashes are saved to .document-hashes

Important: This will create duplicates in Chroma!
You may need to reset the collection first using:
uploadService.resetAndReload()
```

---

## File Modification Details

### `.document-hashes` File Structure

**Location**: Project root directory (same level as pom.xml)

**Example Content**:
```
# MD5 hashes of processed financial documents
a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6
b5c6d7e8f9g0h1i2j3k4l5m6n7o8p9q0
c9d0e1f2g3h4i5j6k7l8m9n0o1p2q3r4
d3e4f5g6h7i8j9k0l1m2n3o4p5q6r7s8
e7f8g9h0i1j2k3l4m5n6o7p8q9r0s1t2
```

**Properties**:
- Created automatically on first run
- Appended to (never overwritten) for performance
- Deleted by `reloadDocuments()` to force full reload
- Should be included in `.gitignore` (persisted state file)

---

## Metadata Storage

Each indexed document now includes a `fileHash` in its metadata:

```java
doc.getMetadata().put("fileHash", fileHash);
```

This allows:
1. Future external duplicate checks
2. Traceability of document origin
3. Advanced queries (if needed)

**Example Metadata**:
```json
{
  "fileName": "companies.xlsx",
  "fileType": "excel",
  "fileExtension": "xlsx",
  "fileSize": 245678,
  "source": "financial-docs",
  "documentType": "excel",
  "uploadTimestamp": 1718000000000,
  "fileHash": "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"
}
```

---

## Important Considerations

### ⚠️ Caveats

1. **File Content Changes**
   - If a file is modified (but not renamed), it gets a NEW hash
   - The modified version will be re-indexed
   - Old version remains in Chroma (consider cleanup)

2. **File Deletions**
   - Deleting a file from `financial-docs/` doesn't remove it from Chroma
   - The hash remains in `.document-hashes`
   - Use `uploadService.resetAndReload()` if needed

3. **Hash File Loss**
   - If `.document-hashes` is deleted manually:
     - Next startup will re-index everything
     - Duplicates will be created in Chroma
   - Solution: Delete collection and reload

4. **Concurrent Modifications**
   - Adding files to `financial-docs/` while app is running:
     - Won't be picked up until next restart
     - No hot-reload capability

### ✅ Best Practices

1. **Backup Strategy**
   ```bash
   # Keep backup of .document-hashes
   cp .document-hashes .document-hashes.backup
   ```

2. **Clean Slate**
   ```bash
   # If you need to reset everything:
   # 1. Delete the tracking file
   rm .document-hashes
   # 2. Call API to reset collection
   curl -X POST http://localhost:8080/api/reset
   # 3. Restart application
   ```

3. **Monitoring**
   - Check logs for skip counts
   - Monitor duplicate skips in startup logs
   - Track "Skipped (duplicates)" count

---

## Performance Impact

### Time Complexity
- **Startup**: O(n) where n = number of files
  - Read hashes: ~1ms per file
  - Calculate hashes: ~10-100ms per file (depends on size)
  - Total: ~50-200ms for typical setups
  
### Space Complexity
- **Memory**: O(n) for the hash set
  - ~50 bytes per hash entry
  - For 1000 files: ~50KB memory
  
- **Disk**: O(n) for tracking file
  - ~33 bytes per hash (32 hex chars + newline)
  - For 1000 files: ~33KB on disk

### Optimization Insights
- Hash calculation is I/O bound, not CPU bound
- Set membership check is O(1)
- File appending is efficient (no rewrites)

---

## API Endpoints

### Reset Vector Store (Clear Everything)
```bash
POST /api/upload/reset
Response: "Vector store reset successfully."
```

This:
1. Deletes the entire Chroma collection
2. Resets collection to empty state
3. Does NOT delete `.document-hashes`
4. Next restart will re-index all files

### Force Reload (Reindex Everything)
```bash
# Called internally or manually via code
documentLoaderService.reloadDocuments()
```

This:
1. Clears in-memory hash cache
2. Deletes `.document-hashes` file
3. Reloads all documents
4. ⚠️ Creates duplicates in Chroma if not cleared first

---

## Troubleshooting

### Issue: All Files Being Re-indexed Every Startup
**Symptom**: "Skipped (duplicates): 0" every time

**Solutions**:
1. Check if `.document-hashes` file exists
2. Verify file has read/write permissions
3. Check Java has permission to write in project root
4. Check logs for `saveDocumentHash` errors

### Issue: Some Files Keep Re-indexing
**Symptom**: Inconsistent skip counts

**Solutions**:
1. Check if files are being modified
2. File modification = new MD5 hash
3. Save modified files before restart
4. Use `resetAndReload()` if inconsistency persists

### Issue: "Skipped" But Documents Still Missing
**Symptom**: Files marked skipped but not in Chroma

**Causes**:
1. Collection was reset externally
2. Documents were deleted from Chroma
3. Collection name mismatch

**Solution**: Call `resetAndReload()` to clear hash tracking and reload

---

## Summary

The duplicate checker provides:
- ✅ **One-time indexing** of documents from financial-docs
- ✅ **Persistent tracking** via `.document-hashes` file
- ✅ **Fast startup** by skipping known files
- ✅ **MD5 content-based detection** (name-independent)
- ✅ **Graceful handling** of new/modified files
- ✅ **Force reload option** when needed

**Result**: Application can restart 100 times without creating duplicates in Chroma! 🎉
