/**
 * customer API 타입입니다. **손으로 고치지 않습니다.**
 *
 * 생성: pnpm --filter @paritypay/api-client generate
 * 출처: docs/api/openapi.json (customer)
 *
 * 백엔드가 응답 필드를 바꾸면 이 파일이 바뀌고, 그것을 쓰는 화면의 빌드가 깨집니다.
 * 그것이 이 파일을 커밋하는 이유입니다.
 */
export interface paths {
    "/api/v1/auth/logout": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["logout"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/password": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["changePassword"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/password-reset": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["requestPasswordReset"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/password-reset/confirm": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["confirmPasswordReset"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/tokens": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["issue"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/tokens/refresh": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["refresh"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/bank-accounts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["linkBankAccount"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/members": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["registerMember"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/merchant/me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["me"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/merchant/settlements": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listSettlements"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/merchant/settlements/{settlementId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getSettlement"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/payments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["approve"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/payments/{paymentId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getPayment"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/payments/{paymentId}/cancellations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["cancel"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/payments/{paymentId}/confirmation": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["confirm"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/top-ups": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["requestTopUp"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/top-ups/{topUpId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getTopUp"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/wallets/{walletId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getBalance"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/wallets/{walletId}/ledger-verification": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["verify"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/wallets/{walletId}/transactions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getTransactions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/wallets/me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getMyBalance"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/webhooks/mock-pg": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["receive"];
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
        ApprovePaymentRequest: {
            /** Format: int64 */
            amount?: number;
            currency: string;
            /** Format: uuid */
            merchantId: string;
            method: string;
            orderId: string;
            /** Format: uuid */
            walletId: string;
        };
        BalanceVerificationResponse: {
            /** Format: int64 */
            ledgerBalance?: number;
            matches?: boolean;
            /** Format: int64 */
            snapshotBalance?: number;
            /** Format: uuid */
            walletId?: string;
        };
        CancellationResponse: {
            /** Format: uuid */
            cancellationId?: string;
            /** Format: int64 */
            completedAmount?: number;
            /** Format: date-time */
            completedAt?: string;
            currency?: string;
            /** Format: int64 */
            paymentCanceledAmount?: number;
            /** Format: uuid */
            paymentId?: string;
            /** Format: int64 */
            requestedAmount?: number;
            /** @enum {string} */
            status?: "REQUESTED" | "PROCESSING" | "COMPLETED" | "FAILED" | "UNKNOWN";
        };
        CancelPaymentRequest: {
            /** Format: int64 */
            amount?: number;
            currency: string;
            reason?: string;
        };
        ChangePasswordRequest: {
            currentPassword: string;
            newPassword: string;
        };
        LinkBankAccountRequest: {
            accountNumber: string;
            bankCode: string;
            /** Format: int64 */
            initialBalance?: number;
        };
        LinkBankAccountResponse: {
            /** Format: uuid */
            bankAccountId?: string;
        };
        LoginRequest: {
            email: string;
            password: string;
        };
        MerchantResponse: {
            /** Format: date-time */
            createdAt?: string;
            /** Format: uuid */
            merchantId?: string;
            name?: string;
            status?: string;
        };
        OrderConfirmationResponse: {
            /** Format: date-time */
            confirmedAt?: string;
            newlyConfirmed?: boolean;
            orderId?: string;
            /** Format: uuid */
            paymentId?: string;
        };
        PasswordResetConfirmRequest: {
            newPassword: string;
            token: string;
        };
        PasswordResetRequest: {
            /** Format: email */
            email: string;
        };
        PaymentResponse: {
            /** Format: int64 */
            approvedAmount?: number;
            /** Format: date-time */
            approvedAt?: string;
            /** Format: int64 */
            canceledAmount?: number;
            /** Format: int64 */
            cancellableAmount?: number;
            currency?: string;
            orderId?: string;
            /** Format: uuid */
            paymentId?: string;
            /** Format: int64 */
            requestedAmount?: number;
            /** @enum {string} */
            status?: "READY" | "PROCESSING" | "APPROVED" | "PARTIALLY_CANCELED" | "CANCELED" | "FAILED" | "UNKNOWN";
        };
        RefreshRequest: {
            refreshToken: string;
        };
        RegisterMemberRequest: {
            /** Format: email */
            email: string;
            password: string;
        };
        RegisterMemberResponse: {
            /** Format: uuid */
            memberId?: string;
            /** Format: uuid */
            walletId?: string;
        };
        SettlementResponse: {
            /** Format: int64 */
            adjustmentAmount?: number;
            /** Format: int64 */
            cancellationAmount?: number;
            currency?: string;
            /** Format: int64 */
            feeAmount?: number;
            /** Format: int64 */
            grossAmount?: number;
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
        TokenResponse: {
            accessToken?: string;
            /** Format: int64 */
            expiresIn?: number;
            /** Format: uuid */
            memberId?: string;
            refreshToken?: string;
            roles?: string[];
            tokenType?: string;
        };
        TopUpRequest: {
            /** Format: int64 */
            amount?: number;
            /** Format: uuid */
            bankAccountId: string;
            currency: string;
            /** Format: uuid */
            walletId: string;
        };
        TopUpResponse: {
            /** Format: int64 */
            completedAmount?: number;
            /** Format: date-time */
            completedAt?: string;
            currency?: string;
            /** Format: int64 */
            requestedAmount?: number;
            /** Format: date-time */
            requestedAt?: string;
            /** @enum {string} */
            status?: "REQUESTED" | "PROCESSING" | "SUCCEEDED" | "FAILED" | "UNKNOWN";
            /** Format: uuid */
            topUpId?: string;
        };
        TransactionPageResponse: {
            nextCursor?: string;
            transactions?: components["schemas"]["TransactionResponse"][];
        };
        TransactionResponse: {
            /** Format: int64 */
            amount?: number;
            currency?: string;
            direction?: string;
            /** Format: date-time */
            occurredAt?: string;
            referenceId?: string;
            referenceType?: string;
            /** Format: uuid */
            transactionId?: string;
            type?: string;
        };
        WalletBalanceResponse: {
            /** Format: date-time */
            asOf?: string;
            /** Format: int64 */
            available?: number;
            currency?: string;
            /** Format: int64 */
            pending?: number;
            /** Format: uuid */
            walletId?: string;
        };
        WebhookAck: {
            result?: string;
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
    logout: {
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
                content?: never;
            };
        };
    };
    changePassword: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ChangePasswordRequest"];
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
    requestPasswordReset: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PasswordResetRequest"];
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
    confirmPasswordReset: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PasswordResetConfirmRequest"];
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
    issue: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LoginRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TokenResponse"];
                };
            };
        };
    };
    refresh: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RefreshRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TokenResponse"];
                };
            };
        };
    };
    linkBankAccount: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LinkBankAccountRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LinkBankAccountResponse"];
                };
            };
        };
    };
    registerMember: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RegisterMemberRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RegisterMemberResponse"];
                };
            };
        };
    };
    me: {
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
                    "*/*": components["schemas"]["MerchantResponse"];
                };
            };
        };
    };
    listSettlements: {
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
                    "*/*": components["schemas"]["SettlementResponse"][];
                };
            };
        };
    };
    getSettlement: {
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
    approve: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ApprovePaymentRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PaymentResponse"];
                };
            };
        };
    };
    getPayment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                paymentId: string;
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
                    "*/*": components["schemas"]["PaymentResponse"];
                };
            };
        };
    };
    cancel: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                paymentId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CancelPaymentRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CancellationResponse"];
                };
            };
        };
    };
    confirm: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                paymentId: string;
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
                    "*/*": components["schemas"]["OrderConfirmationResponse"];
                };
            };
        };
    };
    requestTopUp: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TopUpRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TopUpResponse"];
                };
            };
        };
    };
    getTopUp: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                topUpId: string;
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
                    "*/*": components["schemas"]["TopUpResponse"];
                };
            };
        };
    };
    getBalance: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                walletId: string;
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
                    "*/*": components["schemas"]["WalletBalanceResponse"];
                };
            };
        };
    };
    verify: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                walletId: string;
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
                    "*/*": components["schemas"]["BalanceVerificationResponse"];
                };
            };
        };
    };
    getTransactions: {
        parameters: {
            query?: {
                cursor?: string;
                limit?: number;
            };
            header?: never;
            path: {
                walletId: string;
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
                    "*/*": components["schemas"]["TransactionPageResponse"];
                };
            };
        };
    };
    getMyBalance: {
        parameters: {
            query?: {
                currency?: string;
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
                    "*/*": components["schemas"]["WalletBalanceResponse"];
                };
            };
        };
    };
    receive: {
        parameters: {
            query?: never;
            header?: {
                "X-Webhook-Signature"?: string;
                "X-Webhook-Timestamp"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": string;
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["WebhookAck"];
                };
            };
        };
    };
}
