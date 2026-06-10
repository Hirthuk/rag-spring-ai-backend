# Duplicate Checker - Quick Start

## What It Does ✅
Prevents the same documents from being added to Chroma multiple times when you restart the application.

## How to Use

### Default Behavior (Recommended)
```bash
# Just start the app normally
mvn spring-boot:run
# or
java -jar target/finsight-ai-0.0.1-SNAPSHOT.jar
```

**What happens:**
- First run: Loads all financial-docs files into Chroma ✅
- Subsequent runs: Skips existing files (duplicates prevented) ⏭️
- New files: Auto-detected and indexed 🆕

### Force Full Reload (Clear & Reload)
```bash
# 1. Delete the tracking file
rm .document-hashes

# 2. Call reset API to clear Chroma
curl -X POST http://localhost:8080/api/upload/reset

# 3. Restart application
mvn spring-boot:run
```

## Check What's Happening 📊

Look for these messages in logs:

```
✅ First run:
Loaded 0 document hashes from tracking file
Found 5 file(s) in financial-docs folder
✅ Successfully indexed 150 documents from companies.xlsx
✅ Successfully indexed 45 documents from data.csv
DOCUMENT LOADER FINISHED
Total files: 5, Successful: 5, Failed: 0, Skipped (duplicates): 0

✅ Second run (no changes):
Loaded 5 document hashes from tracking file
Found 5 file(s) in financial-docs folder
⏭️ Skipping duplicate file: companies.xlsx (already in Chroma)
⏭️ Skipping duplicate file: data.csv (already in Chroma)
DOCUMENT LOADER FINISHED
Total files: 5, Successful: 0, Failed: 0, Skipped (duplicates): 5

✅ After adding new file:
Loaded 5 document hashes from tracking file
Found 6 file(s) in financial-docs folder
⏭️ Skipping duplicate file: companies.xlsx
✅ Successfully indexed 30 documents from new_file.json
DOCUMENT LOADER FINISHED
Total files: 6, Successful: 1, Failed: 0, Skipped (duplicates): 5
```

## Files Created 📁

**.document-hashes** (auto-created in project root)
```
Contains MD5 hashes of all indexed files
One hash per line (example):
a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6
b5c6d7e8f9g0h1i2j3k4l5m6n7o8p9q0
```

This file should be added to `.gitignore`:
```bash
echo ".document-hashes" >> .gitignore
```

## Scenarios 🎯

| Scenario | Action | Result |
|----------|--------|--------|
| First startup | Just start app | All docs indexed ✅ |
| Restart (no changes) | Just start app | Duplicates skipped ⏭️ |
| Add new file | Restart app | New file indexed 🆕 |
| Modify existing file | Restart app | Re-indexed as new 🔄 |
| Want fresh start | Delete `.document-hashes` + Reset API | Everything reloaded 🔄 |

## Advanced: Code Usage

### Force Reload Programmatically
```java
@Autowired
private DocumentLoaderService documentLoaderService;

// Force reload everything
documentLoaderService.reloadDocuments();
```

### Reset Collection + Reload
```java
@Autowired
private UploadService uploadService;

// This clears Chroma collection entirely
uploadService.resetAndReload();
```

## Troubleshooting 🔧

**Q: Files keep being re-indexed**
- A: Check if file is being modified → gives new hash
- Solution: Save files before restarting

**Q: Duplicate files in Chroma**
- A: `.document-hashes` was deleted or corrupted
- Solution: Delete `.document-hashes` + call reset API + restart

**Q: "Skipped" but doc not found**
- A: Collection was cleared but hash tracking still exists
- Solution: Call reset API to clear collection

## Key Points 🔑

1. ✅ **Automatic** - No configuration needed
2. ✅ **Persistent** - Survives app restarts
3. ✅ **Content-based** - Works with renamed files
4. ✅ **Smart** - Detects modified files (new hash)
5. ✅ **Safe** - Won't create duplicates

---

See `DUPLICATE_CHECKER_GUIDE.md` for detailed documentation.
