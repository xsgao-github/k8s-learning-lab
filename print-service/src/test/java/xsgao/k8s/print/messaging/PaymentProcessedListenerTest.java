package xsgao.k8s.print.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import xsgao.k8s.events.OrderStatus;
import xsgao.k8s.events.PaymentProcessedEvent;
import xsgao.k8s.events.PaymentStatus;
import xsgao.k8s.events.PrintCompletedEvent;
import xsgao.k8s.events.Topics;
import xsgao.k8s.print.receipt.ReceiptService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentProcessedListenerTest {

    @Mock
    private ReceiptService receipts;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private PaymentProcessedListener listener;

    @Test
    void publishesTheReceiptKeyedByOrderId() {
        PaymentProcessedEvent payment = payment(PaymentStatus.SUCCEEDED);
        PrintCompletedEvent receipt = receipt(payment.orderId());
        when(receipts.complete(payment)).thenReturn(Optional.of(receipt));
        when(kafkaTemplate.send(Topics.PRINT_COMPLETED, payment.orderId().toString(), receipt))
                .thenReturn(CompletableFuture.completedFuture(null));

        listener.onPaymentProcessed(payment);

        verify(kafkaTemplate).send(Topics.PRINT_COMPLETED, payment.orderId().toString(), receipt);
    }

    @Test
    void publishesNothingForADeclinedPayment() {
        PaymentProcessedEvent payment = payment(PaymentStatus.FAILED);
        when(receipts.complete(payment)).thenReturn(Optional.empty());

        listener.onPaymentProcessed(payment);

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void failsTheListenerWhenTheReceiptCannotBePublished() {
        PaymentProcessedEvent payment = payment(PaymentStatus.SUCCEEDED);
        PrintCompletedEvent receipt = receipt(payment.orderId());
        when(receipts.complete(payment)).thenReturn(Optional.of(receipt));
        when(kafkaTemplate.send(Topics.PRINT_COMPLETED, payment.orderId().toString(), receipt))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatThrownBy(() -> listener.onPaymentProcessed(payment))
                .isInstanceOf(IllegalStateException.class);
    }

    private static PaymentProcessedEvent payment(PaymentStatus status) {
        return new PaymentProcessedEvent(UUID.randomUUID(), UUID.randomUUID(), "user-1",
                new BigDecimal("5.00"), status, Instant.now());
    }

    private static PrintCompletedEvent receipt(UUID orderId) {
        return new PrintCompletedEvent(UUID.randomUUID(), orderId, "user-1",
                new BigDecimal("5.00"), OrderStatus.PRINTED, Instant.now());
    }
}
