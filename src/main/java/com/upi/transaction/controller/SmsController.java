package com.upi.transaction.controller;

import com.upi.transaction.dto.ParsedTransaction;
import com.upi.transaction.service.BalanceService;
import com.upi.transaction.service.DailyAmountTracker;
import com.upi.transaction.service.SmsParserService;
import com.upi.transaction.service.TelegramService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SmsController {

    private final SmsParserService smsParserService;
    private final BalanceService balanceService;
    private final TelegramService telegramService;
    private final DailyAmountTracker tracker;

    public SmsController(SmsParserService smsParserService,
                         BalanceService balanceService,
                         TelegramService telegramService,DailyAmountTracker tracker) {
        this.smsParserService = smsParserService;
        this.balanceService = balanceService;
        this.telegramService = telegramService;
        this.tracker = tracker;
    }

    @GetMapping("/test")
    public String test(){
        return "Hi";
    }

    @PostMapping("/sync")
    public ResponseEntity<?> setBalance(@RequestBody JsonNode node) throws Exception {

        BigDecimal newBalance;
        BigDecimal current;
        BigDecimal previousAmount = balanceService.getCurrentBalance();
        String text = node.path("message").path("text").asText();
        try{
            BigDecimal amount = new BigDecimal(text);
            newBalance = balanceService.syncBalance(amount);
            current = tracker.alter(previousAmount,amount);
            telegramService.notifyBalance(newBalance);
            return ResponseEntity.ok(Map.of(
                    "balance",newBalance,
                        "tracker",current
            ));
        }
        catch (Exception e){
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
        switch (parsed.type()) {
            case UPI_SENT -> {
                newBalance = balanceService.deduct(parsed.amount());
                current = tracker.deduct(parsed.amount());
                telegramService.notifyTransaction("SENT", parsed.amount(), parsed.upiId(), newBalance);
            }
            case UPI_RECEIVED -> {
                newBalance = balanceService.add(parsed.amount());
                current = tracker.add(parsed.amount());
                telegramService.notifyTransaction("RECEIVED", parsed.amount(), parsed.upiId(), newBalance);
            }
            case CASH_DEPOSIT -> {
                newBalance = balanceService.syncBalance(parsed.bankBalance());
                current = tracker.add(parsed.amount());
                System.out.println("Balance synced to: ₹" + newBalance);
            }
            default -> {
                current = tracker.getCurrentAmount();
                newBalance = balanceService.getCurrentBalance();
            }
        }

        return ResponseEntity.ok(Map.of(
                "status", "processed",
                "type", parsed.type().name(),
                "amount", parsed.amount().toString(),
                "balance", newBalance.toString(),
                "tracker_current",current.toString()
        ));
    }
}