package com.upi.transaction.dto;

import java.math.BigDecimal;

public record ParsedTransaction(
        Direction direction,
        PaymentMethod paymentMethod,
        BigDecimal amount,
        String counterparty,
        String date,
        String referenceId,
        BigDecimal balanceAfter
) {
    public enum Direction {
        DEBIT, CREDIT
    }

    public enum PaymentMethod {
        UPI, CARD, NET_BANKING, WALLET, NEFT, IMPS, NACH,
        CHARGE, REFUND, INTEREST, CASH, UPI_MANDATE,
        OTHER
    }
}