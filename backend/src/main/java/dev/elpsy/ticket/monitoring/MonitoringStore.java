package dev.elpsy.ticket.monitoring;
import dev.elpsy.ticket.common.ApiErrors.Problem;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
public class MonitoringStore {
 public record Role(String role,int expected,long recent,long stale,long recentConsumerLifecycles) {}
 public record Data(OffsetDateTime observedAt,List<Role> roles,Map<String,Long> counts) {}
 private final JdbcTemplate db;private final MonitoringSettings settings;
 public MonitoringStore(JdbcTemplate db,MonitoringSettings settings){this.db=db;this.settings=settings;}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=4) public Data read(String name,String localRole){
  db.execute("SET LOCAL statement_timeout='1500ms'");
  if(db.queryForObject("SELECT count(*) FROM staff_account WHERE username=? AND enabled",Integer.class,name)!=1)throw new Problem(403,"FORBIDDEN","請由目前員工核對監控");
  var time=db.queryForObject("SELECT statement_timestamp()",OffsetDateTime.class);if(!settings.enabled())return new Data(time,List.of(),Map.of());
  var roles=new ArrayList<Role>();
  for(String role:List.of("API","WORKER","EMBEDDED")){var rows=db.queryForMap("SELECT count(*) FILTER(WHERE last_seen>=?::timestamptz-make_interval(secs=>?)) AS recent,count(*) FILTER(WHERE last_seen<?::timestamptz-make_interval(secs=>?)) AS stale,count(*) FILTER(WHERE last_seen>=?::timestamptz-make_interval(secs=>?) AND consumer_running) AS consumers FROM runtime_heartbeat WHERE role=?",time,settings.staleSeconds(),time,settings.staleSeconds(),time,settings.staleSeconds(),role);roles.add(new Role(role,role.equals("API")?settings.expectedApis():role.equals("WORKER")?settings.expectedWorkers():localRole.equals("EMBEDDED")?1:0,((Number)rows.get("recent")).longValue(),((Number)rows.get("stale")).longValue(),((Number)rows.get("consumers")).longValue()));}
  var counts=new LinkedHashMap<String,Long>();
  for(var entry:QUERIES.entrySet())counts.put(entry.getKey(),db.queryForObject(entry.getValue(),Long.class));
  return new Data(time,List.copyOf(roles),Collections.unmodifiableMap(counts));
 }
 private static final Map<String,String> QUERIES=queries();
 private static Map<String,String> queries(){var q=new LinkedHashMap<String,String>();
  q.put("purchaseProcessing","SELECT count(*) FROM purchase_request WHERE status='PROCESSING'");
  q.put("outboxUnpublished","SELECT count(*) FROM outbox_publication WHERE published_at IS NULL");
  q.put("outboxExpiredLeases","SELECT count(*) FROM outbox_publication WHERE published_at IS NULL AND lease_until<=clock_timestamp()");
  q.put("outboxUnknown","SELECT count(*) FROM outbox_publication WHERE published_at IS NULL AND last_error='UNKNOWN'");
  for(String state:List.of("READY","CLOSED","DIRTY"))q.put("gate"+state,"SELECT count(*) FROM stock_projection WHERE state='"+state+"'");
  for(String state:List.of("WAITING","LEASED","REVIEW"))for(String kind:List.of("stock","return"))q.put(kind+state,"SELECT count(*) FROM "+(kind.equals("stock")?"stock_recovery_job":"stock_return_recovery_job")+" WHERE state='"+state+"'");
  for(String kind:List.of("stock","return")){String t=kind.equals("stock")?"stock_recovery_job":"stock_return_recovery_job";q.put(kind+"ExpiredLeases","SELECT count(*) FROM "+t+" WHERE state='LEASED' AND lease_until<=clock_timestamp()");q.put(kind+"ExhaustedReview","SELECT count(*) FROM "+t+" WHERE state='REVIEW' AND attempts=max_attempts");}
  for(String state:List.of("ACTIVE","EXHAUSTED","STOPPED"))q.put("policy"+state,"SELECT count(*) FROM stock_return_discovery_policy WHERE state='"+state+"'");
  q.put("discoveredEnrolled","SELECT count(*) FROM stock_return_discovery_source WHERE disposition='ENROLLED'");q.put("discoveredExisting","SELECT count(*) FROM stock_return_discovery_source WHERE disposition='EXISTING_RETURN'");
  return Collections.unmodifiableMap(q);
 }
}
