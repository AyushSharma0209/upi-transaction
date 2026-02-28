
package com.upi.transaction.dto;

import java.math.BigDecimal;

public record ParsedTransaction(
        TransactionType type,
        BigDecimal amount,
        String upiId,
        String date,
        String upiRef,
        BigDecimal bankBalance
) {
    public enum TransactionType {
        UPI_SENT,
        UPI_RECEIVED,
        CASH_DEPOSIT
    }
}