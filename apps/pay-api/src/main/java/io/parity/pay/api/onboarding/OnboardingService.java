package io.parity.pay.api.onboarding;

import io.parity.pay.api.mockbank.MockBankLedger;
import io.parity.pay.ledger.application.port.in.ResolveLedgerAccountUseCase;
import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.event.OutboxAppender;
import io.parity.pay.shared.id.BankAccountId;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import io.parity.pay.wallet.application.event.WalletEvents;
import io.parity.pay.wallet.application.port.out.WalletBalanceRepository;
import io.parity.pay.wallet.application.port.out.WalletRepository;
import io.parity.pay.wallet.domain.Wallet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입과 계좌 연결.
 *
 * <p>가입 시 KRW 지갑과 사용자 원장 계정을 함께 만듭니다. 근거: FR-001, FR-002
 *
 * <p>인증·인가는 아직 구현되지 않았습니다(Phase 2). 현재 API는 로컬 개발용이며 호출자가 보낸
 * {@code X-Member-Id}를 그대로 신뢰합니다.
 */
@Service
public class OnboardingService {

    /** 마스킹된 계좌번호에서 가릴 앞자리의 최대 길이입니다. 컬럼은 VARCHAR(30)입니다. */
    private static final int MAX_MASKED_PREFIX = 20;

    private final JdbcTemplate jdbcTemplate;
    private final WalletRepository walletRepository;
    private final WalletBalanceRepository walletBalanceRepository;
    private final ResolveLedgerAccountUseCase resolveLedgerAccount;
    private final MockBankLedger mockBankLedger;
    private final OutboxAppender outboxAppender;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public OnboardingService(
            JdbcTemplate jdbcTemplate,
            WalletRepository walletRepository,
            WalletBalanceRepository walletBalanceRepository,
            ResolveLedgerAccountUseCase resolveLedgerAccount,
            MockBankLedger mockBankLedger,
            OutboxAppender outboxAppender,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.walletRepository = walletRepository;
        this.walletBalanceRepository = walletBalanceRepository;
        this.resolveLedgerAccount = resolveLedgerAccount;
        this.mockBankLedger = mockBankLedger;
        this.outboxAppender = outboxAppender;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public RegisteredMember registerMember(String email, String rawPassword) {
        MemberId memberId = MemberId.generate();
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO member (member_id, email, password_hash, status, created_at)
                    VALUES (?, ?, ?, 'ACTIVE', ?)
                    """,
                    memberId.value(),
                    email,
                    // 비밀번호 원문을 저장하지 않습니다. 근거: docs/05-technical-design.md §11
                    passwordEncoder.encode(rawPassword),
                    java.sql.Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "email is already registered");
        }

        Wallet wallet = walletRepository.save(Wallet.open(memberId, CurrencyCode.KRW, clock.instant()));
        walletBalanceRepository.create(wallet.id(), Money.zero(CurrencyCode.KRW));
        // 사용자 페이머니 계정을 가입 시점에 함께 만듭니다. 근거: FR-001
        resolveLedgerAccount.resolve(AccountCode.USER_PAY_MONEY, wallet.id().value(), CurrencyCode.KRW);

        outboxAppender.append(WalletEvents.walletCreated(
                wallet.id(), memberId, CurrencyCode.KRW, wallet.createdAt()));

        return new RegisteredMember(memberId, wallet.id().value());
    }

    /**
     * Mock Bank 계좌를 연결합니다.
     *
     * <p>계좌번호 원문은 저장하지 않고 소유 확인 토큰과 마스킹된 번호만 남깁니다. 근거: FR-002
     */
    @Transactional
    public BankAccountId linkBankAccount(
            MemberId memberId, String bankCode, String accountNumber, Money initialBalance) {
        String token = tokenize(bankCode, accountNumber);
        BankAccountId bankAccountId = BankAccountId.generate();

        jdbcTemplate.update(
                """
                INSERT INTO bank_account
                    (bank_account_id, member_id, bank_code, account_number_token,
                     account_number_masked, status, created_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)
                """,
                bankAccountId.value(),
                memberId.value(),
                bankCode,
                token,
                mask(accountNumber),
                java.sql.Timestamp.from(clock.instant()));

        mockBankLedger.openAccount(bankAccountId, token, initialBalance);
        return bankAccountId;
    }

    private static String tokenize(String bankCode, String accountNumber) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((bankCode + ":" + accountNumber).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available", e);
        }
    }

    /**
     * 마스킹된 계좌번호.
     *
     * <p>길이를 입력에 비례시키지 않습니다. 원문 길이를 그대로 드러내면 마스킹의 의미가 줄어들고,
     * 저장 컬럼 길이를 넘겨 요청이 500으로 실패합니다. 뒤 4자리만 남기고 앞은 고정 길이로 가립니다.
     */
    private static String mask(String accountNumber) {
        int visible = Math.min(4, accountNumber.length());
        String tail = accountNumber.substring(accountNumber.length() - visible);
        int hidden = Math.min(accountNumber.length() - visible, MAX_MASKED_PREFIX);
        return "*".repeat(hidden) + tail;
    }

    public record RegisteredMember(MemberId memberId, java.util.UUID walletId) {}
}
