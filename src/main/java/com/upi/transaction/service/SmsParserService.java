package com.upi.transaction.service;

import com.upi.transaction.config.AppConfig;
import com.upi.transaction.dto.ParsedTransaction;
import com.upi.transaction.dto.ParsedTransaction.TransactionType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SmsParserService {

    private final Pattern upiSentPattern;
    private final Pattern upiReceivedPattern;
    private final Pattern cashDepositPattern;

    public SmsParserService(AppConfig appConfig) {
        this.upiSentPattern = Pattern.compile(appConfig.getSms().getSentPattern());
        this.upiReceivedPattern = Pattern.compile(appConfig.getSms().getReceivedPattern());
        this.cashDepositPattern = Pattern.compile(appConfig.getSms().getDepositPattern());
    }

    public ParsedTransaction parse(String smsBody) {
        Matcher sentMatcher = upiSentPattern.matcher(smsBody);
        if (sentMatcher.find()) {
            return new ParsedTransaction(
                    TransactionType.UPI_SENT,
                    new BigDecimal(sentMatcher.group(1)),
                    sentMatcher.group(2),
                    sentMatcher.group(3),
                    sentMatcher.group(4),
                    null
            );
        }

        Matcher receivedMatcher = upiReceivedPattern.matcher(smsBody);
        if (receivedMatcher.find()) {
            return new ParsedTransaction(
                    TransactionType.UPI_RECEIVED,
                    new BigDecimal(receivedMatcher.group(1)),
                    receivedMatcher.group(2),
                    receivedMatcher.group(3),
                    receivedMatcher.group(4),
                    null
            );
        }

        Matcher depositMatcher = cashDepositPattern.matcher(smsBody);
        if (depositMatcher.find()) {
            return new ParsedTransaction(
                    TransactionType.CASH_DEPOSIT,
                    new BigDecimal(depositMatcher.group(1)),
                    null,
                    depositMatcher.group(2),
                    null,
                    new BigDecimal(depositMatcher.group(3))
            );
        }

        return null;
    }
}