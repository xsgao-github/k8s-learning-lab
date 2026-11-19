package xsgao.k8s.order.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class OutboxWriter {

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxEventRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    /**
     * Appends an event to the outbox. Runs inside the caller's transaction, so the
     * event exists if and only if the state change that produced it committed.
     */
    public void append(String topic, String aggregateId, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            outbox.save(new OutboxEvent(aggregateId, topic, payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not serialize " + event.getClass().getSimpleName() + " for the outbox", exception);
        }
    }
}
