// src/main/java/com/upi/transaction/config/AppConfig.java

package com.upi.transaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Configuration
@ConfigurationProperties(prefix = "app")
public class AppConfig {

    private Balance balance = new Balance();
    private Telegram telegram = new Telegram();
    private Bank bank = new Bank();
    private Sms sms = new Sms();

    // Getters and Setters
    public Balance getBalance() { return balance; }
    public void setBalance(Balance balance) { this.balance = balance; }
    public Telegram getTelegram() { return telegram; }
    public void setTelegram(Telegram telegram) { this.telegram = telegram; }
    public Bank getBank() { return bank; }
    public void setBank(Bank bank) { this.bank = bank; }
    public Sms getSms() { return sms; }
    public void setSms(Sms sms) { this.sms = sms; }

    public static class Balance {
        private BigDecimal initial = BigDecimal.ZERO;
        public BigDecimal getInitial() { return initial; }
        public void setInitial(BigDecimal initial) { this.initial = initial; }
    }

    public static class Telegram {
        private String botToken;
        private String chatId;
        public String getBotToken() { return botToken; }
        public void setBotToken(String botToken) { this.botToken = botToken; }
        public String getChatId() { return chatId; }
        public void setChatId(String chatId) { this.chatId = chatId; }
    }

    public static class Bank {
        private String accountId;
        public String getAccountId() { return accountId; }
        public void setAccountId(String accountId) { this.accountId = accountId; }
    }

    public static class Sms {
        private String sentPattern;
        private String receivedPattern;
        private String depositPattern;
        public String getSentPattern() { return sentPattern; }
        public void setSentPattern(String sentPattern) { this.sentPattern = sentPattern; }
        public String getReceivedPattern() { return receivedPattern; }
        public void setReceivedPattern(String receivedPattern) { this.receivedPattern = receivedPattern; }
        public String getDepositPattern() { return depositPattern; }
        public void setDepositPattern(String depositPattern) { this.depositPattern = depositPattern; }
    }
}