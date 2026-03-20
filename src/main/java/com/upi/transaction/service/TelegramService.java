// src/main/java/com/upi/transaction/service/TelegramService.java

package com.upi.transaction.service;

import com.upi.transaction.config.AppConfig;
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
        webClient.get()
                .uri("/bot{token}/sendMessage?chat_id={chatId}&text={text}&parse_mode=Markdown",
                        botToken, chatId, text)
                .retrieve()
                .bodyToMono(String.class)
                .doOnError(e -> System.err.println("Telegram send failed: " + e.getMessage()))
                .subscribe();
    }
    public void notifyTally(String text) {
        System.out.println("token"+trackToken);
        System.out.println("token"+trackId);
        webClient.get()
                .uri("/bot{token}/sendMessage?chat_id={chatId}&text={text}&parse_mode=Markdown",
                        trackToken, trackId, text)
                .retrieve()
                .bodyToMono(String.class)
                .doOnError(e -> System.err.println("Telegram send failed: " + e.getMessage()))
                .subscribe();
    }

    public void notifyBalance(BigDecimal balance){
        String message = String.format("💰 Balance Updated!\n\nUpdated Balance: *₹%s*", balance);
        sendMessage(message);
    }

    public void notifyTransaction(String type, BigDecimal amount, String upiId, BigDecimal balance) {
        String emoji = type.equals("SENT") ? "🔴" : "🟢";
        String direction = type.equals("SENT") ? "Paid to" : "Received from";

        String message = String.format(
                "%s *%s* ₹%s\n%s: `%s`\n\n💰 Balance: *₹%s*",
                emoji, direction, amount, direction.split(" ")[0], upiId, balance
        );

        sendMessage(message);
    }
}