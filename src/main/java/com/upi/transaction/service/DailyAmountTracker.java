package com.upi.transaction.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class DailyAmountTracker {
    private  BigDecimal currentAmount;

    private final TelegramService telegramService;
        DailyAmountTracker(TelegramService telegramService){
        this.currentAmount = BigDecimal.ZERO;
        this.telegramService = telegramService;
      }
    public BigDecimal deduct(BigDecimal amount){
        this.currentAmount = this.currentAmount.subtract(amount);
        return this.currentAmount;
    }
    public BigDecimal add(BigDecimal amount){
            this.currentAmount = this.currentAmount.add(amount);
            return this.currentAmount;
    }
    public BigDecimal alter(BigDecimal previousAmount,BigDecimal newAmount){
            BigDecimal net = newAmount.subtract(previousAmount);
            this.currentAmount = this.currentAmount.add(net);
            return this.currentAmount;
    }

    public BigDecimal getCurrentAmount(){
            return this.currentAmount;
    }

    @Scheduled(cron = "0 11 17 * * *", zone = "Asia/Kolkata")
    public void sendDailySummary() {
        String emoji = currentAmount.compareTo(BigDecimal.ZERO) >= 0 ? "🟢" : "🔴";
        String sign = currentAmount.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";

        String message = String.format(
                "📊 *Daily Summary*\n\n%s Net: *%s₹%s*\n\nGood night!",
                emoji, sign, currentAmount
        );

        telegramService.notifyTally(message);
        this.currentAmount = BigDecimal.ZERO;
    }

}
