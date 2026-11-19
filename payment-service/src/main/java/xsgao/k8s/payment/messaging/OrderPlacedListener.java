package xsgao.k8s.payment.messaging;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import xsgao.k8s.events.OrderPlacedEvent;
import xsgao.k8s.events.PaymentProcessedEvent;
import xsgao.k8s.events.Topics;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OrderPlacedListener {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final PaymentProcessor processor;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OrderPlacedListener(PaymentProcessor processor, KafkaTemplate<String, Object> kafkaTemplate) {
        this.processor = processor;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes synchronously: if the result cannot be published the listener throws,
     * the offset is not committed, and the event is retried instead of being lost.
     */
    @KafkaListener(
            topics = Topics.ORDER_PLACED,
            groupId = "payment-service-group",
            containerFactory = "orderPlacedListenerContainerFactory")
    public void onOrderPlaced(OrderPlacedEvent event) {
        PaymentProcessedEvent processed = processor.process(event);
        try {
            kafkaTemplate.send(Topics.PAYMENT_PROCESSED, event.orderId().toString(), processed)
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while publishing payment.processed for order " + event.orderId(), exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException(
                    "Could not publish payment.processed for order " + event.orderId(), exception);
        }
    }
}
