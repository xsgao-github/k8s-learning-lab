package xsgao.k8s.order.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final int BATCH_SIZE = 100;
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RETENTION = Duration.ofHours(1);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxRelay(OutboxEventRepository outbox, KafkaTemplate<String, String> kafkaTemplate) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes unsent rows in id order and marks them sent in the same transaction.
     * A failed send stops the batch before that row is marked, so the next tick
     * retries it and nothing is skipped or reordered.
     */
    @Scheduled(fixedDelay = 500)
    @Transactional
    public void relay() {
        List<OutboxEvent> pending = outbox.claimUnsent(BATCH_SIZE);
        if (pending.isEmpty()) {
            return;
        }

        for (OutboxEvent event : pending) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload())
                        .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                event.markSent();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                log.warn("Outbox relay interrupted at id={}; retrying next tick", event.getId());
                return;
            } catch (ExecutionException | TimeoutException exception) {
                log.warn("Outbox relay could not publish id={} topic={}; retrying next tick",
                        event.getId(), event.getTopic(), exception);
                return;
            }
        }

        log.info("Outbox relay published {} event(s)", pending.size());
        outbox.deleteBySentAtBefore(Instant.now().minus(RETENTION));
    }
}
