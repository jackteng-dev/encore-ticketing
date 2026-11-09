package dev.elpsy.ticket.orders;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.LoggerFactory;
@Component
@org.springframework.context.annotation.Profile("!api")
public class PurchaseExpiry {
 private final JdbcTemplate db;private final PurchaseService service;private final boolean enabled;
 public PurchaseExpiry(JdbcTemplate db,PurchaseService service,@Value("${ticket.expiry.enabled}") boolean enabled){this.db=db;this.service=service;this.enabled=enabled;}
 @Scheduled(fixedDelayString="${ticket.expiry.delay-ms}",initialDelay=5000) public void sweep(){if(!enabled)return;for(UUID id:db.query("SELECT id FROM purchase_request WHERE status='PROCESSING' AND deadline_at<=clock_timestamp() ORDER BY deadline_at,id LIMIT 100",(r,n)->r.getObject(1,UUID.class))){try{service.expire(id);}catch(RuntimeException e){LoggerFactory.getLogger(PurchaseExpiry.class).error("Purchase expiry failed; will retry ({})",e.getClass().getSimpleName());}}}
}
