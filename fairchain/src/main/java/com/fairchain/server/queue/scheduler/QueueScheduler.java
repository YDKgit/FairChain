package com.fairchain.server.queue.scheduler;

import com.fairchain.server.common.config.FairChainProperties;
import com.fairchain.server.queue.repository.QueueRepository;
import com.fairchain.server.queue.service.FC_QueueService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** SD-202-03: 1초마다 대기열이 열린 공연별로 입장 허용 */
@Component
public class QueueScheduler {

    private final FC_QueueService queueService;
    private final QueueRepository queueRepository;
    private final int admitPerSecond;

    public QueueScheduler(FC_QueueService queueService, QueueRepository queueRepository, FairChainProperties properties) {
        this.queueService = queueService;
        this.queueRepository = queueRepository;
        this.admitPerSecond = properties.queue().admitPerSecond();
    }

    @Scheduled(fixedRate = 1000)
    public void admitEverySecond() {
        for (Long eventId : queueRepository.activeEventIds()) {
            queueService.admitTopN(eventId, admitPerSecond);
        }
    }
}
