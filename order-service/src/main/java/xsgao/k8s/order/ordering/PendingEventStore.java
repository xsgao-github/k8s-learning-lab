package xsgao.k8s.order.ordering;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import xsgao.k8s.events.PrintCompletedEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores events that arrived ahead of the order state and replays them once the
 * state allows it. Every method runs inside the caller's transaction.
 *
 * <p>Deliberately lab-sized: one event type, no timeouts, no compensations. A
 * production order flow would be driven by a saga orchestrator instead; see
 * {@code docs/module-03-payment-print-service.md}.
 */
@Component
public class PendingEventStore {

    private static final Logger log = LoggerFactory.getLogger(PendingEventStore.class);

    private final PendingEventRepository pending;
    private final ObjectMapper objectMapper;

    public PendingEventStore(PendingEventRepository pending, ObjectMapper objectMapper) {
        this.pending = pending;
        this.objectMapper = objectMapper;
    }

    public void deferPrintCompleted(UUID orderId, PrintCompletedEvent event) {
        if (pending.existsByOrderIdAndEventType(orderId, PendingEventType.PRINT_COMPLETED)) {
            log.info("print.completed is already deferred for orderId={}", orderId);
            return;
        }
        pending.save(new PendingEvent(
                orderId, PendingEventType.PRINT_COMPLETED, serialize(orderId, event)));
    }

    public Optional<PrintCompletedEvent> takePrintCompleted(UUID orderId) {
        List<PendingEvent> waiting =
                pending.findByOrderIdAndEventType(orderId, PendingEventType.PRINT_COMPLETED);
        if (waiting.isEmpty()) {
            return Optional.empty();
        }
        PrintCompletedEvent event = deserialize(waiting.get(0));
        pending.deleteAll(waiting);
        return Optional.of(event);
    }

    /**
     * A declined payment can never be followed by a receipt, so anything waiting for
     * this order is contradictory and would otherwise linger forever.
     */
    public void discard(UUID orderId) {
        long discarded = pending.deleteByOrderId(orderId);
        if (discarded > 0) {
            log.warn("Discarded {} deferred event(s) for orderId={} after a declined payment",
                    discarded, orderId);
        }
    }

    private String serialize(UUID orderId, PrintCompletedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not serialize print.completed for order " + orderId, exception);
        }
    }

    private PrintCompletedEvent deserialize(PendingEvent event) {
        try {
            return objectMapper.readValue(event.getPayload(), PrintCompletedEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not read deferred " + event.getEventType()
                            + " for order " + event.getOrderId(), exception);
        }
    }
}
