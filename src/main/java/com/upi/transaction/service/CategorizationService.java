package com.upi.transaction.service;

import com.upi.transaction.entity.CounterpartyMapping;
import com.upi.transaction.repository.CounterpartyMappingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upi.transaction.config.AppConfig;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CategorizationService {

    /** What we return to SmsController for every incoming txn. */
    public record Result(
            String displayName,     // save to txn.counterparty
            String category,        // save to txn.category (Uncategorized if needsApproval)
            String source,          // MAPPED | MERCHANT | LLM | PENDING
            boolean needsApproval,
            Candidate topGuess      // nullable — pre-fill for Telegram Day 3 flow
    ) {}

    public record Candidate(String displayName, String category, double confidence) {}

    private static final double AUTO_THRESHOLD = 0.85;   // >= this, auto-save mapping
    private static final double GUESS_THRESHOLD = 0.50;  // >= this, Telegram pre-fills guess
    private static final int TOP_N = 8;

    private final CounterpartyMappingRepository mappingRepo;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JaroWinklerSimilarity jw = new JaroWinklerSimilarity();

    /**
     * Merchant-substring allowlist. If the normalized UPI ID contains any KEY,
     * skip the LLM entirely and resolve to (displayName, category).
     * All keys must be UPPERCASE. Order doesn't matter (first substring hit wins).
     */
    private static final Map<String, String[]> MERCHANT_ALLOWLIST = Map.ofEntries(
            // Food & delivery
            Map.entry("SWIGGY",     new String[]{"Swiggy",       "Food & Dining"}),
            Map.entry("ZOMATO",     new String[]{"Zomato",       "Food & Dining"}),
            // Groceries / q-commerce
            Map.entry("BLINKIT",    new String[]{"Blinkit",      "Groceries"}),
            Map.entry("ZEPTO",      new String[]{"Zepto",        "Groceries"}),
            Map.entry("BIGBASKET",  new String[]{"BigBasket",    "Groceries"}),
            Map.entry("INSTAMART",  new String[]{"Instamart",    "Groceries"}),
            // Shopping
            Map.entry("AMAZON",     new String[]{"Amazon",       "Shopping"}),
            Map.entry("FLIPKART",   new String[]{"Flipkart",     "Shopping"}),
            Map.entry("MYNTRA",     new String[]{"Myntra",       "Shopping"}),
            Map.entry("MEESHO",     new String[]{"Meesho",       "Shopping"}),
            // Entertainment / subscriptions
            Map.entry("NETFLIX",    new String[]{"Netflix",      "Entertainment"}),
            Map.entry("HOTSTAR",    new String[]{"Hotstar",      "Entertainment"}),
            Map.entry("SPOTIFY",    new String[]{"Spotify",      "Subscriptions"}),
            Map.entry("YOUTUBE",    new String[]{"YouTube",      "Subscriptions"}),
            Map.entry("APPLE",      new String[]{"Apple Media Services", "Entertainment"}),
            Map.entry("AWS",        new String[]{"AWS",          "Subscriptions"}),
            // Telecom / utilities
            Map.entry("JIO",        new String[]{"Jio",          "Bills & Utilities"}),
            Map.entry("AIRTEL",     new String[]{"Airtel",       "Bills & Utilities"}),
            Map.entry("BSNL",       new String[]{"BSNL",         "Bills & Utilities"}),
            Map.entry("BESCOM",     new String[]{"BESCOM",       "Bills & Utilities"}),
            Map.entry("MSEB",       new String[]{"MSEB",         "Bills & Utilities"}),
            // Transport
            Map.entry("OLA",        new String[]{"Ola",          "Transport"}),
            Map.entry("UBER",       new String[]{"Uber",         "Transport"}),
            Map.entry("RAPIDO",     new String[]{"Rapido",       "Transport"}),
            Map.entry("IRCTC",      new String[]{"IRCTC",        "Transport"}),
            // Fuel
            Map.entry("INDIANOIL",  new String[]{"Indian Oil",   "Fuel & Petrol"}),
            Map.entry("HPCL",       new String[]{"HPCL",         "Fuel & Petrol"}),
            Map.entry("BPCL",       new String[]{"BPCL",         "Fuel & Petrol"}),
            // Health
            Map.entry("APOLLO",     new String[]{"Apollo",       "Health"}),
            Map.entry("PHARMEASY",  new String[]{"PharmEasy",    "Health"}),
            Map.entry("1MG",        new String[]{"1mg",          "Health"})
    );

    private static final String SYSTEM_PROMPT = """
            You match a payment identifier to an existing known entity.

            You will get:
              - the raw UPI ID (may be a phone number or garbled string)
              - a normalized guess extracted from that ID
              - up to 8 candidate entities ranked by string similarity

            Rules:
            - If a candidate CLEARLY refers to the same real-world entity, return
              that candidate's exact displayName and category with confidence 0.85-1.0.
            - If a candidate is a plausible but uncertain match (partial name,
              possible alias, common first-name only), return it with confidence
              between 0.50 and 0.84.
            - If NO candidate matches, return
              {"displayName": "<normalized>", "category": "Uncategorized", "confidence": 0.0}
            - NEVER invent a category. Only use one from the candidate list, or
              "Uncategorized".

            Return ONLY JSON: {"displayName":"...","category":"...","confidence":0.0}
            No markdown, no prose.
            """;

    public CategorizationService(AppConfig appConfig, CounterpartyMappingRepository mappingRepo) {
        this.mappingRepo = mappingRepo;
        this.webClient = WebClient.builder()
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("x-api-key", appConfig.getClaude().getApiKey())
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    // ---------------------------------------------------------------- pipeline

    public Result resolve(String rawUpiId, BigDecimal amount, String paymentMethod) {

        // ---- Step 1: exact match on raw ID (Tier 1) ------------------------
        Optional<CounterpartyMapping> exact = mappingRepo.findById(rawUpiId);
        if (exact.isPresent()) {
            CounterpartyMapping m = exact.get();
            return new Result(m.getDisplayName(), m.getCategory(), "MAPPED", false, null);
        }

        // ---- Step 2: normalize the UPI ID (Java, no LLM) -------------------
        String normalized = normalize(rawUpiId);

        // ---- Step 3: merchant allowlist shortcut (Java, no LLM) ------------
        for (Map.Entry<String, String[]> e : MERCHANT_ALLOWLIST.entrySet()) {
            if (normalized.contains(e.getKey())) {
                String display  = e.getValue()[0];
                String category = e.getValue()[1];
                saveMapping(rawUpiId, display, category, "MERCHANT");
                return new Result(display, category, "MERCHANT", false, null);
            }
        }

        // ---- Step 4: Jaro-Winkler top-N (Java, no LLM) ---------------------
        List<CounterpartyMapping> all = mappingRepo.findAll();
        List<Scored> topN = all.stream()
                .map(m -> new Scored(m, score(normalized, m)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(TOP_N)
                .toList();

        // Edge case: mappings table is empty (fresh install)
        if (topN.isEmpty()) {
            return new Result(normalized, "Uncategorized", "PENDING", true, null);
        }

        // ---- Step 5: Haiku picks (LLM) -------------------------------------
        Candidate pick;
        try {
            pick = callHaiku(rawUpiId, normalized, topN);
        } catch (Exception ex) {
            System.err.println("Categorization LLM call failed: " + ex.getMessage());
            return new Result(normalized, "Uncategorized", "PENDING", true, null);
        }

        // ---- Threshold decision --------------------------------------------
        if (pick.confidence() >= AUTO_THRESHOLD) {
            // auto-save; no Telegram
            saveMapping(rawUpiId, pick.displayName(), pick.category(), "LLM");
            return new Result(pick.displayName(), pick.category(), "LLM", false, null);
        }

        if (pick.confidence() >= GUESS_THRESHOLD) {
            // Telegram will fire with this pre-filled guess (Day 3 handles it)
            return new Result(normalized, "Uncategorized", "PENDING", true, pick);
        }

        // Full picker; no guess
        return new Result(normalized, "Uncategorized", "PENDING", true, null);
    }

    public void saveMapping(String raw, String displayName, String category, String source) {
        mappingRepo.save(new CounterpartyMapping(raw, displayName, category, source));
    }

    // ---------------------------------------------------------------- helpers

    /** Normalize a raw UPI ID or bank string:
     *  1. drop @domain
     *  2. replace ., _, digits with spaces
     *  3. uppercase
     *  4. collapse whitespace, trim
     */
    static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw;
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(0, at);
        s = s.replaceAll("[._0-9]+", " ");
        s = s.toUpperCase().replaceAll("\\s+", " ").trim();
        return s;
    }

    private record Scored(CounterpartyMapping mapping, double score) {}

    private double score(String normalized, CounterpartyMapping m) {
        double a = jw.apply(normalized, safeUpper(m.getDisplayName()));
        double b = jw.apply(normalized, safeUpper(m.getRawCounterparty()));
        return Math.max(a, b);
    }

    private static String safeUpper(String s) {
        return s == null ? "" : s.toUpperCase();
    }

    // ---------------------------------------------------------------- Haiku call

    private Candidate callHaiku(String rawUpiId, String normalized, List<Scored> topN) throws Exception {
        StringBuilder cands = new StringBuilder();
        int i = 1;
        for (Scored s : topN) {
            cands.append(String.format(
                    "  %d. \"%s\" | %s   (jw=%.2f)%n",
                    i++, s.mapping().getDisplayName(),
                    s.mapping().getCategory() == null ? "Uncategorized" : s.mapping().getCategory(),
                    s.score()));
        }

        String userContent = String.format(
                "Raw UPI ID:       %s%n" +
                        "Normalized:       %s%n" +
                        "Candidates:%n%s",
                rawUpiId, normalized, cands);

        var body = Map.of(
                "model", "claude-haiku-4-5-20251001",
                "max_tokens", 200,
                "system", SYSTEM_PROMPT,
                "messages", new Object[]{
                        Map.of("role", "user", "content", userContent)
                }
        );

        String raw = webClient.post()
                .uri("/v1/messages")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        JsonNode root = objectMapper.readTree(raw);
        String text = root.path("content").get(0).path("text").asText()
                .replaceAll("```json|```", "").trim();
        JsonNode parsed = objectMapper.readTree(text);

        return new Candidate(
                parsed.path("displayName").asText(normalized),
                parsed.path("category").asText("Uncategorized"),
                parsed.path("confidence").asDouble(0.0)
        );
    }
}