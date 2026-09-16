package io.parity.pay.api.mockpg.guard;

/** 초당 상한을 지키는 방법. 셋을 같은 부하로 비교한 결과는 reports/11 M-022에 있습니다. */
interface RateLimitStrategy {

    /** 허가되면 true. 기다리지 않습니다 — 결제 요청을 붙잡아 두는 것은 사용자 입장에서 타임아웃과 같습니다. */
    boolean tryAcquire();

    String name();
}
