package xsgao.k8s.print.receipt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import xsgao.k8s.events.OrderStatus;
import xsgao.k8s.events.PaymentProcessedEvent;
import xsgao.k8s.events.PaymentStatus;
import xsgao.k8s.events.PrintCompletedEvent;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);

    /**
     * Prints only a successful payment. Returns empty for a declined one so the
     * listener publishes nothing.
     */
    public Optional<PrintCompletedEvent> complete(PaymentProcessedEvent payment) {
        if (payment.status() != PaymentStatus.SUCCEEDED) {
            log.debug("Skipping receipt for orderId={} paymentStatus={}",
                    payment.orderId(), payment.status());
            return Optional.empty();
        }

        Instant printedAt = Instant.now();
        log.info("RECEIPT orderId={} userId={} totalAmount={} paymentStatus={} printedAt={}",
                payment.orderId(), payment.userId(), payment.totalAmount(), payment.status(), printedAt);

        return Optional.of(new PrintCompletedEvent(
                UUID.randomUUID(),
                payment.orderId(),
                payment.userId(),
                payment.totalAmount(),
                OrderStatus.PRINTED,
                printedAt));
    }
}
