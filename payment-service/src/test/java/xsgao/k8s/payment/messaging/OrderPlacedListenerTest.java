package xsgao.k8s.payment.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import xsgao.k8s.events.OrderPlacedEvent;
import xsgao.k8s.events.OrderStatus;
import xsgao.k8s.events.PaymentProcessedEvent;
import xsgao.k8s.events.PaymentStatus;
import xsgao.k8s.events.Topics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPlacedListenerTest {

    @Mock
    private PaymentProcessor processor;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private OrderPlacedListener listener;

    @Test
    void publishesThePaymentResultKeyedByOrderId() {
        OrderPlacedEvent placed = order();
        PaymentProcessedEvent processed = payment(placed.orderId());
        when(processor.process(placed)).thenReturn(processed);
        when(kafkaTemplate.send(Topics.PAYMENT_PROCESSED, placed.orderId().toString(), processed))
                .thenReturn(CompletableFuture.completedFuture(null));

        listener.onOrderPlaced(placed);

        verify(kafkaTemplate).send(Topics.PAYMENT_PROCESSED, placed.orderId().toString(), processed);
    }

    @Test
    void failsTheListenerWhenTheResultCannotBePublished() {
        OrderPlacedEvent placed = order();
        PaymentProcessedEvent processed = payment(placed.orderId());
        when(processor.process(placed)).thenReturn(processed);
        when(kafkaTemplate.send(Topics.PAYMENT_PROCESSED, placed.orderId().toString(), processed))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatThrownBy(() -> listener.onOrderPlaced(placed))
                .isInstanceOf(IllegalStateException.class);
    }

    private static OrderPlacedEvent order() {
        return new OrderPlacedEvent(UUID.randomUUID(), UUID.randomUUID(), "user-1",
                new BigDecimal("5.00"), OrderStatus.PENDING_PAYMENT, Instant.now());
    }

    private static PaymentProcessedEvent payment(UUID orderId) {
        return new PaymentProcessedEvent(UUID.randomUUID(), orderId, "user-1",
                new BigDecimal("5.00"), PaymentStatus.SUCCEEDED, Instant.now());
    }
}
