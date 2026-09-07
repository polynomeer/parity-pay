package io.parity.pay.shared.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7 생성기 (RFC 9562).
 *
 * <p>외부에 노출되는 식별자는 추측하기 어려우면서 시간순으로 정렬 가능해야 합니다.
 * 근거: docs/04-payment-policy.md BR-002
 *
 * <p>레이아웃: 48비트 밀리초 타임스탬프 + 4비트 버전 + 12비트 시퀀스 + 2비트 variant + 62비트 랜덤.
 * 같은 밀리초 안에서는 시퀀스를 증가시켜 단조 증가를 보장합니다.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object LOCK = new Object();

    private static long lastTimestamp = -1L;
    private static int sequence = 0;

    private UuidV7() {}

    public static UUID generate() {
        long timestamp = System.currentTimeMillis();
        int seq;
        synchronized (LOCK) {
            if (timestamp == lastTimestamp) {
                sequence++;
                if (sequence > 0x0FFF) {
                    // 같은 밀리초에서 시퀀스가 소진되면 다음 밀리초로 넘깁니다.
                    timestamp = waitForNextMillis(lastTimestamp);
                    lastTimestamp = timestamp;
                    sequence = 0;
                }
            } else if (timestamp > lastTimestamp) {
                lastTimestamp = timestamp;
                sequence = 0;
            } else {
                // 시계가 뒤로 갔을 때는 마지막 시각을 유지해 단조 증가를 깨지 않습니다.
                timestamp = lastTimestamp;
                sequence++;
            }
            seq = sequence & 0x0FFF;
        }

        long mostSignificantBits = (timestamp & 0xFFFF_FFFF_FFFFL) << 16;
        mostSignificantBits |= 0x7000L; // version 7
        mostSignificantBits |= seq;

        long leastSignificantBits = RANDOM.nextLong();
        leastSignificantBits &= 0x3FFF_FFFF_FFFF_FFFFL;
        leastSignificantBits |= 0x8000_0000_0000_0000L; // variant 10

        return new UUID(mostSignificantBits, leastSignificantBits);
    }

    private static long waitForNextMillis(long previous) {
        long now = System.currentTimeMillis();
        while (now <= previous) {
            Thread.onSpinWait();
            now = System.currentTimeMillis();
        }
        return now;
    }
}
