package xsgao.k8s.print.messaging;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import xsgao.k8s.events.PaymentProcessedEvent;
import xsgao.k8s.events.PrintCompletedEvent;
import xsgao.k8s.events.Topics;
import xsgao.k8s.print.receipt.ReceiptService;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class PaymentProcessedListener {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final ReceiptService receipts;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PaymentProcessedListener(ReceiptService receipts, KafkaTemplate<String, Object> kafkaTemplate) {
        this.receipts = receipts;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(
            topics = Topics.PAYMENT_PROCESSED,
            groupId = "print-service-group",
            containerFactory = "paymentProcessedListenerContainerFactory")
    public void onPaymentProcessed(PaymentProcessedEvent event) {
        receipts.complete(event).ifPresent(receipt -> publish(event, receipt));
    }

    private void publish(PaymentProcessedEvent payment, PrintCompletedEvent receipt) {
        try {
            kafkaTemplate.send(Topics.PRINT_COMPLETED, payment.orderId().toString(), receipt)
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while publishing print.completed for order " + payment.orderId(), exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException(
                    "Could not publish print.completed for order " + payment.orderId(), exception);
        }
    }
}
