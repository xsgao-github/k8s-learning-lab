package xsgao.k8s.order.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import xsgao.k8s.events.Topics;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxEventRepository outbox;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private OutboxRelay relay;

    @Test
    void publishesClaimedRowsInOrderAndMarksThemSent() {
        OutboxEvent first = new OutboxEvent("order-1", Topics.ORDER_PLACED, "{\"seq\":1}");
        OutboxEvent second = new OutboxEvent("order-2", Topics.ORDER_PLACED, "{\"seq\":2}");
        when(outbox.claimUnsent(100)).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.relay();

        InOrder inOrder = inOrder(kafkaTemplate);
        inOrder.verify(kafkaTemplate).send(Topics.ORDER_PLACED, "order-1", "{\"seq\":1}");
        inOrder.verify(kafkaTemplate).send(Topics.ORDER_PLACED, "order-2", "{\"seq\":2}");

        assertThat(first.getSentAt()).isNotNull();
        assertThat(second.getSentAt()).isNotNull();
        verify(outbox).deleteBySentAtBefore(any());
    }

    @Test
    void leavesTheRowUnsentWhenPublishingFails() {
        OutboxEvent event = new OutboxEvent("order-1", Topics.ORDER_PLACED, "{}");
        when(outbox.claimUnsent(100)).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        relay.relay();

        assertThat(event.getSentAt()).isNull();
        verify(outbox, never()).deleteBySentAtBefore(any());
    }

    @Test
    void doesNothingWhenTheOutboxIsEmpty() {
        when(outbox.claimUnsent(100)).thenReturn(List.of());

        relay.relay();

        verifyNoInteractions(kafkaTemplate);
        verify(outbox, never()).deleteBySentAtBefore(any());
    }
}
