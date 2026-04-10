package net.samit.document_processing_v2.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    private final DocProcessingProperties props;

    public RabbitMqConfig(DocProcessingProperties props) {
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
                .withArgument("x-message-ttl", 86_400_000L)
                .build();
    }

    @Bean
    Queue deadLetterQueue() {
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

    @Bean
    MessageConverter jacksonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean(name = "simpleRabbitListenerContainerFactory")
    SimpleRabbitListenerContainerFactory simpleRabbitListenerContainerFactory(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }
}
