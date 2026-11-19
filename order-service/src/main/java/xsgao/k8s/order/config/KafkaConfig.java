package xsgao.k8s.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import xsgao.k8s.events.PaymentProcessedEvent;
import xsgao.k8s.events.PrintCompletedEvent;
import xsgao.k8s.events.Topics;

@Configuration
public class KafkaConfig {

    private static final int PARTITIONS = 3;
    private static final short REPLICAS = 1;

    /**
     * The relay publishes the outbox payload verbatim, so both key and value are
     * already strings. JSON is produced once, when the outbox row is written.
     */
    @Bean
    ProducerFactory<String, String> kafkaProducerFactory(KafkaProperties properties) {
        return new DefaultKafkaProducerFactory<>(
                properties.buildProducerProperties(), new StringSerializer(), new StringSerializer());
    }

    @Bean
    KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    NewTopic orderPlacedTopic() {
        return TopicBuilder.name(Topics.ORDER_PLACED)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, PaymentProcessedEvent> paymentProcessedListenerContainerFactory(
            KafkaProperties properties, ObjectMapper objectMapper) {
        return listenerContainerFactory(properties,
                new JsonDeserializer<>(PaymentProcessedEvent.class, objectMapper, false));
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, PrintCompletedEvent> printCompletedListenerContainerFactory(
            KafkaProperties properties, ObjectMapper objectMapper) {
        return listenerContainerFactory(properties,
                new JsonDeserializer<>(PrintCompletedEvent.class, objectMapper, false));
    }

    /**
     * The consumer factory is built inside each listener factory on purpose: a
     * single {@code ConsumerFactory} bean cannot carry two different value types.
     */
    private <T> ConcurrentKafkaListenerContainerFactory<String, T> listenerContainerFactory(
            KafkaProperties properties, JsonDeserializer<T> valueDeserializer) {
        var consumerFactory = new DefaultKafkaConsumerFactory<>(
                properties.buildConsumerProperties(), new StringDeserializer(), valueDeserializer);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, T>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }
}
