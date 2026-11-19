package xsgao.k8s.order.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xsgao.k8s.events.OrderPlacedEvent;
import xsgao.k8s.events.OrderStatus;
import xsgao.k8s.events.Topics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboxWriterTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final OutboxWriter writer = new OutboxWriter(repository, objectMapper);

    @Test
    void storesTheEventAsJsonWithoutTypeHeaders() throws Exception {
        OrderPlacedEvent event = new OrderPlacedEvent(UUID.randomUUID(), UUID.randomUUID(), "user-1",
                new BigDecimal("5.00"), OrderStatus.PENDING_PAYMENT, Instant.parse("2026-01-01T00:00:00Z"));

        writer.append(Topics.ORDER_PLACED, event.orderId().toString(), event);

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(saved.capture());
        OutboxEvent row = saved.getValue();

        assertThat(row.getTopic()).isEqualTo(Topics.ORDER_PLACED);
        assertThat(row.getAggregateId()).isEqualTo(event.orderId().toString());
        assertThat(row.getSentAt()).isNull();

        JsonNode payload = objectMapper.readTree(row.getPayload());
        assertThat(payload.get("orderId").asText()).isEqualTo(event.orderId().toString());
        assertThat(payload.get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(payload.get("timestamp").asText()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(row.getPayload()).doesNotContain("@class");
    }
}
