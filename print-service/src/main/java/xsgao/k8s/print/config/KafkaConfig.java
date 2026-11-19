package xsgao.k8s.print.config;

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
import org.springframework.kafka.support.serializer.JsonSerializer;
import xsgao.k8s.events.PaymentProcessedEvent;
import xsgao.k8s.events.Topics;

@Configuration
public class KafkaConfig {

    private static final int PARTITIONS = 3;
    private static final short REPLICAS = 1;

    @Bean
    ProducerFactory<String, Object> kafkaProducerFactory(KafkaProperties properties, ObjectMapper objectMapper) {
        JsonSerializer<Object> valueSerializer = new JsonSerializer<>(objectMapper);
        valueSerializer.setAddTypeInfo(false);
        return new DefaultKafkaProducerFactory<>(
                properties.buildProducerProperties(), new StringSerializer(), valueSerializer);
    }

    @Bean
    KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    NewTopic printCompletedTopic() {
        return TopicBuilder.name(Topics.PRINT_COMPLETED)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, PaymentProcessedEvent> paymentProcessedListenerContainerFactory(
            KafkaProperties properties, ObjectMapper objectMapper) {
        JsonDeserializer<PaymentProcessedEvent> valueDeserializer =
                new JsonDeserializer<>(PaymentProcessedEvent.class, objectMapper, false);
        var consumerFactory = new DefaultKafkaConsumerFactory<>(
                properties.buildConsumerProperties(), new StringDeserializer(), valueDeserializer);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, PaymentProcessedEvent>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }
}
