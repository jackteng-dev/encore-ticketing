package dev.elpsy.ticket.delivery;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class OutboxStore {
 public record Claim(UUID eventId,UUID purchaseId,UUID token,long attempts) {}
 private final JdbcTemplate db;
 public OutboxStore(JdbcTemplate db){this.db=db;}
 @Transactional public Optional<Claim> claim(int seconds){
  UUID token=UUID.randomUUID();
  var rows=db.query("""
   WITH candidate AS (SELECT event_id FROM outbox_publication
    WHERE published_at IS NULL AND next_attempt_at<=clock_timestamp()
     AND (lease_until IS NULL OR lease_until<=clock_timestamp())
    ORDER BY next_attempt_at,event_id FOR UPDATE SKIP LOCKED LIMIT 1),
   claimed AS (UPDATE outbox_publication p SET lease_token=?,
    lease_until=clock_timestamp()+make_interval(secs=>?),attempts=attempts+1
    FROM candidate c WHERE p.event_id=c.event_id RETURNING p.event_id,p.attempts)
   SELECT c.event_id,o.purchase_id,c.attempts FROM claimed c JOIN purchase_outbox o ON o.id=c.event_id
   """,(r,n)->new Claim(r.getObject(1,UUID.class),r.getObject(2,UUID.class),token,r.getLong(3)),token,seconds);
  return rows.stream().findFirst();
 }
 @Transactional public boolean confirmed(Claim claim){return db.update("UPDATE outbox_publication SET published_at=clock_timestamp(),lease_token=NULL,lease_until=NULL,last_error=NULL WHERE event_id=? AND lease_token=? AND lease_until>clock_timestamp() AND published_at IS NULL",claim.eventId(),claim.token())==1;}
 @Transactional public boolean failed(Claim claim,String code){
  if(!Set.of("RETURNED","NACK","UNKNOWN").contains(code))throw new IllegalArgumentException("Unknown publication outcome");
  int delay=(int)Math.min(30,1L<<Math.min(5,Math.max(0,claim.attempts()-1)));
  return db.update("UPDATE outbox_publication SET lease_token=NULL,lease_until=NULL,last_error=?,next_attempt_at=clock_timestamp()+make_interval(secs=>?) WHERE event_id=? AND lease_token=? AND lease_until>clock_timestamp() AND published_at IS NULL",code,delay,claim.eventId(),claim.token())==1;
 }
}
