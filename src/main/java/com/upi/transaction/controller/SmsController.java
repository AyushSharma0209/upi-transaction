package com.upi.transaction.controller;

import com.upi.transaction.dto.ParsedTransaction;
import com.upi.transaction.entity.PendingCategorization;
import com.upi.transaction.entity.Transaction;
import com.upi.transaction.repository.PendingCategorizationRepository;
import com.upi.transaction.repository.TransactionRepository;
import com.upi.transaction.service.BalanceService;
import com.upi.transaction.service.CategorizationService;
import com.upi.transaction.service.DailyAmountTracker;
import com.upi.transaction.service.SmsParserService;
import com.upi.transaction.service.TelegramService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SmsController {
    private final PendingCategorizationRepository pendingRepo;
    private final SmsParserService smsParserService;
    private final BalanceService balanceService;
    private final TelegramService telegramService;
    private final DailyAmountTracker tracker;
    private final TransactionRepository transactionRepository;
    private final CategorizationService categorizationService;

    public SmsController(SmsParserService smsParserService,
                         BalanceService balanceService,
                         TelegramService telegramService,
                         DailyAmountTracker tracker,
                         TransactionRepository transactionRepository,
                         CategorizationService categorizationService,PendingCategorizationRepository pendingRepo) {
        this.smsParserService = smsParserService;
        this.balanceService = balanceService;
        this.telegramService = telegramService;
        this.tracker = tracker;
        this.transactionRepository = transactionRepository;
        this.categorizationService = categorizationService;
        this.pendingRepo = pendingRepo;
    }

    @GetMapping("/test")
    public String test() {
        return "Hi";
    }

    @PostMapping("/sync")
    public ResponseEntity<?> setBalance(@RequestBody JsonNode node) throws Exception {
        BigDecimal newBalance;
        BigDecimal current;
        BigDecimal previousAmount = balanceService.getCurrentBalance();
        String text = node.path("message").path("text").asText();
        try {
            BigDecimal amount = new BigDecimal(text);
            newBalance = balanceService.syncBalance(amount);
            current = tracker.alter(previousAmount, amount);
            telegramService.notifyBalance(newBalance);
            return ResponseEntity.ok(Map.of(
                    "balance", newBalance,
                    "tracker", current
            ));
        } catch (Exception e) {
            throw new Exception(e);
        }
    }

    @PostMapping("/sms")
    public ResponseEntity<?> receiveSms(@RequestBody Map<String, String> request) {
        String smsBody = request.get("smsBody");

        if (smsBody == null || smsBody.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error"));
        }

        ParsedTransaction parsed = smsParserService.parse(smsBody);

        if (parsed == null) {
            System.out.println("UNRECOGNIZED SMS: " + smsBody);
            return ResponseEntity.ok(Map.of("status", "unrecognized"));
        }

        BigDecimal newBalance;
        BigDecimal current;

        if (parsed.balanceAfter() != null) {
            newBalance = balanceService.syncBalance(parsed.balanceAfter());
        } else {
            switch (parsed.direction()) {
                case DEBIT -> newBalance = balanceService.deduct(parsed.amount());
                case CREDIT -> newBalance = balanceService.add(parsed.amount());
                default -> newBalance = balanceService.getCurrentBalance();
            }
        }

        switch (parsed.direction()) {
            case DEBIT -> current = tracker.deduct(parsed.amount());
            case CREDIT -> current = tracker.add(parsed.amount());
            default -> current = tracker.getCurrentAmount();
        }

        // Resolve category via CategorizationService (Tier 1 mapping → Tier 2 fuzzy + LLM)
        CategorizationService.Result catResult = categorizationService.resolve(
                parsed.counterparty(), parsed.amount(), parsed.paymentMethod().name());

// -------- Branch A: auto-resolved → write to ledger + notify normally --------
        if (!catResult.needsApproval()) {
            Transaction txn = new Transaction();
            txn.setDate(parseDate(parsed.date()));
            txn.setDirection(parsed.direction());
            txn.setAmount(parsed.amount());
            txn.setPaymentMethod(parsed.paymentMethod());
            txn.setCounterpartyRaw(parsed.counterparty());
            txn.setCounterparty(catResult.displayName());
            txn.setCategory(catResult.category());
            txn.setReference(parsed.referenceId());
            txn.setBalance(newBalance);
            transactionRepository.save(txn);

            telegramService.notifyTransaction(parsed, newBalance);

            return ResponseEntity.ok(Map.of(
                    "status", "processed",
                    "direction", parsed.direction().name(),
                    "amount", parsed.amount().toString(),
                    "balance", newBalance.toString(),
                    "category", catResult.category(),
                    "categorySource", catResult.source()
            ));
        }

// -------- Branch B: uncertain → write to pending, ask via Telegram --------
        PendingCategorization pending = new PendingCategorization();
        pending.setRawCounterparty(parsed.counterparty());
        pending.setDirection(parsed.direction());
        pending.setAmount(parsed.amount());
        pending.setPaymentMethod(parsed.paymentMethod());
        pending.setReference(parsed.referenceId());
        pending.setDate(parseDate(parsed.date()));
        pending.setBalanceAfter(newBalance);
        pending = pendingRepo.save(pending);   // save first to get the id

        telegramService.notifyForApproval(pending);


        return ResponseEntity.ok(Map.of(
                "status", "pending",
                "pending_id", pending.getId().toString(),
                "direction", parsed.direction().name(),
                "amount", parsed.amount().toString(),
                "balance", newBalance.toString()
        ));
    }

    /**
     * Bank SMS dates come as dd-MM-yy (regex path). LLM-parsed dates can vary.
     * Falls back to today's date if parsing fails, rather than throwing.
     */
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return LocalDate.now();
        }
        String[] patterns = {"dd-MM-yy", "dd-MM-yyyy", "yyyy-MM-dd", "dd/MM/yy", "dd/MM/yyyy"};
        for (String pattern : patterns) {
            try {
                return LocalDate.parse(dateStr, DateTimeFormatter.ofPattern(pattern));
            } catch (DateTimeParseException ignored) {
                // try next pattern
            }
        }
        System.err.println("Could not parse date: " + dateStr + " — defaulting to today");
        return LocalDate.now();
    }
}