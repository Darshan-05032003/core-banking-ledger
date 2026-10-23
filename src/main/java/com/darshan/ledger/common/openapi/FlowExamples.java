package com.darshan.ledger.common.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.MediaType;
import java.util.HashMap;
import java.util.Map;

// Worked examples of a customer's first day, in the order the calls happen: sign in, onboard,
// open an account, deposit, transfer, approve. They are keyed by operationId so a renamed or
// removed operation fails the document build instead of leaving a stale example behind.
final class FlowExamples {

    private static final String JSON = "application/json";

    private FlowExamples() {
    }

    static void attach(OpenAPI api) {
        Map<String, Operation> operations = new HashMap<>();
        api.getPaths().values().forEach(item -> item.readOperations()
                .forEach(operation -> operations.put(operation.getOperationId(), operation)));

        request(operations, "issueToken", "Teller login", """
                { "username": "teller.priya", "password": "correct-horse-battery-staple" }
                """);
        response(operations, "issueToken", "200", "Token valid for fifteen minutes", """
                {
                  "accessToken": "eyJraWQiOiJsZWRnZXItMjAyNi0xMCIsImFsZyI6IlJTMjU2In0.eyJzdWIiOiJ0ZWxsZXIucHJpeWEiLCJyb2xlcyI6WyJURUxMRVIiXX0.c2lnbmF0dXJl",
                  "tokenType": "Bearer",
                  "expiresIn": 900,
                  "expiresAt": "2026-10-23T10:15:00Z"
                }
                """);

        request(operations, "onboardCustomer", "New customer at the counter", """
                {
                  "fullName": "Asha Verma",
                  "email": "asha.verma@example.com",
                  "phone": "+919876543210",
                  "dateOfBirth": "1994-03-12"
                }
                """);
        response(operations, "onboardCustomer", "201", "KYC starts as PENDING", """
                {
                  "id": 42,
                  "fullName": "Asha Verma",
                  "maskedEmail": "a***@example.com",
                  "maskedPhone": "XXXXXXXXX3210",
                  "dateOfBirth": "1994-03-12",
                  "kycStatus": "PENDING",
                  "kycReason": null,
                  "kycDecidedAt": null,
                  "createdAt": "2026-10-23T10:00:12Z"
                }
                """);

        request(operations, "openAccount", "Savings account for a verified customer", """
                { "customerId": 42, "type": "SAVINGS", "currency": "INR" }
                """);
        response(operations, "openAccount", "201", "Account number masked to its last four digits", """
                {
                  "id": 7,
                  "maskedAccountNumber": "XXXXXXXX0421",
                  "customerId": 42,
                  "type": "SAVINGS",
                  "currency": "INR",
                  "status": "ACTIVE",
                  "openedOn": "2026-10-23",
                  "closedOn": null
                }
                """);

        request(operations, "deposit", "Cash deposit", """
                { "amount": 500000.00, "currency": "INR", "remarks": "Initial deposit" }
                """);
        response(operations, "deposit", "201", "Journal debiting the vault and crediting the account", """
                {
                  "journal": {
                    "id": 101,
                    "reference": "JRN-0000000101",
                    "type": "DEPOSIT",
                    "description": "Initial deposit",
                    "postedAt": "2026-10-23T10:05:40Z",
                    "createdBy": "teller.priya",
                    "reversalOfId": null,
                    "entries": [
                      { "accountId": null, "maskedAccountNumber": null, "glAccount": "CASH_VAULT",
                        "direction": "DEBIT", "amount": 500000.00, "currency": "INR" },
                      { "accountId": 7, "maskedAccountNumber": "XXXXXXXX0421", "glAccount": null,
                        "direction": "CREDIT", "amount": 500000.00, "currency": "INR" }
                    ]
                  },
                  "balance": {
                    "accountId": 7,
                    "maskedAccountNumber": "XXXXXXXX0421",
                    "ledgerBalance": 500000.00,
                    "availableBalance": 500000.00,
                    "onHold": 0.00,
                    "currency": "INR"
                  }
                }
                """);

        request(operations, "createTransfer", "Transfer between two accounts", """
                {
                  "fromAccount": "000100000421",
                  "toAccount": "000100000439",
                  "amount": 1000.00,
                  "currency": "INR",
                  "remarks": "Rent"
                }
                """);
        response(operations, "createTransfer", "201", "Posted immediately", """
                {
                  "id": 12,
                  "reference": "TRF-0000000012",
                  "status": "COMPLETED",
                  "fromAccountId": 7,
                  "maskedFromAccountNumber": "XXXXXXXX0421",
                  "toAccountId": 8,
                  "maskedToAccountNumber": "XXXXXXXX0439",
                  "amount": 1000.00,
                  "currency": "INR",
                  "remarks": "Rent",
                  "journalId": 102,
                  "journalReference": "JRN-0000000102",
                  "failureCode": null,
                  "failureReason": null,
                  "createdBy": "asha.verma",
                  "createdAt": "2026-10-23T10:20:05Z",
                  "completedAt": "2026-10-23T10:20:05Z",
                  "decidedBy": null,
                  "decisionReason": null
                }
                """);
        response(operations, "createTransfer", "202", "Above the approval threshold, parked with a hold", """
                {
                  "id": 13,
                  "reference": "TRF-0000000013",
                  "status": "PENDING_APPROVAL",
                  "fromAccountId": 7,
                  "maskedFromAccountNumber": "XXXXXXXX0421",
                  "toAccountId": 8,
                  "maskedToAccountNumber": "XXXXXXXX0439",
                  "amount": 250000.00,
                  "currency": "INR",
                  "remarks": "Car purchase",
                  "journalId": null,
                  "journalReference": null,
                  "failureCode": null,
                  "failureReason": null,
                  "createdBy": "asha.verma",
                  "createdAt": "2026-10-23T10:22:30Z",
                  "completedAt": null,
                  "decidedBy": null,
                  "decisionReason": null
                }
                """);

        response(operations, "approveTransfer", "200", "Posted by a checker other than the maker", """
                {
                  "id": 13,
                  "reference": "TRF-0000000013",
                  "status": "COMPLETED",
                  "fromAccountId": 7,
                  "maskedFromAccountNumber": "XXXXXXXX0421",
                  "toAccountId": 8,
                  "maskedToAccountNumber": "XXXXXXXX0439",
                  "amount": 250000.00,
                  "currency": "INR",
                  "remarks": "Car purchase",
                  "journalId": 103,
                  "journalReference": "JRN-0000000103",
                  "failureCode": null,
                  "failureReason": null,
                  "createdBy": "asha.verma",
                  "createdAt": "2026-10-23T10:22:30Z",
                  "completedAt": "2026-10-23T10:31:02Z",
                  "decidedBy": "checker.rahul",
                  "decisionReason": null
                }
                """);
    }

    private static void request(Map<String, Operation> operations, String operationId, String summary, String json) {
        MediaType body = required(operations, operationId).getRequestBody().getContent().get(JSON);
        body.addExamples(operationId, JsonExample.of(summary, json));
    }

    private static void response(Map<String, Operation> operations, String operationId, String status,
            String summary, String json) {
        MediaType body = required(operations, operationId).getResponses().get(status).getContent().get(JSON);
        body.addExamples(operationId + status, JsonExample.of(summary, json));
    }

    private static Operation required(Map<String, Operation> operations, String operationId) {
        Operation operation = operations.get(operationId);
        if (operation == null) {
            throw new IllegalStateException("No operation with id " + operationId + " to attach an example to");
        }
        return operation;
    }
}
