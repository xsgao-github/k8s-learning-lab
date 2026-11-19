package xsgao.k8s.payment.messaging;

import org.junit.jupiter.api.Test;
import xsgao.k8s.events.OrderPlacedEvent;
import xsgao.k8s.events.OrderStatus;
import xsgao.k8s.events.PaymentProcessedEvent;
import xsgao.k8s.events.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentProcessorTest {

    private final PaymentProcessor processor = new PaymentProcessor();

    @Test
    void approvesAnOrderAtExactlyTheThreshold() {
        PaymentProcessedEvent result = processor.process(order(new BigDecimal("1000.00")));

        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void declinesAnOrderAboveTheThreshold() {
        PaymentProcessedEvent result = processor.process(order(new BigDecimal("1000.01")));

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void carriesTheOrderDetailsIntoTheResult() {
        OrderPlacedEvent placed = order(new BigDecimal("5.00"));

        PaymentProcessedEvent result = processor.process(placed);

        assertThat(result.orderId()).isEqualTo(placed.orderId());
        assertThat(result.userId()).isEqualTo(placed.userId());
        assertThat(result.totalAmount()).isEqualByComparingTo(placed.totalAmount());
        assertThat(result.eventId()).isNotNull();
        assertThat(result.timestamp()).isNotNull();
    }

    private static OrderPlacedEvent order(BigDecimal totalAmount) {
        return new OrderPlacedEvent(UUID.randomUUID(), UUID.randomUUID(), "user-1", totalAmount,
                OrderStatus.PENDING_PAYMENT, Instant.now());
    }
}
