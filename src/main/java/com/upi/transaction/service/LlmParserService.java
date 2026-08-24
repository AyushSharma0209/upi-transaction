package com.upi.transaction.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upi.transaction.config.AppConfig;
import com.upi.transaction.dto.ParsedTransaction;
import com.upi.transaction.dto.ParsedTransaction.Direction;
import com.upi.transaction.dto.ParsedTransaction.PaymentMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.Map;

@Service
public class LlmParserService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            You are a financial SMS parser. Extract transaction details from Indian bank/payment SMS messages.

            Return ONLY valid JSON with this exact structure:
            {
              "is_transaction": true/false,
              "amount": number or null,
              "direction": "DEBIT" or "CREDIT" or null,
              "counterparty": "clean merchant/person name" or null,
              "payment_method": "UPI" or "CARD" or "NET_BANKING" or "WALLET" or "NEFT" or "IMPS" or "NACH" or "OTHER" or null,
              "balance_after": number or null,
              "date": "date found in SMS" or null,
              "reference_id": "transaction ref if present" or null
            }

            Rules:
            - Extract the clean merchant name, not raw UPI IDs or folio numbers
            - If multiple amounts appear, the transaction amount is NOT the balance
            - OTP messages, promotional SMS, balance inquiries without transactions → is_transaction: false
            - "debited"/"sent"/"paid"/"purchased"/"DR" → DEBIT
            - "credited"/"received"/"CR" → CREDIT
            - SIP/mandate purchases are DEBIT
            - Return ONLY the JSON object. No markdown, no backticks, no explanation.
            """;

    public LlmParserService(AppConfig appConfig) {
        this.objectMapper = new ObjectMapper();
        this.webClient = WebClient.builder()
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("x-api-key", appConfig.getClaude().getApiKey())
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public ParsedTransaction parse(String smsBody) {
        try {
            String response = webClient.post()
                    .uri("/v1/messages")
                    .bodyValue(buildRequestBody(smsBody))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return mapResponse(response);
        } catch (Exception e) {
            System.err.println("LLM parse failed: " + e.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildRequestBody(String smsBody) {
        return Map.of(
                "model", "claude-haiku-4-5-20251001",
                "max_tokens", 300,
                "system", SYSTEM_PROMPT,
                "messages", new Object[]{
                        Map.of("role", "user", "content", smsBody)
                }
        );
    }

    private ParsedTransaction mapResponse(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);

        // Claude Messages API: content[0].text
        String text = root.path("content").get(0).path("text").asText();

        // Strip markdown fences if the model ever wraps them
        String cleaned = text.replaceAll("```json|```", "").trim();
        JsonNode parsed = objectMapper.readTree(cleaned);

        if (!parsed.path("is_transaction").asBoolean(false)) {
            return null;
        }

        return new ParsedTransaction(
                mapDirection(parsed.path("direction").asText(null)),
                mapPaymentMethod(parsed.path("payment_method").asText(null)),
                parseBigDecimal(parsed.path("amount")),
                parsed.path("counterparty").asText(null),
                parsed.path("date").asText(null),
                parsed.path("reference_id").asText(null),
                parseBigDecimal(parsed.path("balance_after"))
        );
    }

    private Direction mapDirection(String dir) {
        if (dir == null) return Direction.DEBIT;
        return switch (dir.toUpperCase()) {
            case "CREDIT" -> Direction.CREDIT;
            default -> Direction.DEBIT;
        };
    }

    private PaymentMethod mapPaymentMethod(String method) {
        if (method == null) return PaymentMethod.OTHER;
        return switch (method.toUpperCase()) {
            case "UPI" -> PaymentMethod.UPI;
            case "CARD" -> PaymentMethod.CARD;
            case "NET_BANKING" -> PaymentMethod.NET_BANKING;
            case "WALLET" -> PaymentMethod.WALLET;
            case "NEFT" -> PaymentMethod.NEFT;
            case "IMPS" -> PaymentMethod.IMPS;
            case "NACH" -> PaymentMethod.NACH;
            default -> PaymentMethod.OTHER;
        };
    }

    private BigDecimal parseBigDecimal(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}