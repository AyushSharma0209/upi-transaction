package com.upi.transaction.controller;

import com.upi.transaction.service.CategorizationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Buildathon-scoped test endpoints.
 * Bypasses SMS ingestion + balance + Telegram; hits only the parts
 * of the pipeline we want to measure. Not used by production SMS flow.
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final CategorizationService categorizationService;

    public EvalController(CategorizationService categorizationService) {
        this.categorizationService = categorizationService;
    }

    /**
     * Categorization only (Tier 1..5). Does NOT write to transactions or fire Telegram.
     *
     * NOTE: still writes learned mappings when Tier 3 (MERCHANT) or Tier 5 (LLM >= 0.85)
     * resolves — because we call the same resolve() method production uses. For a
     * one-shot measurement this is honest; re-running would show near-100% MAPPED.
     */
    @PostMapping("/categorize")
    public ResponseEntity<?> categorize(@RequestBody Map<String, Object> req) {
        String counterparty = (String) req.get("counterparty");
        BigDecimal amount = new BigDecimal(req.getOrDefault("amount", "0").toString());
        String paymentMethod = (String) req.getOrDefault("paymentMethod", "UPI");

        CategorizationService.Result result =
                categorizationService.resolve(counterparty, amount, paymentMethod);

        Map<String, Object> resp = new HashMap<>();
        resp.put("counterparty", counterparty);
        resp.put("displayName", result.displayName());
        resp.put("category", result.category());
        resp.put("source", result.source());  // MAPPED | MERCHANT | LLM | PENDING
        resp.put("needsApproval", result.needsApproval());
        resp.put("confidence", result.topGuess() != null ? result.topGuess().confidence() : null);
        return ResponseEntity.ok(resp);
    }
}