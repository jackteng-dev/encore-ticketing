package dev.jackteng.encore.inventory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
@Component
@org.springframework.context.annotation.Profile("!api")
public class StockReturnDiscoveryWorker {
 private final StockReturnDiscoveryService service;private final boolean enabled;private final int batch;
 public StockReturnDiscoveryWorker(StockReturnDiscoveryService service,@Value("${ticket.stock-return-discovery.enabled:false}")boolean enabled,@Value("${ticket.stock-return-discovery.batch:4}")int batch){if(batch<1||batch>20)throw new IllegalArgumentException("Bounded discovery batch required");this.service=service;this.enabled=enabled;this.batch=batch;}
 @Scheduled(fixedDelayString="${ticket.stock-return-discovery.poll-ms:1000}",initialDelayString="${ticket.stock-return-discovery.initial-delay-ms:5000}")
 public void sweep(){if(!enabled)return;try{for(int i=0;i<batch;i++)if(service.discoverOne().isEmpty())return;}catch(RuntimeException e){LoggerFactory.getLogger(StockReturnDiscoveryWorker.class).warn("Return discovery deferred; PG authorization retained ({})",e.getClass().getSimpleName());}}
}
