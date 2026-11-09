package dev.elpsy.ticket.monitoring;
import dev.elpsy.ticket.delivery.DeliverySettings;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Service;
@Service
public class DependencyProbe {
 public record Queue(long ready,long consumers) {}
 public record Broker(String state,Map<String,Queue> queues) {}
 private final StringRedisTemplate redis;private final DeliverySettings delivery;private final Environment env;
 public DependencyProbe(StringRedisTemplate redis,DeliverySettings delivery,Environment env){this.redis=redis;this.delivery=delivery;this.env=env;}
 public String redis(){try{return "PONG".equals(redis.execute((RedisCallback<String>)c->c.ping()))?"UP":"UNAVAILABLE";}catch(org.springframework.dao.DataAccessException e){return "UNAVAILABLE";}}
 public Broker broker(){
  if(!delivery.enabled())return new Broker("DISABLED",Map.of());
  // Dedicated raw client, never Spring RabbitAdmin/shared connection: no topology auto-declaration.
  var f=new com.rabbitmq.client.ConnectionFactory();f.setHost(env.getProperty("spring.rabbitmq.host","127.0.0.1"));f.setPort(env.getProperty("spring.rabbitmq.port",Integer.class,5672));f.setUsername(env.getProperty("spring.rabbitmq.username","guest"));f.setPassword(env.getProperty("spring.rabbitmq.password",""));f.setVirtualHost(env.getProperty("spring.rabbitmq.virtual-host","/"));f.setConnectionTimeout(1000);f.setHandshakeTimeout(1000);f.setChannelRpcTimeout(1000);f.setShutdownTimeout(500);f.setAutomaticRecoveryEnabled(false);f.setTopologyRecoveryEnabled(false);
  try(var connection=f.newConnection("encore-monitor-read");var channel=connection.createChannel()){
   var queues=new LinkedHashMap<String,Queue>();String[] names={delivery.queue(),delivery.retryQueue(),delivery.deadQueue()};String[] labels={"main","retry","dead"};
   for(int i=0;i<names.length;i++){var q=channel.queueDeclarePassive(names[i]);queues.put(labels[i],new Queue(q.getMessageCount(),q.getConsumerCount()));}
   return new Broker("UP",Collections.unmodifiableMap(queues));
  }catch(Exception e){Throwable cause=e;while(cause!=null){if(cause instanceof com.rabbitmq.client.ShutdownSignalException s&&s.getReason()instanceof com.rabbitmq.client.AMQP.Channel.Close close&&close.getReplyCode()==404)return new Broker("MISSING",Map.of());cause=cause.getCause();}return new Broker("UNAVAILABLE",Map.of());}
 }
}
