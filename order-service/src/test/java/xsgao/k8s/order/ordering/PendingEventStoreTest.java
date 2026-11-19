package xsgao.k8s.order.ordering;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xsgao.k8s.events.OrderStatus;
import xsgao.k8s.events.PrintCompletedEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingEventStoreTest {

    private final PendingEventRepository repository = mock(PendingEventRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final PendingEventStore store = new PendingEventStore(repository, objectMapper);

    @Test
    void storesTheOriginalPayloadForADeferredReceipt() {
        UUID orderId = UUID.randomUUID();
        store.deferPrintCompleted(orderId, receipt(orderId));

        ArgumentCaptor<PendingEvent> saved = ArgumentCaptor.forClass(PendingEvent.class);
        verify(repository).save(saved.capture());
        PendingEvent row = saved.getValue();

        assertThat(row.getOrderId()).isEqualTo(orderId);
        assertThat(row.getEventType()).isEqualTo(PendingEventType.PRINT_COMPLETED);
        assertThat(row.getPayload()).contains(orderId.toString()).doesNotContain("@class");
    }

    @Test
    void doesNotDeferTheSameEventTwice() {
        UUID orderId = UUID.randomUUID();
        when(repository.existsByOrderIdAndEventType(orderId, PendingEventType.PRINT_COMPLETED))
                .thenReturn(true);

        store.deferPrintCompleted(orderId, receipt(orderId));

        verify(repository, never()).save(any());
    }

    @Test
    void returnsAndRemovesTheDeferredReceipt() {
        UUID orderId = UUID.randomUUID();
        PrintCompletedEvent receipt = receipt(orderId);
        PendingEvent row = new PendingEvent(orderId, PendingEventType.PRINT_COMPLETED,
                write(receipt));
        when(repository.findByOrderIdAndEventType(orderId, PendingEventType.PRINT_COMPLETED))
                .thenReturn(List.of(row));

        Optional<PrintCompletedEvent> replayed = store.takePrintCompleted(orderId);

        assertThat(replayed).isPresent();
        assertThat(replayed.get().orderId()).isEqualTo(orderId);
        assertThat(replayed.get().status()).isEqualTo(OrderStatus.PRINTED);
        verify(repository).deleteAll(List.of(row));
    }

    @Test
    void returnsEmptyWhenNothingIsWaiting() {
        UUID orderId = UUID.randomUUID();
        when(repository.findByOrderIdAndEventType(orderId, PendingEventType.PRINT_COMPLETED))
                .thenReturn(List.of());

        assertThat(store.takePrintCompleted(orderId)).isEmpty();
        verify(repository, never()).deleteAll(any());
    }

    @Test
    void reportsHowManyEventsWereDiscarded() {
        UUID orderId = UUID.randomUUID();
        when(repository.deleteByOrderId(orderId)).thenReturn(1L);

        store.discard(orderId);

        verify(repository).deleteByOrderId(eq(orderId));
    }

    private PrintCompletedEvent receipt(UUID orderId) {
        return new PrintCompletedEvent(UUID.randomUUID(), orderId, "user-1",
                new BigDecimal("5.00"), OrderStatus.PRINTED, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private String write(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
