package dev.elpsy.ticket.delivery;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("ticket.delivery")
public record DeliverySettings(boolean enabled,boolean publisherEnabled,boolean consumerEnabled,String namespace,
 int confirmTimeoutMs,int leaseSeconds,int retryDelayMs,int maxRetries,int prefetch) {
 public DeliverySettings {
  if(namespace==null||!namespace.matches("encore-[a-z0-9-]{1,48}"))throw new IllegalArgumentException("Use an isolated encore-* MQ namespace");
  if(confirmTimeoutMs<100||confirmTimeoutMs>5000||leaseSeconds<10||leaseSeconds>120||leaseSeconds*1000<3*confirmTimeoutMs||retryDelayMs<100||retryDelayMs>30000||maxRetries<1||maxRetries>5||prefetch<1||prefetch>16)throw new IllegalArgumentException("Invalid bounded MQ delivery settings");
 }
 public String exchange(){return namespace+".purchase";}
 public String queue(){return namespace+".purchase.q";}
 public String retryExchange(){return namespace+".retry";}
 public String retryQueue(){return namespace+".retry.q";}
 public String deadExchange(){return namespace+".dead";}
 public String deadQueue(){return namespace+".dead.q";}
}
