package io.parity.pay.shared.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    @DisplayName("BR-002: 버전 7, variant 10 형식을 만족한다")
    void hasVersion7AndCorrectVariant() {
        UUID id = UuidV7.generate();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 밀리초 안에서도 단조 증가한다")
    void isMonotonic() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            ids.add(UuidV7.generate());
        }

        for (int i = 1; i < ids.size(); i++) {
            assertThat(compareUnsigned(ids.get(i - 1), ids.get(i)))
                    .as("id[%d] must be smaller than id[%d]", i - 1, i)
                    .isNegative();
        }
    }

    @Test
    @DisplayName("동시 생성해도 중복되지 않는다")
    void isUniqueUnderConcurrency() throws Exception {
        int threads = 8;
        int perThread = 2_000;
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Callable<List<UUID>>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                tasks.add(() -> {
                    List<UUID> generated = new ArrayList<>(perThread);
                    for (int i = 0; i < perThread; i++) {
                        generated.add(UuidV7.generate());
                    }
                    return generated;
                });
            }

            Set<UUID> all = new HashSet<>();
            for (Future<List<UUID>> future : executor.invokeAll(tasks)) {
                all.addAll(future.get());
            }
            assertThat(all).hasSize(threads * perThread);
        }
    }

    private static int compareUnsigned(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0
                ? high
                : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
}
