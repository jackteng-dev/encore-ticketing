package dev.jackteng.encore.delivery;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
@Configuration
@EnableConfigurationProperties(DeliverySettings.class)
public class MqConfiguration {
 @Configuration
 @ConditionalOnProperty(name="ticket.delivery.enabled",havingValue="true")
 static class Enabled {
  @Bean Declarables purchaseTopology(DeliverySettings s){
   var main=new DirectExchange(s.exchange(),true,false);var retry=new DirectExchange(s.retryExchange(),true,false);var dead=new DirectExchange(s.deadExchange(),true,false);
   // One-node quorum queues provide durable delivery, not host-level HA. Confirm internal DLX hops.
   var q=QueueBuilder.durable(s.queue()).quorum().maxLength(10000).withArgument("x-dead-letter-strategy","at-least-once").withArgument("x-overflow","reject-publish").deadLetterExchange(s.deadExchange()).deadLetterRoutingKey("purchase").build();
   var rq=QueueBuilder.durable(s.retryQueue()).quorum().maxLength(10000).withArgument("x-dead-letter-strategy","at-least-once").withArgument("x-overflow","reject-publish").ttl(s.retryDelayMs()).deadLetterExchange(s.exchange()).deadLetterRoutingKey("purchase").build();
   var dq=QueueBuilder.durable(s.deadQueue()).quorum().maxLength(10000).withArgument("x-overflow","reject-publish").build();
   return new Declarables(main,retry,dead,q,rq,dq,BindingBuilder.bind(q).to(main).with("purchase"),BindingBuilder.bind(rq).to(retry).with("purchase"),BindingBuilder.bind(dq).to(dead).with("purchase"));
  }
  @Bean RabbitTemplate ticketRabbitTemplate(ConnectionFactory factory){var template=new RabbitTemplate(factory);template.setMandatory(true);return template;}
  @Bean RabbitAdmin ticketRabbitAdmin(ConnectionFactory factory){return new RabbitAdmin(factory);}
  @Bean ConfirmedSender confirmedSender(RabbitTemplate template,DeliverySettings settings){return new ConfirmedSender(template,settings);}
  @Bean PurchaseConsumer purchaseConsumer(dev.jackteng.encore.orders.PurchaseService service,com.fasterxml.jackson.databind.ObjectMapper json,ConfirmedSender sender,DeliverySettings settings){return new PurchaseConsumer(service,json,sender,settings);}
  @Bean SimpleMessageListenerContainer purchaseListener(ConnectionFactory factory,PurchaseConsumer consumer,DeliverySettings s,org.springframework.core.env.Environment environment){
   var container=new SimpleMessageListenerContainer(factory);container.setQueueNames(s.queue());container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
   container.setPrefetchCount(s.prefetch());container.setConcurrentConsumers(1);container.setMaxConcurrentConsumers(1);
   container.setRecoveryInterval(5000);container.setAutoStartup(s.consumerEnabled()&&!environment.acceptsProfiles(org.springframework.core.env.Profiles.of("api")));container.setMessageListener(consumer);return container;
  }
 }
}
