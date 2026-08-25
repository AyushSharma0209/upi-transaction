package com.upi.transaction.entity;

import com.upi.transaction.dto.ParsedTransaction;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "pending_categorizations")
public class PendingCategorization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String rawCounterparty;   // clean name from bank SMS (e.g. "RAJESH KUMAR")

    @Enumerated(EnumType.STRING)
    private ParsedTransaction.Direction direction;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private ParsedTransaction.PaymentMethod paymentMethod;

    private String reference;         // UPI Ref number
    private LocalDate date;           // txn date
    private BigDecimal balanceAfter;  // snapshot of balance after this txn

    private Long telegramMessageId;   // set after Telegram inline-keyboard message sent

    private Instant createdAt = Instant.now();

    public PendingCategorization() {}

    // getters + setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRawCounterparty() { return rawCounterparty; }
    public void setRawCounterparty(String s) { this.rawCounterparty = s; }

    public ParsedTransaction.Direction getDirection() { return direction; }
    public void setDirection(ParsedTransaction.Direction d) { this.direction = d; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal a) { this.amount = a; }

    public ParsedTransaction.PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(ParsedTransaction.PaymentMethod p) { this.paymentMethod = p; }

    public String getReference() { return reference; }
    public void setReference(String r) { this.reference = r; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate d) { this.date = d; }

    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal b) { this.balanceAfter = b; }

    public Long getTelegramMessageId() { return telegramMessageId; }
    public void setTelegramMessageId(Long m) { this.telegramMessageId = m; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant c) { this.createdAt = c; }
}