package xsgao.k8s.payment.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import xsgao.k8s.events.OrderPlacedEvent;
import xsgao.k8s.events.PaymentProcessedEvent;
import xsgao.k8s.events.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Stateless and deterministic: the same order always produces the same decision,
 * which is what makes redelivery harmless.
 */
@Component
public class PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);
    private static final BigDecimal DECLINE_THRESHOLD = new BigDecimal("1000");

    public PaymentProcessedEvent process(OrderPlacedEvent order) {
        PaymentStatus status = order.totalAmount().compareTo(DECLINE_THRESHOLD) > 0
                ? PaymentStatus.FAILED
                : PaymentStatus.SUCCEEDED;

        log.info("Payment decision orderId={} totalAmount={} status={}",
                order.orderId(), order.totalAmount(), status);

        return new PaymentProcessedEvent(
                UUID.randomUUID(),
                order.orderId(),
                order.userId(),
                order.totalAmount(),
                status,
                Instant.now());
    }
}
