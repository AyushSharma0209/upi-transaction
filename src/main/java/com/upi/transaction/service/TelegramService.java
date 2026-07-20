// src/main/java/com/upi/transaction/service/TelegramService.java

package com.upi.transaction.service;

import com.upi.transaction.config.AppConfig;
import com.upi.transaction.dto.ParsedTransaction;
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