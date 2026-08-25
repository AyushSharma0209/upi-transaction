// src/main/java/com/upi/transaction/service/TelegramService.java

package com.upi.transaction.service;
import com.upi.transaction.config.AppConfig;
import com.upi.transaction.dto.ParsedTransaction;
import com.upi.transaction.entity.PendingCategorization;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;

@Service
public class TelegramService {

    private final String botToken;
    private final String chatId;
    private final WebClient webClient;
    private final String trackToken;
    private final String trackId;

    public TelegramService(AppConfig appConfig) {
        this.botToken = appConfig.getTelegram().getBotToken();
        this.chatId = appConfig.getTelegram().getChatId();
        this.trackId = appConfig.getTelegram().getTrackId();
        this.trackToken = appConfig.getTelegram().getTrackToken();
        this.webClient = WebClient.builder()
                .baseUrl("https://api.telegram.org")
                .build();
    }

    // Short codes keep callback_data under Telegram's 64-byte limit.
// Reverse-mapped by the resolve-pending endpoint on tap.

    public static final java.util.LinkedHashMap<String, String> CATEGORIES = new java.util.LinkedHashMap<>();
    static {
        CATEGORIES.put("food",    "Food & Dining");
        CATEGORIES.put("groc",    "Groceries");
        CATEGORIES.put("trans",   "Transport");
        CATEGORIES.put("fuel",    "Fuel & Petrol");
        CATEGORIES.put("shop",    "Shopping");
        CATEGORIES.put("ent",     "Entertainment");
        CATEGORIES.put("sub",     "Subscriptions");
        CATEGORIES.put("bills",   "Bills & Utilities");
        CATEGORIES.put("health",  "Health");
        CATEGORIES.put("inv",     "Investments");
        CATEGORIES.put("friends", "Friends");
        CATEGORIES.put("family",  "Family");
        CATEGORIES.put("chrg",    "Bank Charges");
        CATEGORIES.put("income",  "Income");
        CATEGORIES.put("stat",    "Stationery");
        CATEGORIES.put("barb",    "Barber");
        CATEGORIES.put("other",   "Other");
        CATEGORIES.put("skip",    "Uncategorized");
    }

    /** Sends the "pick a category" prompt with inline keyboard.
     *  Returns the Telegram message_id so we can editMessageText later. */

    /** Edit a previously-sent approval message to show it's resolved.
     *  Removes the inline keyboard (by omitting reply_markup). */
    public void editApprovalMessage(Long messageId, String newText) {
        if (messageId == null) return;

        var body = java.util.Map.of(
                "chat_id", chatId,
                "message_id", messageId,
                "text", newText
        );

        webClient.post()
                .uri("/bot" + botToken + "/editMessageText")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .doOnError(e -> System.err.println("editMessageText failed: " + e.getMessage()))
                .subscribe();
    }

    /** Fire-and-forget approval prompt with inline keyboard.
     *  Same pattern as sendMessage() — non-blocking, no timeout issues. */
    public void notifyForApproval(PendingCategorization pending) {
        String emoji = pending.getDirection() == ParsedTransaction.Direction.DEBIT ? "🔴" : "🟢";
        String verb  = pending.getDirection() == ParsedTransaction.Direction.DEBIT ? "Paid to" : "Received from";

        String text = String.format(
                "%s %s ₹%s%n%s: %s%n%n💰 Balance: ₹%s%n%n🤔 Pick a category:",
                emoji, verb, pending.getAmount(),
                verb.split(" ")[0], pending.getRawCounterparty(),
                pending.getBalanceAfter()
        );

        java.util.List<Object> keyboard = new java.util.ArrayList<>();
        java.util.List<Object> row = new java.util.ArrayList<>();
        int i = 0;
        for (var e : CATEGORIES.entrySet()) {
            row.add(java.util.Map.of(
                    "text", e.getValue(),
                    "callback_data", "pcat:" + pending.getId() + ":" + e.getKey()
            ));
            i++;
            if (i % 2 == 0) { keyboard.add(row); row = new java.util.ArrayList<>(); }
        }
        if (!row.isEmpty()) keyboard.add(row);

        var body = java.util.Map.of(
                "chat_id", chatId,
                "text", text,
                "reply_markup", java.util.Map.of("inline_keyboard", keyboard)
        );

        webClient.post()
                .uri("/bot" + botToken + "/sendMessage")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .doOnError(e -> System.err.println("notifyForApproval failed: " + e.getMessage()))
                .subscribe();
    }


    public void sendMessage(String text) {
        webClient.post()
                .uri("/bot" + botToken + "/sendMessage")
                .bodyValue(java.util.Map.of(
                        "chat_id", chatId,
                        "text", text
                ))
                .retrieve()
                .bodyToMono(String.class)
                .doOnError(e -> System.err.println("Telegram send failed: " + e.getMessage()))
                .subscribe();
    }

    public void notifyTally(String text) {
        webClient.post()
                .uri("/bot" + trackToken + "/sendMessage")
                .bodyValue(java.util.Map.of(
                        "chat_id", trackId,
                        "text", text
                ))
                .retrieve()
                .bodyToMono(String.class)
                .doOnError(e -> System.err.println("Telegram send failed: " + e.getMessage()))
                .subscribe();
    }

    public void notifyTransaction(ParsedTransaction parsed, BigDecimal balance) {
        String emoji = parsed.direction() == ParsedTransaction.Direction.DEBIT ? "🔴" : "🟢";
        String action = parsed.direction() == ParsedTransaction.Direction.DEBIT ? "Paid to" : "Received from";
        String method = parsed.paymentMethod().name();
        String counterparty = parsed.counterparty() != null ? parsed.counterparty() : "Unknown";

        String message = String.format(
                "%s %s ₹%s\n%s: %s\nMethod: %s\n\n💰 Balance: ₹%s",
                emoji, action, parsed.amount(),
                action.split(" ")[0], counterparty,
                method, balance
        );

        sendMessage(message);
    }

    public void notifyBalance(BigDecimal balance){
        String message = String.format("💰 Balance Updated!\n\nUpdated Balance: ₹%s", balance);
        sendMessage(message);
    }
}