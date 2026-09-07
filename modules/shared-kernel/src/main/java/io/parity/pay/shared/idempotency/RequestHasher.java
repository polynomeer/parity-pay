package io.parity.pay.shared.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 요청 본문의 정규화 해시를 계산합니다.
 *
 * <p>같은 키로 다른 요청이 왔는지 판단하는 기준입니다. 원문을 저장하지 않고 해시만 남깁니다.
 * 근거: docs/04-payment-policy.md §5
 */
public final class RequestHasher {

    private RequestHasher() {}

    public static String sha256(String canonicalRequest) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available", e);
        }
    }
}
