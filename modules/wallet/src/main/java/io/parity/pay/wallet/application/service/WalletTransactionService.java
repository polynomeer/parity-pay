package io.parity.pay.wallet.application.service;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.wallet.application.port.in.WalletTransactionQuery;
import io.parity.pay.wallet.application.port.out.WalletRepository;
import io.parity.pay.wallet.application.port.out.WalletTransactionRepository;
import io.parity.pay.wallet.domain.Wallet;
import io.parity.pay.wallet.domain.WalletTransactionEntry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거래내역 조회. 근거: FR-008
 *
 * <p>커서는 불투명 문자열로 인코딩하고 서버가 검증합니다. 클라이언트가 커서를 해석하거나 조작하는
 * 것을 전제로 하지 않습니다. 근거: docs/08-db-api-event-spec.md §9
 */
@Service
public class WalletTransactionService implements WalletTransactionQuery {

    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LIMIT = 20;

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;

    public WalletTransactionService(
            WalletRepository walletRepository, WalletTransactionRepository transactionRepository) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionPage list(MemberId memberId, WalletId walletId, String cursor, int limit) {
        Wallet wallet = walletRepository
                .findById(walletId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "wallet not found"));
        wallet.requireOwnedBy(memberId);

        int pageSize = normalizeLimit(limit);
        Cursor decoded = decodeCursor(cursor);

        List<WalletTransactionEntry> entries = transactionRepository.findPage(
                walletId, decoded.occurredAt(), decoded.transactionId(), pageSize);

        String nextCursor = entries.size() < pageSize ? null : encodeCursor(entries.getLast());
        return new TransactionPage(entries, nextCursor);
    }

    private static int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String encodeCursor(WalletTransactionEntry last) {
        String raw = last.occurredAt().toEpochMilli() + ":" + last.transactionId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new Cursor(null, null);
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf(':');
            Instant occurredAt = Instant.ofEpochMilli(Long.parseLong(raw.substring(0, separator)));
            UUID transactionId = UUID.fromString(raw.substring(separator + 1));
            return new Cursor(occurredAt, transactionId);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor is not valid");
        }
    }

    private record Cursor(Instant occurredAt, UUID transactionId) {}
}
