package dev.elpsy.ticket.orders;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.slf4j.LoggerFactory;
@Configuration
@org.springframework.context.annotation.Profile("!api")
public class OrderExpiry {
 private final JdbcTemplate db;private final OrderService service;private final boolean enabled;
 public OrderExpiry(JdbcTemplate db,OrderService service,@Value("${ticket.expiry.enabled}") boolean enabled){this.db=db;this.service=service;this.enabled=enabled;}
 @Scheduled(fixedDelayString="${ticket.expiry.delay-ms}",initialDelay=5000) public void sweep(){
  if(!enabled)return;
  for(UUID id:db.query("SELECT id FROM ticket_order WHERE status='PENDING_PAYMENT' AND expires_at<=clock_timestamp() ORDER BY expires_at,id LIMIT 100",(r,n)->r.getObject(1,UUID.class))){
   try{service.expire(id);}catch(RuntimeException e){LoggerFactory.getLogger(OrderExpiry.class).error("Reservation expiry failed; will retry on next sweep ({})",e.getClass().getSimpleName());}
  }
 }
}
