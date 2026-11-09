package dev.elpsy.ticket.admission;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class RedisResources {
 @Bean(destroyMethod="shutdown") ClientResources ticketRedisResources(){return DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();}
}
