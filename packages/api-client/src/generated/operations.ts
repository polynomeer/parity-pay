/**
 * operations API 타입입니다. **손으로 고치지 않습니다.**
 *
 * 생성: pnpm --filter @paritypay/api-client generate
 * 출처: docs/api/openapi.json (operations)
 *
 * 백엔드가 응답 필드를 바꾸면 이 파일이 바뀌고, 그것을 쓰는 화면의 빌드가 깨집니다.
 * 그것이 이 파일을 커밋하는 이유입니다.
 */
export interface paths {
    "/api/v1/admin/invariants": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["invariants"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/ledger/transactions/{transactionId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getTransaction"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/merchants": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["registerMerchant"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/mock-bank/mode": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["setMockBankMode"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/mock-pg/mode": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["setMockPgMode"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/outbox-events": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listOutboxEvents"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/outbox-events/{eventId}/retry": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["retryOutboxEvent"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/outbox-events/backlog": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["outboxBacklog"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/payments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listUnresolvedPayments"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/payments/{paymentId}/resolve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["resolvePayment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/reconciliation/mismatches": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listOpen"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/reconciliation/mismatches/{mismatchId}/adjustments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["adjust"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/reconciliation/mismatches/{mismatchId}/resolve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["resolve_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/reconciliation/runs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["run"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/recovery/manual-review": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listManualReview"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/settlements": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listByMerchant"];
        put?: never;
        post: operations["calculate"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/settlements/{settlementId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/settlements/{settlementId}/hold": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["hold"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/settlements/{settlementId}/payouts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["pay"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/settlements/{settlementId}/release": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["release"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/top-ups": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listUnresolved"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/top-ups/{topUpId}/resolve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["resolve"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/transactions/{referenceId}/timeline": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["timeline"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/transactions/resolve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["resolve_2"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/wallets/{walletId}/balance-rebuild": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["rebuildBalance"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/wallets/balance-drift": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listBalanceDrift"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        AdjustmentRequest: {
            /** Format: int64 */
            amount?: number;
            /** @enum {string} */
            creditAccount: "BANK_DEPOSIT" | "PG_RECEIVABLE" | "MERCHANT_RECEIVABLE" | "USER_PAY_MONEY" | "PAYMENT_HOLDING" | "MERCHANT_PAYABLE" | "POINT_LIABILITY" | "UNIDENTIFIED_DEPOSIT" | "EQUITY_ADJUSTMENT" | "PLATFORM_FEE_REVENUE" | "PROVIDER_FEE_EXPENSE" | "SETTLEMENT_CLEARING";
            /** Format: uuid */
            creditOwnerId?: string;
            /** @enum {string} */
            debitAccount: "BANK_DEPOSIT" | "PG_RECEIVABLE" | "MERCHANT_RECEIVABLE" | "USER_PAY_MONEY" | "PAYMENT_HOLDING" | "MERCHANT_PAYABLE" | "POINT_LIABILITY" | "UNIDENTIFIED_DEPOSIT" | "EQUITY_ADJUSTMENT" | "PLATFORM_FEE_REVENUE" | "PROVIDER_FEE_EXPENSE" | "SETTLEMENT_CLEARING";
            /** Format: uuid */
            debitOwnerId?: string;
            reason: string;
        };
        BalanceDriftResponse: {
            /** Format: int64 */
            difference?: number;
            /** Format: int64 */
            ledgerBalance?: number;
            /** Format: int64 */
            snapshotTotal?: number;
            /** Format: uuid */
            walletId?: string;
        };
        BalanceRebuildResponse: {
            detail?: string;
            /** Format: int64 */
            ledgerBalance?: number;
            /** Format: int64 */
            snapshotAfter?: number;
            /** Format: int64 */
            snapshotBefore?: number;
            status?: string;
            /** Format: uuid */
            walletId?: string;
        };
        CalculateRequest: {
            /** Format: uuid */
            merchantId: string;
            /** Format: date */
            periodEnd: string;
            /** Format: date */
            periodStart: string;
        };
        EntryResponse: {
            accountCode?: string;
            /** Format: int64 */
            amount?: number;
            direction?: string;
            /** Format: uuid */
            entryId?: string;
            /** Format: uuid */
            ownerId?: string;
        };
        HoldRequest: {
            reason: string;
        };
        InvariantSnapshot: {
            /** Format: int64 */
            ageSeconds?: number;
            /** Format: int64 */
            refreshDurationMillis?: number;
            /** Format: date-time */
            refreshedAt?: string;
            values?: components["schemas"]["InvariantValue"][];
        };
        InvariantValue: {
            description?: string;
            name?: string;
            /** Format: int64 */
            value?: number;
        };
        LedgerTransactionResponse: {
            balanced?: boolean;
            /** Format: int64 */
            creditTotal?: number;
            currency?: string;
            /** Format: int64 */
            debitTotal?: number;
            /** Format: date-time */
            effectiveAt?: string;
            entries?: components["schemas"]["EntryResponse"][];
            /** Format: uuid */
            referenceId?: string;
            referenceType?: string;
            /** Format: uuid */
            reversalOfTransactionId?: string;
            status?: string;
            /** Format: uuid */
            transactionId?: string;
            transactionType?: string;
        };
        MerchantRegistrationResponse: {
            /** Format: uuid */
            merchantId?: string;
            name?: string;
            ownerEmail?: string;
            status?: string;
        };
        MismatchResponse: {
            /** Format: uuid */
            adjustmentLedgerTransactionId?: string;
            /** Format: int64 */
            amountDifference?: number;
            detail?: string;
            /** Format: date-time */
            detectedAt?: string;
            /** Format: int64 */
            externalAmount?: number;
            /** Format: int64 */
            internalAmount?: number;
            /** Format: uuid */
            mismatchId?: string;
            referenceId?: string;
            referenceType?: string;
            resolutionReason?: string;
            resolutionStatus?: string;
            /** Format: date-time */
            resolvedAt?: string;
            resolvedBy?: string;
            type?: string;
        };
        MockBankModeRequest: {
            /** @enum {string} */
            mode?: "NORMAL" | "EXPLICIT_FAILURE" | "TIMEOUT_BEFORE_WITHDRAWAL" | "TIMEOUT_AFTER_WITHDRAWAL";
        };
        MockPgModeRequest: {
            /** @enum {string} */
            mode?: "NORMAL" | "EXPLICIT_DECLINE" | "TIMEOUT_BEFORE_APPROVAL" | "TIMEOUT_AFTER_APPROVAL";
            statusQueryAvailable?: boolean;
            webhookMode?: string;
        };
        OutboxEventSummary: {
            aggregateId?: string;
            aggregateType?: string;
            /** Format: int32 */
            attemptCount?: number;
            /** Format: uuid */
            eventId?: string;
            eventType?: string;
            lastError?: string;
            /** Format: date-time */
            nextAttemptAt?: string;
            /** Format: date-time */
            occurredAt?: string;
            partitionKey?: string;
            /** Format: date-time */
            publishedAt?: string;
            status?: string;
        };
        PartitionBacklog: {
            /** Format: int32 */
            maxAttemptCount?: number;
            /** Format: int64 */
            oldestAgeSeconds?: number;
            partitionKey?: string;
            /** Format: int64 */
            pending?: number;
        };
        PaymentRecoveryOutcomeResponse: {
            changed?: boolean;
            detail?: string;
            /** Format: uuid */
            paymentId?: string;
            status?: string;
        };
        RebuildRequest: {
            reason: string;
        };
        RecoveryOutcomeResponse: {
            changed?: boolean;
            detail?: string;
            status?: string;
            /** Format: uuid */
            topUpId?: string;
        };
        Reference: {
            kind?: string;
            referenceId?: string;
            summary?: string;
        };
        RegisterMerchantRequest: {
            name: string;
            ownerEmail: string;
            reason: string;
        };
        RequeueOutcome: {
            changed?: boolean;
            detail?: string;
            /** Format: uuid */
            eventId?: string;
            laterSiblingPublished?: boolean;
            /** Format: int32 */
            previousAttemptCount?: number;
            status?: string;
        };
        ResolveRequest: {
            reason: string;
        };
        RunResponse: {
            /** Format: int32 */
            externalCount?: number;
            /** Format: int32 */
            internalCount?: number;
            /** Format: int32 */
            mismatchCount?: number;
            /** Format: uuid */
            runId?: string;
        };
        SearchResult: {
            kind?: string;
            query?: string;
            references?: components["schemas"]["Reference"][];
        };
        SettlementResponse: {
            /** Format: int64 */
            adjustmentAmount?: number;
            /** Format: int64 */
            cancellationAmount?: number;
            currency?: string;
            externalReferenceId?: string;
            /** Format: int64 */
            feeAmount?: number;
            /** Format: int64 */
            grossAmount?: number;
            /** Format: uuid */
            merchantId?: string;
            /** Format: int64 */
            netAmount?: number;
            /** Format: date-time */
            paidAt?: string;
            /** Format: date */
            periodEnd?: string;
            /** Format: date */
            periodStart?: string;
            /** Format: uuid */
            settlementId?: string;
            status?: string;
        };
        Timeline: {
            entries?: components["schemas"]["TimelineEntry"][];
            referenceId?: string;
        };
        TimelineEntry: {
            /** Format: int64 */
            amount?: number;
            currency?: string;
            detail?: string;
            id?: string;
            kind?: string;
            /** Format: date-time */
            occurredAt?: string;
            status?: string;
        };
        UnresolvedPaymentResponse: {
            /** Format: int32 */
            attemptCount?: number;
            /** Format: date-time */
            createdAt?: string;
            currency?: string;
            externalReferenceId?: string;
            lastError?: string;
            /** Format: uuid */
            merchantId?: string;
            orderId?: string;
            /** Format: uuid */
            paymentId?: string;
            /** Format: int64 */
            requestedAmount?: number;
            requiresManualReview?: boolean;
            status?: string;
        };
        UnresolvedTopUpResponse: {
            /** Format: int32 */
            attemptCount?: number;
            currency?: string;
            lastError?: string;
            /** Format: int64 */
            requestedAmount?: number;
            /** Format: date-time */
            requestedAt?: string;
            requiresManualReview?: boolean;
            status?: string;
            /** Format: uuid */
            topUpId?: string;
            /** Format: uuid */
            walletId?: string;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    invariants: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["InvariantSnapshot"];
                };
            };
        };
    };
    getTransaction: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                transactionId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LedgerTransactionResponse"];
                };
            };
        };
    };
    registerMerchant: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RegisterMerchantRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["MerchantRegistrationResponse"];
                };
            };
        };
    };
    setMockBankMode: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["MockBankModeRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    setMockPgMode: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["MockPgModeRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    listOutboxEvents: {
        parameters: {
            query?: {
                limit?: number;
                status?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["OutboxEventSummary"][];
                };
            };
        };
    };
    retryOutboxEvent: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                eventId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ResolveRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RequeueOutcome"];
                };
            };
        };
    };
    outboxBacklog: {
        parameters: {
            query?: {
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PartitionBacklog"][];
                };
            };
        };
    };
    listUnresolvedPayments: {
        parameters: {
            query?: {
                limit?: number;
                status?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UnresolvedPaymentResponse"][];
                };
            };
        };
    };
    resolvePayment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                paymentId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ResolveRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PaymentRecoveryOutcomeResponse"];
                };
            };
        };
    };
    listOpen: {
        parameters: {
            query?: {
                limit?: number;
                type?: "INTERNAL_ONLY" | "EXTERNAL_ONLY" | "STATUS_MISMATCH" | "AMOUNT_MISMATCH" | "DUPLICATE" | "LEDGER_MISSING";
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["MismatchResponse"][];
                };
            };
        };
    };
    adjust: {
        parameters: {
            query?: never;
            header: {
                "X-Approver-Id": string;
                "X-Reauth-Token": string;
            };
            path: {
                mismatchId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AdjustmentRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["MismatchResponse"];
                };
            };
        };
    };
    resolve_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                mismatchId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ResolveRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["MismatchResponse"];
                };
            };
        };
    };
    run: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RunResponse"];
                };
            };
        };
    };
    listManualReview: {
        parameters: {
            query?: {
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    }[];
                };
            };
        };
    };
    listByMerchant: {
        parameters: {
            query: {
                limit?: number;
                merchantId: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SettlementResponse"][];
                };
            };
        };
    };
    calculate: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CalculateRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SettlementResponse"];
                };
            };
        };
    };
    get: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                settlementId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SettlementResponse"];
                };
            };
        };
    };
    hold: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                settlementId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["HoldRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SettlementResponse"];
                };
            };
        };
    };
    pay: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                settlementId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SettlementResponse"];
                };
            };
        };
    };
    release: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                settlementId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SettlementResponse"];
                };
            };
        };
    };
    listUnresolved: {
        parameters: {
            query?: {
                limit?: number;
                status?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UnresolvedTopUpResponse"][];
                };
            };
        };
    };
    resolve: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                topUpId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ResolveRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RecoveryOutcomeResponse"];
                };
            };
        };
    };
    timeline: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                referenceId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Timeline"];
                };
            };
        };
    };
    resolve_2: {
        parameters: {
            query: {
                query: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SearchResult"];
                };
            };
        };
    };
    rebuildBalance: {
        parameters: {
            query?: never;
            header: {
                "X-Approver-Id": string;
            };
            path: {
                walletId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RebuildRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BalanceRebuildResponse"];
                };
            };
        };
    };
    listBalanceDrift: {
        parameters: {
            query?: {
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BalanceDriftResponse"][];
                };
            };
        };
    };
}
