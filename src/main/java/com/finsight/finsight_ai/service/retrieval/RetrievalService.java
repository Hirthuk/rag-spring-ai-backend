package com.finsight.finsight_ai.service.retrieval;

import com.finsight.finsight_ai.service.DocumentLoaderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Retrieves relevant documents from the vector store.
 *
 * Key strategies:
 *  1. Native Chroma filterExpression — isolation enforced at DB level (no Java post-filter).
 *  2. Similarity threshold (0.30) — drops low-quality matches before they reach the LLM.
 *  3. Per-file chunk cap (MAX_CHUNKS_PER_FILE) — prevents any single document from
 *     consuming the entire context window, ensuring breadth over depth.
 *  4. Multi-entity parallel search — for comparison queries ("Infosys vs Presidio"),
 *     runs a focused sub-query per detected entity so both companies are represented
 *     in the result set regardless of which embedding the combined query drifts toward.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetrievalService {

    private final VectorStore vectorStore;

    // Threshold for the raw user query — higher bar to keep noise out of context
    private static final double SIMILARITY_THRESHOLD        = 0.30;
    // Threshold for per-entity enriched sub-queries ("HCL revenue profit...").
    // Lower because the enrichment already anchors the embedding in financial space,
    // so a wider net is safe and necessary when Cohere cosine values skew low.
    private static final double ENTITY_SIMILARITY_THRESHOLD = 0.15;
    private static final int MAX_CHUNKS_PER_FILE    = 3;
    private static final int TOP_K_PER_ENTITY       = 5;
    private static final int TOP_K_COMBINED         = 8;
    private static final int TOP_K_SINGLE           = 10;
    private static final int MAX_TOTAL_DOCS         = 10;

    /**
     * Words that can appear capitalised in a query (first word of sentence, common English
     * words the user typed with a capital) but are NOT company/entity names.
     * Used to filter out false positives during entity extraction.
     */
    private static final Set<String> NON_ENTITY_WORDS = Set.of(
            // question / request starters
            "tell", "what", "show", "give", "how", "who", "why", "which", "where", "when",
            "can", "could", "would", "should", "does", "did", "please", "help",
            // request adjectives — "Brief comparison", "Detailed analysis", "Quick overview"
            "brief", "detailed", "quick", "short", "long", "simple", "complete", "full",
            "deep", "broad", "comprehensive", "thorough", "clear", "exact", "accurate",
            // common verbs/connectors
            "compare", "comparison", "will", "has", "have", "had", "get", "find", "list",
            "provide", "calculate", "predict", "forecast", "analyze", "analyse",
            "between", "across", "among", "versus", "against",
            // financial terms
            "revenue", "profit", "growth", "margin", "earnings", "ebitda", "cagr",
            "financial", "performance", "analysis", "percentage",
            "market", "share", "capital", "investment", "returns", "trend", "trends",
            "number", "numbers", "ratio", "ratios",
            // query qualifiers
            "both", "all", "the", "its", "their", "our", "your", "any", "some", "more",
            "much", "many", "last", "next", "latest", "annual", "quarterly",
            "best", "worst", "top", "bottom", "high", "low", "strong", "weak",
            "winner", "loser", "better", "worse", "higher", "lower",
            // generic nouns
            "company", "companies", "firms", "firm", "business", "businesses",
            "sector", "industry", "industries", "segment", "segments",
            "year", "years", "quarter", "quarters", "decade",
            "data", "report", "reports", "result", "results",
            "following", "current", "recent", "future", "past", "historical",
            "interms", "terms", "detail", "details", "overview", "summary", "info",
            "information", "insight", "insights", "breakdown", "format"
    );

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public List<Document> retrieveRelevantDocuments(String query) {
        try {
            String currentUserId = getCurrentUserId();
            Filter.Expression filter = buildUserFilter(currentUserId);

            List<String> entities = extractEntityNames(query);
            List<Document> result;

            if (entities.size() >= 2) {
                // Comparison/multi-entity query — fetch per entity + combined
                result = multiEntitySearch(query, entities, filter);
            } else {
                // Single or zero entities — enrich with the detected entity name (if any)
                // so that queries like "Presidio trajectory in 2040" still hit financial data
                // (the raw query's embedding drifts toward future/speculative content and
                // can fall below the similarity threshold against historical data chunks)
                result = singleSearch(query, entities.isEmpty() ? null : entities.get(0), filter);
            }

            log.info("[RAG] {} docs retrieved for userId={} | entities={}",
                    result.size(), currentUserId, entities.isEmpty() ? "none" : entities);
            return result;

        } catch (Exception e) {
            log.warn("Vector store search failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // -------------------------------------------------------------------------
    // Search strategies
    // -------------------------------------------------------------------------

    /**
     * For comparison queries: runs a focused sub-query per entity to ensure each company
     * gets representation, then supplements with the full original query to catch any
     * cross-entity chunks the sub-queries might have missed.
     */
    private List<Document> multiEntitySearch(String query,
                                             List<String> entities,
                                             Filter.Expression filter) {
        // Ordered insertion — per-entity results first (highest relevance per company)
        Map<String, Integer> chunksByFile = new LinkedHashMap<>();
        List<Document> merged              = new ArrayList<>();
        Set<String>    seenIds             = new LinkedHashSet<>();

        for (String entity : entities) {
            // Enrich the sub-query with financial domain terms so the embedding lands
            // closer to financial-data chunks rather than generic company overview text.
            // Uses ENTITY_SIMILARITY_THRESHOLD (lower) — the enrichment already constrains
            // relevance, so a wider net is needed to catch all company-specific chunks.
            String subQuery = entity + " revenue profit net margin financial performance growth";
            for (Document doc : rawSearch(subQuery, filter, TOP_K_PER_ENTITY, ENTITY_SIMILARITY_THRESHOLD)) {
                addIfAllowed(doc, merged, seenIds, chunksByFile);
            }
        }

        // Supplement with a combined query that names ALL detected entities.
        // Using the raw user query here is wrong — it matches "comparison", "financial",
        // "analysis" terms that are common to every company document in the collection,
        // pulling in TCS / HDFC / Infosys / Reliance even when the user only asked about
        // HCL and Presidio.  A focused query keeps the context clean.
        String focusedCombined = String.join(" vs ", entities)
                + " revenue profit margin growth financial performance comparison";
        for (Document doc : rawSearch(focusedCombined, filter, TOP_K_COMBINED, ENTITY_SIMILARITY_THRESHOLD)) {
            addIfAllowed(doc, merged, seenIds, chunksByFile);
        }

        return merged.size() > MAX_TOTAL_DOCS ? merged.subList(0, MAX_TOTAL_DOCS) : merged;
    }

    /**
     * Single-entity or general search.
     *
     * When an entity name is present (e.g. "Presidio"), we run an enriched sub-query
     * first — "<entity> revenue profit net margin financial performance growth" — which
     * produces an embedding that lands squarely in the financial-data neighbourhood of
     * the vector space.  The raw user query is then used to supplement, catching any
     * contextual chunks the enriched query might have missed.
     *
     * Without enrichment, queries that mention future dates ("trajectory in 2040"),
     * sentiment ("how is Presidio doing?"), or non-financial framing produce embeddings
     * that drift away from historical financial data and fall below the similarity
     * threshold — returning 0 documents even when relevant data exists.
     */
    private List<Document> singleSearch(String query, String entity, Filter.Expression filter) {
        Map<String, Integer> chunksByFile = new LinkedHashMap<>();
        List<Document>       merged       = new ArrayList<>();
        Set<String>          seenIds      = new LinkedHashSet<>();

        // Pass 1 — enriched sub-query anchored to financial terminology.
        // Uses TOP_K_SINGLE (not TOP_K_PER_ENTITY) for a wider net: user-uploaded files
        // for lesser-known companies (e.g. "Apex") score lower than dense SYSTEM docs
        // (Infosys/TCS/HDFC) on generic financial terms, so with topK=5 the user's file
        // was pushed past position 5 and dropped.  topK=10 catches it.
        if (entity != null) {
            String enriched = entity + " revenue profit net margin financial performance growth";
            for (Document doc : rawSearch(enriched, filter, TOP_K_SINGLE, ENTITY_SIMILARITY_THRESHOLD)) {
                addIfAllowed(doc, merged, seenIds, chunksByFile);
            }
        }

        // Pass 2 — original user query; catches contextual chunks the enriched pass missed.
        for (Document doc : rawSearch(query, filter, TOP_K_SINGLE, SIMILARITY_THRESHOLD)) {
            addIfAllowed(doc, merged, seenIds, chunksByFile);
        }

        return merged.size() > MAX_TOTAL_DOCS ? merged.subList(0, MAX_TOTAL_DOCS) : merged;
    }

    /**
     * Base Chroma search with a caller-supplied similarity threshold and user-isolation filter.
     *
     * Two thresholds are in use:
     *   ENTITY_SIMILARITY_THRESHOLD (0.15) — for enriched per-entity sub-queries where
     *     the query is already anchored to financial terms; a wider net is needed.
     *   SIMILARITY_THRESHOLD (0.30) — for raw user queries where a tighter bar keeps noise out.
     */
    private List<Document> rawSearch(String query, Filter.Expression filter, int topK, double threshold) {
        try {
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .similarityThreshold(threshold)
                            .filterExpression(filter)
                            .build()
            );
            return results != null ? results : new ArrayList<>();
        } catch (Exception e) {
            log.warn("[RAG] rawSearch failed (query='{}...'): {}", abbreviate(query, 40), e.getMessage());
            return new ArrayList<>();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Adds a document only if its ID is new and the per-file chunk cap isn't exceeded. */
    private void addIfAllowed(Document doc,
                               List<Document> target,
                               Set<String> seenIds,
                               Map<String, Integer> chunksByFile) {
        String id = docId(doc);
        if (!seenIds.add(id)) return;

        String file  = fileName(doc);
        int    count = chunksByFile.getOrDefault(file, 0);
        if (count >= MAX_CHUNKS_PER_FILE) return;

        target.add(doc);
        chunksByFile.put(file, count + 1);
    }

    /** Apply the per-file chunk cap to a flat list (used for single-entity searches). */
    private List<Document> diversify(List<Document> docs) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Document>       result = new ArrayList<>();
        for (Document doc : docs) {
            String file  = fileName(doc);
            int    count = counts.getOrDefault(file, 0);
            if (count < MAX_CHUNKS_PER_FILE) {
                result.add(doc);
                counts.put(file, count + 1);
            }
        }
        return result;
    }

    /**
     * Extracts likely entity (company) names from the query by finding runs of
     * consecutive tokens that start with an uppercase letter and are not in the
     * NON_ENTITY_WORDS set.  Consecutive such tokens are joined as a compound name
     * (e.g., "HCL Technologies" rather than two separate entries).
     *
     * Examples:
     *   "Compare Presidio and HCL Technologies" → ["Presidio", "HCL Technologies"]
     *   "Tell me the Percentage of growth"       → []   (all filtered by NON_ENTITY_WORDS)
     */
    private List<String> extractEntityNames(String query) {
        if (query == null || query.isBlank()) return List.of();

        List<String>   entities    = new ArrayList<>();
        List<String>   currentRun  = new ArrayList<>();
        String[]       tokens      = query.trim().split("[\\s,]+");

        for (String token : tokens) {
            String clean = token.replaceAll("[^a-zA-Z0-9]", "");
            if (clean.length() < 2) { flushRun(currentRun, entities); continue; }

            boolean startsUpper = Character.isUpperCase(clean.charAt(0));
            boolean isStopword  = NON_ENTITY_WORDS.contains(clean.toLowerCase());

            if (startsUpper && !isStopword) {
                currentRun.add(clean);
            } else {
                flushRun(currentRun, entities);
            }
        }
        flushRun(currentRun, entities);
        return entities;
    }

    private void flushRun(List<String> run, List<String> target) {
        if (!run.isEmpty()) {
            target.add(String.join(" ", run));
            run.clear();
        }
    }

    private Filter.Expression buildUserFilter(String currentUserId) {
        var b = new FilterExpressionBuilder();
        if (currentUserId != null) {
            return b.in("userEmail", DocumentLoaderService.SYSTEM_USER_ID, currentUserId).build();
        }
        return b.eq("userEmail", DocumentLoaderService.SYSTEM_USER_ID).build();
    }

    private String getCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                return jwt.getSubject();
            }
        } catch (Exception e) {
            log.debug("Could not extract user ID from security context: {}", e.getMessage());
        }
        return null;
    }

    private static String docId(Document doc) {
        return doc.getId() != null ? doc.getId() : doc.getText().substring(0, Math.min(60, doc.getText().length()));
    }

    private static String fileName(Document doc) {
        Object fn = doc.getMetadata().get("fileName");
        return fn != null ? fn.toString() : "unknown";
    }

    private static String abbreviate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    public String prepareRetrievalQuery(String userMessage) {
        return userMessage;
    }
}
