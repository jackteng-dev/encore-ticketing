package dev.jackteng.encore.inventory;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Short PG transactions only. Never hold a claim transaction around Redis IO. */
@Service
public class StockRecoveryStore {
 public record Claim(UUID intentId,String kind,UUID token,int attempt,int maxAttempts) {}
 public static class LostLease extends RuntimeException { public LostLease(){super("Stock recovery lease no longer owned");} }
 private final JdbcTemplate db;
 public StockRecoveryStore(JdbcTemplate db){this.db=db;}
 @Transactional public Optional<Claim> claim(int seconds){
  if(seconds<1||seconds>120)throw new IllegalArgumentException("Recovery lease must be 1..120 seconds");
  // Crashes consume the same finite budget as failed IO; exhausted expired
  // leases need review, not a seventh attempt or indefinite invisible limbo.
  db.update("UPDATE stock_recovery_job SET state='REVIEW',completed_at=clock_timestamp(),last_code='LEASE_EXHAUSTED',lease_token=NULL,lease_until=NULL WHERE state='LEASED' AND lease_until<=clock_timestamp() AND attempts=max_attempts");
  UUID token=UUID.randomUUID();
  var rows=db.query("""
   WITH candidate AS (SELECT intent_id,kind FROM stock_recovery_job
    WHERE (next_attempt_at<=clock_timestamp() OR (kind='UNUSED' AND state='WAITING' AND attempts=0
      AND EXISTS(SELECT 1 FROM stock_hold_progress p WHERE p.intent_id=stock_recovery_job.intent_id AND p.state='ABORTING')))
     AND attempts<max_attempts
     AND (state='WAITING' OR (state='LEASED' AND lease_until<=clock_timestamp()))
    ORDER BY next_attempt_at,intent_id,kind FOR UPDATE SKIP LOCKED LIMIT 1),
   claimed AS (UPDATE stock_recovery_job j SET state='LEASED',lease_token=?,
    lease_until=clock_timestamp()+make_interval(secs=>?),attempts=attempts+1
    FROM candidate c WHERE j.intent_id=c.intent_id AND j.kind=c.kind RETURNING j.*),
   audit AS (INSERT INTO stock_recovery_claim(intent_id,kind,attempt,token,claimed_at,lease_until)
    SELECT intent_id,kind,attempts,lease_token,clock_timestamp(),lease_until FROM claimed RETURNING intent_id)
   SELECT c.intent_id,c.kind,c.attempts,c.max_attempts FROM claimed c JOIN audit a ON a.intent_id=c.intent_id
   """,(r,n)->new Claim(r.getObject(1,UUID.class),r.getString(2),token,r.getInt(3),r.getInt(4)),token,seconds);
  return rows.stream().findFirst();
 }
 /** Called inside EACH independent domain phase after owner/ticket locks. A
  * second check after Lua lets a timed-out attempt roll back its PG evidence;
  * the original immutable decision makes a later Redis replay safe.
  */
 public void fence(Claim c){
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Lease fencing requires a domain transaction");
  if(db.query("SELECT intent_id FROM stock_recovery_job WHERE intent_id=? AND kind=? AND state='LEASED' AND lease_token=? AND attempts=? AND lease_until>clock_timestamp() FOR UPDATE",(r,n)->r.getObject(1,UUID.class),c.intentId(),c.kind(),c.token(),c.attempt()).isEmpty())throw new LostLease();
 }
 @Transactional public boolean complete(Claim c,String code){return finish(c,"DONE",code,0);}
 @Transactional public boolean failed(Claim c,String code,boolean permanent){
  int delay=Math.min(30,1<<Math.min(5,c.attempt()-1));
  return finish(c,permanent||c.attempt()>=c.maxAttempts()?"REVIEW":"WAITING",code,delay);
 }
 private boolean finish(Claim c,String state,String code,int delay){
  if(code==null||!code.matches("[A-Z][A-Z0-9_]{0,47}"))throw new IllegalArgumentException("Only safe recovery outcome codes are persisted");
  return db.update("""
   UPDATE stock_recovery_job SET state=?,last_code=?,lease_token=NULL,lease_until=NULL,
    next_attempt_at=clock_timestamp()+make_interval(secs=>?),
    completed_at=CASE WHEN ? IN ('DONE','REVIEW') THEN clock_timestamp() ELSE NULL END
   WHERE intent_id=? AND kind=? AND state='LEASED' AND lease_token=? AND attempts=? AND lease_until>clock_timestamp()
   """,state,code,delay,state,c.intentId(),c.kind(),c.token(),c.attempt())==1;
 }
}
