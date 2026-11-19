package xsgao.k8s.print.receipt;

import org.junit.jupiter.api.Test;
import xsgao.k8s.events.OrderStatus;
import xsgao.k8s.events.PaymentProcessedEvent;
import xsgao.k8s.events.PaymentStatus;
import xsgao.k8s.events.PrintCompletedEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptServiceTest {

    private final ReceiptService receipts = new ReceiptService();

    @Test
    void printsAReceiptForASuccessfulPayment() {
        PaymentProcessedEvent payment = payment(PaymentStatus.SUCCEEDED);

        Optional<PrintCompletedEvent> receipt = receipts.complete(payment);

        assertThat(receipt).isPresent();
        assertThat(receipt.get().orderId()).isEqualTo(payment.orderId());
        assertThat(receipt.get().userId()).isEqualTo(payment.userId());
        assertThat(receipt.get().totalAmount()).isEqualByComparingTo(payment.totalAmount());
        assertThat(receipt.get().status()).isEqualTo(OrderStatus.PRINTED);
        assertThat(receipt.get().eventId()).isNotNull();
    }

    @Test
    void printsNothingForADeclinedPayment() {
        assertThat(receipts.complete(payment(PaymentStatus.FAILED))).isEmpty();
    }

    private static PaymentProcessedEvent payment(PaymentStatus status) {
        return new PaymentProcessedEvent(UUID.randomUUID(), UUID.randomUUID(), "user-1",
                new BigDecimal("5.00"), status, Instant.now());
    }
}
