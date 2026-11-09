package dev.elpsy.ticket.delivery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
@Component
@ConditionalOnProperty(name="ticket.delivery.enabled",havingValue="true")
@org.springframework.context.annotation.Profile("!api")
public class OutboxPublisher {
 private final OutboxStore store;private final ConfirmedSender sender;private final DeliverySettings settings;private final ObjectMapper json;
 public OutboxPublisher(OutboxStore store,ConfirmedSender sender,DeliverySettings settings,ObjectMapper json){this.store=store;this.sender=sender;this.settings=settings;this.json=json;}
 public Message message(OutboxStore.Claim claim){
  try{var p=new MessageProperties();p.setContentType(MessageProperties.CONTENT_TYPE_JSON);p.setContentEncoding("UTF-8");p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);p.setMessageId(claim.eventId().toString());p.setHeader("ticket-retry",0);
   return new Message(json.writeValueAsBytes(Map.of("eventId",claim.eventId(),"purchaseId",claim.purchaseId(),"schemaVersion",1)),p);
  }catch(Exception e){throw new IllegalStateException("Cannot encode outbox identity",e);}
 }
 public String publish(OutboxStore.Claim claim){String outcome=sender.send(settings.exchange(),"purchase",message(claim));if(outcome.equals("CONFIRMED"))store.confirmed(claim);else store.failed(claim,outcome);return outcome;}
 @Scheduled(fixedDelayString="${ticket.delivery.poll-ms:500}",initialDelay=5000) public void sweep(){
  if(!settings.publisherEnabled())return;
  try{for(int i=0;i<10;i++){var next=store.claim(settings.leaseSeconds());if(next.isEmpty())return;if(!publish(next.get()).equals("CONFIRMED"))return;}}
  catch(RuntimeException e){LoggerFactory.getLogger(OutboxPublisher.class).warn("Outbox delivery deferred ({})",e.getClass().getSimpleName());}
 }
}
