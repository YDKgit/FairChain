package com.fairchain.server.queue;

import com.fairchain.server.queue.service.FC_QueueService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 대기 순번에 따라 다음 상태 확인 간격을 정한다 — 앞쪽은 자주, 뒤쪽은 드물게 */
class QueuePollIntervalTest {

    @Test
    void nearTheFrontAsksEverySecondAndFarBehindAtMostEvery15Seconds() {
        int perSecond = 100;
        assertEquals(1000, FC_QueueService.nextPollMillis(1, perSecond));
        assertEquals(1000, FC_QueueService.nextPollMillis(200, perSecond), "2초 안에 들어갈 순번");
        assertEquals(2000, FC_QueueService.nextPollMillis(201, perSecond));
        assertEquals(2000, FC_QueueService.nextPollMillis(1000, perSecond), "10초 안");
        assertEquals(3000, FC_QueueService.nextPollMillis(1001, perSecond));
        assertEquals(10_000, FC_QueueService.nextPollMillis(5000, perSecond), "남은 50초의 1/5");
        assertEquals(15_000, FC_QueueService.nextPollMillis(15_000, perSecond), "15초를 넘지 않는다");
        assertEquals(1000, FC_QueueService.nextPollMillis(2, 0), "입장 속도가 0으로 잘못 설정돼도 0으로 나누지 않는다 (1명/초로 본다)");
    }

    /** 1만 5천 명이 줄을 서 있을 때 초당 상태 확인 요청 수 — 모두 1초마다 묻던 때(1만 5천 건)의 1/5 아래 */
    @Test
    void fifteenThousandWaitingSendFarFewerRequests() {
        double perSecondRequests = 0;
        for (long rank = 1; rank <= 15_000; rank++) {
            perSecondRequests += 1000.0 / FC_QueueService.nextPollMillis(rank, 100);
        }
        assertTrue(perSecondRequests < 3000, "초당 " + Math.round(perSecondRequests) + "건");
    }
}
