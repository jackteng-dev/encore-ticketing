package dev.elpsy.ticket.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ConnectionFactory;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Disposable runtime probe, test classpath only; never a production endpoint or image layer. */
public final class MultiWorkerBrokerProbe {
 private static String env(String name) {
  String value=System.getenv(name);
  if(value==null||value.isBlank())throw new IllegalArgumentException("Missing probe setting: "+name);
  return value;
 }
 public static void main(String[] args)throws Exception {
  String namespace=env("TICKET_MQ_NAMESPACE");
  if(!namespace.matches("encore-deploy-[0-9]{14}-[a-f0-9]{8}")||args.length<1)
   throw new IllegalArgumentException("Requires generated disposable deployment namespace and action");
  var factory=new ConnectionFactory();factory.setHost("rabbitmq");factory.setPort(5672);
  factory.setUsername(env("TICKET_MQ_USER"));factory.setPassword(env("TICKET_MQ_PASSWORD"));
  factory.setVirtualHost(env("TICKET_MQ_VHOST"));factory.setAutomaticRecoveryEnabled(false);
  factory.setConnectionTimeout(5000);factory.setHandshakeTimeout(5000);
  var json=new ObjectMapper();
  try(var connection=factory.newConnection("ticket-m5c-private-probe");var channel=connection.createChannel()) {
   if(args[0].equals("snapshot")&&args.length==1) {
    var main=channel.queueDeclarePassive(namespace+".purchase.q");
    var retry=channel.queueDeclarePassive(namespace+".retry.q");
    var dead=channel.queueDeclarePassive(namespace+".dead.q");
    System.out.println(json.writeValueAsString(Map.of("consumers",main.getConsumerCount(),
     "ready",main.getMessageCount(),"retryReady",retry.getMessageCount(),"deadReady",dead.getMessageCount())));
   } else if(args[0].equals("duplicate")&&args.length==4) {
    UUID event=UUID.fromString(args[1]),purchase=UUID.fromString(args[2]);
    int copies=Integer.parseInt(args[3]);
    if(!event.toString().equals(args[1])||!purchase.toString().equals(args[2])||copies<1||copies>16)
     throw new IllegalArgumentException("Canonical identity and bounded copies required");
    var returned=new AtomicBoolean();channel.addReturnListener(message->returned.set(true));channel.confirmSelect();
    var properties=new AMQP.BasicProperties.Builder().contentType("application/json").contentEncoding("UTF-8")
     .deliveryMode(2).messageId(event.toString()).headers(Map.of("ticket-retry",0)).build();
    byte[] body=json.writeValueAsBytes(Map.of("eventId",event,"purchaseId",purchase,"schemaVersion",1));
    for(int i=0;i<copies;i++)channel.basicPublish(namespace+".purchase","purchase",true,properties,body);
    channel.waitForConfirmsOrDie(5000);
    if(returned.get())throw new IllegalStateException("Probe publication returned");
    System.out.println(json.writeValueAsString(Map.of("confirmedCopies",copies,"persistent",true,"originalIdentity",true)));
   } else throw new IllegalArgumentException("Unknown probe action");
  }
 }
}
