package dev.samitkumar.ingestion_service.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class RabbitMqConfig {

    private final IngestionProperties props;

    public RabbitMqConfig(IngestionProperties props) {
        this.props = props;
    }

    // ── Exchange ───────────────────────────────────────────────────────

    @Bean
    TopicExchange ingestionExchange() {
        return ExchangeBuilder
                .topicExchange(props.messaging().exchange())
                .durable(true)
                .build();
    }

    // ── Queues ────────────────────────────────────────────────────────

    @Bean
    Queue fileProcessingQueue() {
        return QueueBuilder
                .durable(props.messaging().queue())
                .withArgument("x-dead-letter-exchange", "")      // default exchange
                .withArgument("x-dead-letter-routing-key", props.messaging().deadLetterQueue())
                .withArgument("x-message-ttl", 86_400_000)       // 24 h TTL
                .build();
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder
                .durable(props.messaging().deadLetterQueue())
                .build();
    }

    // ── Binding ───────────────────────────────────────────────────────

    @Bean
    Binding fileProcessingBinding() {
        return BindingBuilder
                .bind(fileProcessingQueue())
                .to(ingestionExchange())
                .with(props.messaging().routingKey());
    }

    // ── Message converter — Jackson 3 JSON ────────────────────────────

    @Bean
    MessageConverter jacksonMessageConverter(JsonMapper jsonMapper) {
        // Jackson2JsonMessageConverter works with Jackson 3 (same artifact,
        // Spring AMQP 4 ships with Jackson 3 support out of the box).
        var converter = new JacksonJsonMessageConverter(jsonMapper);
        converter.setCreateMessageIds(true);
        return converter;
    }

    // ── RabbitTemplate ────────────────────────────────────────────────

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                  MessageConverter messageConverter) {
        var template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setExchange(props.messaging().exchange());
        template.setRoutingKey(props.messaging().routingKey());
        // Publisher confirms + returns for reliable delivery
        template.setMandatory(true);
        return template;
    }
}
