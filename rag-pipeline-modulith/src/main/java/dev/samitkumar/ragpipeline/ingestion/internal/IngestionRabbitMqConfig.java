package dev.samitkumar.ragpipeline.ingestion.internal;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class IngestionRabbitMqConfig {

    private final IngestionProperties props;

    IngestionRabbitMqConfig(IngestionProperties props) {
        this.props = props;
    }

    @Bean
    TopicExchange ingestionExchange() {
        return ExchangeBuilder
                .topicExchange(props.messaging().exchange())
                .durable(true)
                .build();
    }

    @Bean
    Queue fileProcessingQueue() {
        return QueueBuilder
                .durable(props.messaging().queue())
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", props.messaging().deadLetterQueue())
                .withArgument("x-message-ttl", 86_400_000L)  // 24 h TTL
                .build();
    }

    @Bean
    Queue ingestionDeadLetterQueue() {
        return QueueBuilder
                .durable(props.messaging().deadLetterQueue())
                .build();
    }

    @Bean
    Binding fileProcessingBinding() {
        return BindingBuilder
                .bind(fileProcessingQueue())
                .to(ingestionExchange())
                .with(props.messaging().routingKey());
    }
}
