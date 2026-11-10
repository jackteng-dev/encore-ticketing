package dev.jackteng.encore.delivery;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.transaction.support.TransactionSynchronizationManager;
public class ConfirmedSender {
 private final RabbitTemplate template;private final DeliverySettings settings;
 public ConfirmedSender(RabbitTemplate template,DeliverySettings settings){this.template=template;this.settings=settings;}
 /** UNKNOWN includes connection loss, timeout, interrupted wait and lost confirm; caller retains the original ID. */
 public String send(String exchange,String key,Message message){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("MQ confirm must not hold a database transaction");
  var correlation=new CorrelationData(java.util.UUID.randomUUID().toString());
  try{
   template.send(exchange,key,message,correlation);var confirm=correlation.getFuture().get(settings.confirmTimeoutMs(),TimeUnit.MILLISECONDS);
   if(correlation.getReturned()!=null)return "RETURNED";
   // Spring synthesizes a negative confirm with a reason when the channel/connection closes.
   // It is an unknown transport result, not a broker basic.nack.
   return confirm.isAck()?"CONFIRMED":confirm.getReason()==null?"NACK":"UNKNOWN";
  }catch(InterruptedException e){Thread.currentThread().interrupt();return "UNKNOWN";}catch(Exception e){return "UNKNOWN";}
 }
}
