package com.upi.transaction.controller;

import com.upi.transaction.entity.CounterpartyMapping;
import com.upi.transaction.entity.PendingCategorization;
import com.upi.transaction.entity.Transaction;
import com.upi.transaction.repository.CounterpartyMappingRepository;
import com.upi.transaction.repository.PendingCategorizationRepository;
import com.upi.transaction.repository.TransactionRepository;
import com.upi.transaction.service.TelegramService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/internal")
public class PendingController {

    private final PendingCategorizationRepository pendingRepo;
    private final TransactionRepository transactionRepo;
    private final CounterpartyMappingRepository mappingRepo;
    private final TelegramService telegramService;

    public PendingController(PendingCategorizationRepository pendingRepo,
                             TransactionRepository transactionRepo,
                             CounterpartyMappingRepository mappingRepo,
                             TelegramService telegramService) {
        this.pendingRepo     = pendingRepo;
        this.transactionRepo = transactionRepo;
        this.mappingRepo     = mappingRepo;
        this.telegramService = telegramService;
    }

    /** Body: {"pending_id": 1, "category_code": "trans"}
     *  Called by the Python agent-bot when the user taps a category button. */
    @PostMapping("/resolve-pending")
    @Transactional
    public ResponseEntity<?> resolve(@RequestBody Map<String, Object> req) {
        Long pendingId = Long.valueOf(req.get("pending_id").toString());
        String code = (String) req.get("category_code");

        // Look up the full category name from the code
        String category = TelegramService.CATEGORIES.get(code);
        if (category == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown category code: " + code));
        }

        Optional<PendingCategorization> opt = pendingRepo.findById(pendingId);
        if (opt.isEmpty()) {
            // Already resolved by an earlier tap. Idempotent — just report ok.
            return ResponseEntity.ok(Map.of("status", "already_resolved"));
        }
        PendingCategorization p = opt.get();

        // 1. Materialize into the transactions ledger
        Transaction txn = new Transaction();
        txn.setDate(p.getDate());
        txn.setDirection(p.getDirection());
        txn.setAmount(p.getAmount());
        txn.setPaymentMethod(p.getPaymentMethod());
        txn.setCounterpartyRaw(p.getRawCounterparty());
        txn.setCounterparty(p.getRawCounterparty());   // bank name IS the display name
        txn.setCategory(category);
        txn.setReference(p.getReference());
        txn.setBalance(p.getBalanceAfter());
        transactionRepo.save(txn);

        // 2. Upsert counterparty_mappings so future same-raw hits Tier 1 instantly
        //    (Skip if user chose "Skip / Uncategorized" — we don't want to poison future matches)
        if (!"skip".equals(code)) {
            mappingRepo.save(new CounterpartyMapping(
                    p.getRawCounterparty(),
                    p.getRawCounterparty(),
                    category,
                    "USER"
            ));
        }

        // 3. Delete the pending row (source of truth is now transactions)
        Long msgId = p.getTelegramMessageId();
        pendingRepo.deleteById(pendingId);

        // 4. Send a fresh ✅ confirmation (simpler than editing)
        String emoji = p.getDirection() == com.upi.transaction.dto.ParsedTransaction.Direction.DEBIT ? "🔴" : "🟢";
        String verb  = p.getDirection() == com.upi.transaction.dto.ParsedTransaction.Direction.DEBIT ? "Paid to" : "Received from";
        String resolvedText = String.format(
                "✅ %s %s ₹%s%n%s: %s · %s%n%n💰 Balance: ₹%s",
                emoji, verb, p.getAmount(),
                verb.split(" ")[0], p.getRawCounterparty(), category,
                p.getBalanceAfter()
        );
        telegramService.sendMessage(resolvedText);

        return ResponseEntity.ok(Map.of(
                "status", "resolved",
                "transaction_id", txn.getId().toString(),
                "category", category
        ));
    }
}