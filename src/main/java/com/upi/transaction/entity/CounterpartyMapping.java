package com.upi.transaction.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "counterparty_mappings")
public class CounterpartyMapping {

    @Id
    private String rawCounterparty;   // natural key — the exact raw identifier seen

    private String displayName;       // clean human-readable name
    private String category;
    private String source;            // USER | LLM | PENDING

    public CounterpartyMapping() {}

    public CounterpartyMapping(String rawCounterparty, String displayName,
                               String category, String source) {
        this.rawCounterparty = rawCounterparty;
        this.displayName = displayName;
        this.category = category;
        this.source = source;
    }

    public String getRawCounterparty() { return rawCounterparty; }
    public void setRawCounterparty(String rawCounterparty) { this.rawCounterparty = rawCounterparty; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}