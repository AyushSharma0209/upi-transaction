// src/main/java/com/upi/transaction/service/BalanceService.java

package com.upi.transaction.service;

import com.upi.transaction.config.AppConfig;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class BalanceService {

    private BigDecimal currentBalance;

    public BalanceService(AppConfig appConfig) {
        this.currentBalance = appConfig.getBalance().getInitial();
        System.out.println("Balance initialized: ₹" + currentBalance);
    }

    public BigDecimal deduct(BigDecimal amount) {
        this.currentBalance = this.currentBalance.subtract(amount);
        return this.currentBalance;
    }

    public BigDecimal add(BigDecimal amount) {
        this.currentBalance = this.currentBalance.add(amount);
        return this.currentBalance;
    }

    public BigDecimal syncBalance(BigDecimal actualBalance) {
        this.currentBalance = actualBalance;
        return this.currentBalance;
    }

    public BigDecimal getCurrentBalance() {
        return this.currentBalance;
    }
}