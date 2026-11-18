package xsgao.k8s.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by print-service after a successful payment has been fulfilled.
 */
public record PrintCompletedEvent(
        UUID eventId,
        UUID orderId,
        String userId,
        BigDecimal totalAmount,
        OrderStatus status,
        Instant timestamp
) {
}
