package dev.elpsy.ticket.monitoring;
import dev.elpsy.ticket.common.ApiErrors.Problem;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
@Service
public class MonitoringService {
 public record Snapshot(boolean enabled,String localRole,int staleSeconds,long durationMs,MonitoringStore.Data postgres,String redis,DependencyProbe.Broker broker,Map<String,Boolean> capabilities) {}
 private final MonitoringStore store;private final DependencyProbe probe;private final MonitoringSettings settings;private final RuntimeHeartbeat heartbeat;private final Environment env;
 public MonitoringService(MonitoringStore store,DependencyProbe probe,MonitoringSettings settings,RuntimeHeartbeat heartbeat,Environment env){this.store=store;this.probe=probe;this.settings=settings;this.heartbeat=heartbeat;this.env=env;}
 public Snapshot snapshot(String actor){
  long started=System.nanoTime();
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Dependency probes require no outer business transaction");
  MonitoringStore.Data data;
  try{data=store.read(actor,heartbeat.role());}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException e){throw new Problem(503,"MONITORING_UNAVAILABLE","監控快照暫時無法核對，舊資料不能當目前健康");}
  var flags=new java.util.LinkedHashMap<String,Boolean>();for(String name:java.util.List.of("delivery.enabled","delivery.publisher-enabled","delivery.consumer-enabled","stock-recovery.enabled","stock-return.enabled","stock-return-recovery.enabled","stock-return-discovery.enabled","pre-debit.enabled"))flags.put(name,env.getProperty("ticket."+name,Boolean.class,false));
  String redis=settings.enabled()?probe.redis():"NOT_CHECKED";var broker=settings.enabled()?probe.broker():new DependencyProbe.Broker("NOT_CHECKED",Map.of());
  return new Snapshot(settings.enabled(),heartbeat.role(),settings.staleSeconds(),java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),data,redis,broker,java.util.Collections.unmodifiableMap(flags));
 }
}
