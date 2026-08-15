package com.upi.transaction.entity;

import com.upi.transaction.dto.ParsedTransaction;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate date;

    @Enumerated(EnumType.STRING)
    private ParsedTransaction.Direction direction;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private ParsedTransaction.PaymentMethod paymentMethod;

    private String counterpartyRaw;   // exact extracted identifier — lookup key
    private String counterparty;      // clean resolved display name
    private String category;          // resolved via mapping table or LLM
    private String reference;         // UPI Ref / Chq-Ref number
    private BigDecimal balance;       // account balance after this transaction

    public Transaction() {}

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public ParsedTransaction.Direction getDirection() { return direction; }
    public void setDirection(ParsedTransaction.Direction direction) { this.direction = direction; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public ParsedTransaction.PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(ParsedTransaction.PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getCounterpartyRaw() { return counterpartyRaw; }
    public void setCounterpartyRaw(String counterpartyRaw) { this.counterpartyRaw = counterpartyRaw; }
    public String getCounterparty() { return counterparty; }
    public void setCounterparty(String counterparty) { this.counterparty = counterparty; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
}