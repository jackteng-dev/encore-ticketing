package dev.jackteng.encore.delivery;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import dev.jackteng.encore.orders.PurchaseService;
import java.util.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.slf4j.LoggerFactory;
public class PurchaseConsumer implements ChannelAwareMessageListener {
 private final PurchaseService service;private final ObjectMapper json;private final ConfirmedSender sender;private final DeliverySettings settings;
 public PurchaseConsumer(PurchaseService service,ObjectMapper json,ConfirmedSender sender,DeliverySettings settings){this.service=service;this.json=json;this.sender=sender;this.settings=settings;}
 public record Identity(UUID eventId,UUID purchaseId) {}
 private Identity identity(Message message){
  try{
   if(message.getBody().length>1024||!MessageProperties.CONTENT_TYPE_JSON.equals(message.getMessageProperties().getContentType()))return null;
   // Duplicate keys are rejected as well as unknown fields, non-integral schema and non-canonical UUIDs.
   try(var parser=json.getFactory().createParser(message.getBody())){parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
   com.fasterxml.jackson.databind.JsonNode n=json.readTree(parser);if(n==null||parser.nextToken()!=null||!n.isObject()||n.size()!=3||!n.has("eventId")||!n.has("purchaseId")||!n.has("schemaVersion")||!n.get("schemaVersion").isIntegralNumber()||!n.get("schemaVersion").canConvertToInt()||n.get("schemaVersion").intValue()!=1||!n.get("eventId").isTextual()||!n.get("purchaseId").isTextual())return null;
   var event=UUID.fromString(n.get("eventId").textValue());var purchase=UUID.fromString(n.get("purchaseId").textValue());
   if(!event.toString().equals(n.get("eventId").textValue())||!purchase.toString().equals(n.get("purchaseId").textValue())||!event.toString().equals(message.getMessageProperties().getMessageId()))return null;
   return new Identity(event,purchase);
   }
  }catch(Exception e){return null;}
 }
 @Override public void onMessage(Message message,Channel channel)throws Exception {
  long tag=message.getMessageProperties().getDeliveryTag();var identity=identity(message);
  if(identity==null){channel.basicReject(tag,false);return;}
  Object header=message.getMessageProperties().getHeaders().get("ticket-retry");
  if(!(header instanceof Integer retries)||retries<0||retries>settings.maxRetries()){channel.basicReject(tag,false);return;}
  try{
   // Proxy returns only after PostgreSQL commit. Invalid identity is not a successful duplicate.
   var result=service.deliver(identity.eventId(),identity.purchaseId());
   if(result==PurchaseService.Delivery.INVALID){channel.basicReject(tag,false);return;}
  }catch(RuntimeException e){
   var properties=new MessageProperties();properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);properties.setMessageId(identity.eventId().toString());properties.setHeader("ticket-retry",Math.min(settings.maxRetries(),retries+1));
   String target=retries>=settings.maxRetries()?settings.deadExchange():settings.retryExchange();
   String outcome=sender.send(target,"purchase",new Message(message.getBody(),properties));
   if(!outcome.equals("CONFIRMED")){
    // Leave original unacknowledged: channel recovery requeues it, with container recovery backoff.
    channel.close();throw new IllegalStateException("Retry publication unknown; original delivery retained");
   }
   LoggerFactory.getLogger(PurchaseConsumer.class).warn("Purchase delivery deferred to {} ({})",retries>=settings.maxRetries()?"dead-letter":"retry",e.getClass().getSimpleName());
  }
  // ACK failures propagate; a committed purchase is safe to redeliver under its original event ID.
  channel.basicAck(tag,false);
 }
}
