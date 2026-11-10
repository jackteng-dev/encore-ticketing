package dev.jackteng.encore.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.encore.admission.AdmissionSettings;
import dev.jackteng.encore.common.ApiErrors.Problem;
import dev.jackteng.encore.customer.CustomerAccess;
import dev.jackteng.encore.customer.CustomerService.Principal;
import dev.jackteng.encore.inventory.RedisTicketLedger.*;
import dev.jackteng.encore.orders.OrderModels.Place;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** M4D2B1 internal boundary, no endpoint/scheduler/checkout flag.
 * Persist identity BEFORE Lua, serialize writers with the PG ticket lock, and
 * persist abandonment BEFORE freezing/releasing Redis. No formal order is made.
 * apply/abandon accept only an internal durable id, never broker-supplied claims.
 */
@Service
public class StockHoldService {
 public record Intent(UUID id,UUID owner,UUID requestId,UUID ticketId,long generation,long sourceRevision,int quantity,long ticketVersion,int totalTwd,OffsetDateTime createdAt,OffsetDateTime deadlineAt) {}
 public record Attempt(Intent intent,String state,String failure) {}
 private record Identity(Intent intent,long sessionVersion,String sessionHash,String fingerprint,String claimFingerprint) {
  Claim claim(){return new Claim(intent.generation(),intent.id(),intent.owner(),intent.quantity(),intent.deadlineAt().toInstant().toEpochMilli(),claimFingerprint);}
 }
 private record Terms(String state,OffsetDateTime start,OffsetDateTime end,long version,int price,int limit) {}
 private final JdbcTemplate db;private final ObjectMapper json;private final RedisTicketLedger ledger;private final TransactionTemplate write;
 public StockHoldService(JdbcTemplate db,ObjectMapper json,StringRedisTemplate redis,AdmissionSettings settings,PlatformTransactionManager transactions){this.db=db;this.json=json;ledger=RedisTicketLedger.epochs(redis,json,settings.namespace());write=new TransactionTemplate(transactions);write.setTimeout(10);}
 private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Stock holds require independent committed phases");}
 private static Problem conflict(String code){return new Problem(409,code,"預扣需要核對原持久命令");}
 private OffsetDateTime now(){return db.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class);}
 private String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private Identity find(UUID id){var rows=db.query("SELECT * FROM stock_hold_intent WHERE id=?",(r,n)->new Identity(new Intent(id,r.getObject("customer_id",UUID.class),r.getObject("request_id",UUID.class),r.getObject("ticket_id",UUID.class),r.getLong("generation"),r.getLong("source_revision"),r.getInt("quantity"),r.getLong("ticket_version"),r.getInt("total_twd"),r.getObject("created_at",OffsetDateTime.class),r.getObject("deadline_at",OffsetDateTime.class)),r.getLong("session_version"),r.getString("session_hash"),r.getString("fingerprint"),r.getString("claim_fingerprint")),id);if(rows.isEmpty())throw new Problem(404,"NOT_FOUND","找不到原預扣命令");return rows.getFirst();}
 private String state(UUID id,boolean lock){return db.queryForObject("SELECT state FROM stock_hold_progress WHERE intent_id=?"+(lock?" FOR UPDATE":""),String.class,id);}
 public Attempt inspect(UUID id){var c=find(id);return new Attempt(c.intent(),state(id,false),null);}
 private Terms lockTicket(UUID ticket){
  var events=db.query("SELECT s.event_id FROM ticket_type t JOIN event_session s ON s.id=t.session_id WHERE t.id=?",(r,n)->r.getObject(1,UUID.class),ticket);
  if(events.isEmpty())throw new Problem(404,"NOT_FOUND","找不到票種");
  db.queryForObject("SELECT id FROM event WHERE id=? FOR SHARE",UUID.class,events.getFirst());
  return db.queryForObject("SELECT e.status,s.sales_start,s.sales_end,t.version,t.price_twd,t.max_per_customer FROM ticket_type t JOIN event_session s ON s.id=t.session_id JOIN event e ON e.id=s.event_id WHERE t.id=? FOR UPDATE OF t /* stock hold guard */",(r,n)->new Terms(r.getString(1),r.getObject(2,OffsetDateTime.class),r.getObject(3,OffsetDateTime.class),r.getLong(4),r.getInt(5),r.getInt(6)),ticket);
 }
 private void lockOwner(UUID owner){db.queryForObject("SELECT id FROM customer_account WHERE id=? FOR UPDATE",UUID.class,owner);}
 private boolean unused(Intent c){return db.queryForObject("SELECT (SELECT count(*) FROM order_command WHERE customer_id=? AND request_id=?)+(SELECT count(*) FROM purchase_command WHERE customer_id=? AND request_id=?)+(SELECT count(*) FROM ticket_order WHERE customer_id=? AND request_id=?)+(SELECT count(*) FROM purchase_request WHERE customer_id=? AND request_id=?)",Integer.class,c.owner(),c.requestId(),c.owner(),c.requestId(),c.owner(),c.requestId(),c.owner(),c.requestId())==0;}
 private boolean ready(Intent c){return db.queryForObject("SELECT count(*) FROM stock_projection WHERE ticket_id=? AND generation=? AND source_revision=? AND state='READY'",Integer.class,c.ticketId(),c.generation(),c.sourceRevision())==1;}
 private Attempt failed(Identity c,String reason){db.update("UPDATE stock_projection SET state='DIRTY' WHERE ticket_id=? AND generation=?",c.intent().ticketId(),c.intent().generation());return new Attempt(c.intent(),state(c.intent().id(),false),reason);}
 private void terms(Terms t,Place p,OffsetDateTime clock){if(!t.state().equals("PUBLISHED")||clock.isBefore(t.start())||!clock.isBefore(t.end()))throw conflict("SALES_CLOSED");if(t.version()!=p.expectedTicketVersion()||t.price()*p.quantity()!=p.expectedTotalTwd())throw conflict("QUOTE_CHANGED");}
 /** Commit a verified immutable intent without any Redis IO. Retry the original
  * owner/UUID/body first, including after a gate change or a later login session.
  */
 public Intent prepare(Principal principal,Place body){
  outside();if(body==null||body.requestId()==null||body.ticketId()==null||body.quantity()<1||body.quantity()>10||body.expectedTicketVersion()<1||body.expectedTotalTwd()<1||body.expectedTotalTwd()>10000000)throw new Problem(400,"INVALID_INPUT","預扣欄位不正確");
  return write.execute(tx->{
   var auth=CustomerAccess.authorize(db,principal);var fp=hash(body);
   var prior=db.query("SELECT id FROM stock_hold_intent WHERE customer_id=? AND request_id=?",(r,n)->r.getObject(1,UUID.class),principal.id(),body.requestId());
   if(!prior.isEmpty()){var c=find(prior.getFirst());if(!c.fingerprint().equals(fp))throw conflict("REQUEST_CONFLICT");return c.intent();}
   var t=lockTicket(body.ticketId());var clock=now();terms(t,body,clock);CustomerAccess.stillAuthenticated(auth,clock);
   var gate=db.queryForMap("SELECT generation,source_revision,state FROM stock_projection WHERE ticket_id=? FOR UPDATE",body.ticketId());if(!gate.get("state").equals("READY"))throw conflict("STOCK_GATE_CLOSED");
   var c=new Intent(UUID.randomUUID(),principal.id(),body.requestId(),body.ticketId(),(Long)gate.get("generation"),(Long)gate.get("source_revision"),body.quantity(),body.expectedTicketVersion(),body.expectedTotalTwd(),clock,(clock.plusSeconds(60).isBefore(t.end())?clock.plusSeconds(60):t.end()).truncatedTo(ChronoUnit.MILLIS));
   if(!unused(c))throw conflict("REQUEST_CONFLICT");
   if(db.queryForObject("SELECT count(*) FROM stock_hold_intent i JOIN stock_hold_progress p ON p.intent_id=i.id WHERE i.customer_id=? AND p.state<>'ABORTED' AND NOT EXISTS(SELECT 1 FROM stock_hold_ack a WHERE a.intent_id=i.id)",Integer.class,principal.id())>=5)throw conflict("REQUEST_LIMIT");
   int reserved=db.queryForObject("SELECT COALESCE(sum(quantity),0) FROM ticket_order WHERE customer_id=? AND ticket_id=? AND status IN ('PENDING_PAYMENT','PAID')",Integer.class,principal.id(),body.ticketId());
   int pending=db.queryForObject("SELECT COALESCE(sum(i.quantity),0) FROM stock_hold_intent i JOIN stock_hold_progress p ON p.intent_id=i.id WHERE i.customer_id=? AND i.ticket_id=? AND p.state<>'ABORTED' AND NOT EXISTS(SELECT 1 FROM stock_hold_ack a WHERE a.intent_id=i.id)",Integer.class,principal.id(),body.ticketId());
   if(reserved+pending+body.quantity()>t.limit())throw conflict("PURCHASE_LIMIT");
   if(db.queryForObject("SELECT count(*) FROM stock_hold_intent WHERE ticket_id=? AND generation=?",Integer.class,body.ticketId(),c.generation())>=4096)throw conflict("LEDGER_FULL");
   var claimFp=hash(java.util.List.of(c,principal.sessionVersion(),principal.sessionHash(),fp));
   db.update("INSERT INTO stock_hold_intent(id,customer_id,request_id,ticket_id,generation,source_revision,quantity,ticket_version,total_twd,session_version,session_hash,fingerprint,claim_fingerprint,created_at,deadline_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",c.id(),c.owner(),c.requestId(),c.ticketId(),c.generation(),c.sourceRevision(),c.quantity(),c.ticketVersion(),c.totalTwd(),principal.sessionVersion(),principal.sessionHash(),fp,claimFp,c.createdAt(),c.deadlineAt());
   db.update("INSERT INTO stock_hold_progress(intent_id) VALUES(?)",c.id());CustomerAccess.stillAuthenticated(auth,now());return c;
  });
 }
 /** Lookup before HOLD: a Lua success followed by PG rollback can be recovered
  * even after deadline/session expiry. Recording evidence is not order authority.
  */
 public Attempt apply(UUID id){
  outside();var c=find(id);
  return write.execute(tx->{
   lockOwner(c.intent().owner());var t=lockTicket(c.intent().ticketId());var state=state(id,true);
   if(state.equals("ABORTING")||state.equals("ABORTED"))return new Attempt(c.intent(),state,"ABANDONED");
   if(db.queryForObject("SELECT count(*) FROM stock_hold_acceptance WHERE intent_id=?",Integer.class,id)>0)return new Attempt(c.intent(),state,"ACCEPTED");
   if(!unused(c.intent()))return failed(c,"PG_COMMAND_CONFLICT");
   try{
    var before=ledger.lookup(c.intent().ticketId(),c.claim());
    if(before.code()==Code.OK&&before.receipt().status()==Status.HELD)return observed(c,state);
    if(before.code()!=Code.MISSING)return failed(c,"REDIS_"+before.code());
    // MISSING with no snapshot means the whole epoch disappeared: never reseed.
    if(before.snapshot()==null||state.equals("HELD"))return failed(c,"REDIS_MISSING");
    if(!ready(c.intent()))return failed(c,"STOCK_GATE_CLOSED");
    var auth=CustomerAccess.authorize(db,new Principal(c.intent().owner(),"",c.sessionVersion(),c.sessionHash()));var clock=now();
    if(!clock.isBefore(c.intent().deadlineAt()))return failed(c,"REQUEST_EXPIRED");
    terms(t,new Place(c.intent().requestId(),c.intent().ticketId(),c.intent().quantity(),c.intent().ticketVersion(),c.intent().totalTwd()),clock);CustomerAccess.stillAuthenticated(auth,clock);
    var result=ledger.hold(c.intent().ticketId(),c.claim());
    if(result.code()==Code.OK&&result.receipt().status()==Status.HELD)return observed(c,state);
    return failed(c,"REDIS_"+result.code());
   }catch(Unavailable e){return failed(c,"REDIS_UNKNOWN");}catch(Problem e){return failed(c,e.code);}
  });
 }
 private Attempt observed(Identity c,String prior){if(prior.equals("PREPARED"))db.update("UPDATE stock_hold_progress SET state='HELD',held_at=clock_timestamp() WHERE intent_id=?",c.intent().id());return new Attempt(c.intent(),"HELD",null);}
 /** Only unused, internal pre-debits can be abandoned in B1. A future formal
  * order/worker bridge needs a different durable COMMIT decision, not this method.
  */
 public Attempt abandon(UUID id){
  outside();var c=find(id);
  write.execute(tx->{lockOwner(c.intent().owner());lockTicket(c.intent().ticketId());var s=state(id,true);
   if(!unused(c.intent()))throw conflict("PG_COMMAND_CONFLICT");
   if(s.equals("ABORTED")||s.equals("ABORTING"))return null;
   db.update("UPDATE stock_projection SET state='DIRTY' WHERE ticket_id=? AND generation=?",c.intent().ticketId(),c.intent().generation());
   db.update("UPDATE stock_hold_progress SET state='ABORTING',abort_at=clock_timestamp(),abort_reason='UNUSED_INTENT_ABANDONED' WHERE intent_id=?",id);return null;});
  return reconcileAbandonment(id);
 }
 /** Expiry observation is rechecked under the SAME owner/ticket/progress locks
  * as acceptance. An accepted intent is superseded, never raw-released. The
  * ABORTING decision commits before the second, independent Redis phase.
  */
 public Attempt recoverUnused(UUID id,Runnable fence){
  outside();var c=find(id);
  var prior=write.execute(tx->{
   lockOwner(c.intent().owner());lockTicket(c.intent().ticketId());var s=state(id,true);fence.run();
   if(db.queryForObject("SELECT count(*) FROM stock_hold_acceptance WHERE intent_id=?",Integer.class,id)>0)return new Attempt(c.intent(),s,"ACCEPTED");
   if(s.equals("ABORTED"))return new Attempt(c.intent(),s,null);
   if(!unused(c.intent()))throw conflict("PG_COMMAND_CONFLICT");
   if(s.equals("ABORTING"))return null; // resume an already committed decision
   if(now().isBefore(c.intent().deadlineAt()))return new Attempt(c.intent(),s,"NOT_DUE");
   db.update("UPDATE stock_projection SET state='DIRTY' WHERE ticket_id=? AND generation=?",c.intent().ticketId(),c.intent().generation());
   db.update("UPDATE stock_hold_progress SET state='ABORTING',abort_at=clock_timestamp(),abort_reason='UNUSED_INTENT_ABANDONED' WHERE intent_id=?",id);
   fence.run();return null;
  });
  return prior!=null?prior:reconcileAbandonment(id,fence);
 }
 /** Persisted ABORTING + owner/ticket locks fence every official late writer.
  * CLOSE freezes Lua before lookup/release, including a queued timed-out HOLD.
  * Unknown/corrupt/COMMITTED stays ABORTING and blocks rebuilding. Never INCR.
  */
 public Attempt reconcileAbandonment(UUID id){
  return reconcileAbandonment(id,()->{});
 }
 private Attempt reconcileAbandonment(UUID id,Runnable fence){return reconcileReviewed(id,()->{},fence,r->{});}
 /** Staff guard precedes domain locks; checkpoint commits with the original evidence. */
 public Attempt reconcileReviewed(UUID id,Runnable guard,Runnable fence,java.util.function.Consumer<Attempt> committed){
  outside();var c=find(id);
  return write.execute(tx->{guard.run();var result=reconcileAbandonmentLocked(c,fence);committed.accept(result);return result;});
 }
 private Attempt reconcileAbandonmentLocked(Identity c,Runnable fence){
  UUID id=c.intent().id();lockOwner(c.intent().owner());lockTicket(c.intent().ticketId());var s=state(id,true);fence.run();
   if(s.equals("ABORTED"))return new Attempt(c.intent(),s,null);
   if(!s.equals("ABORTING"))throw conflict("ABANDONMENT_NOT_DECIDED");
   if(!unused(c.intent()))return failed(c,"PG_COMMAND_CONFLICT");
   try{
    var frozen=ledger.close(c.intent().ticketId(),c.intent().generation());
    if(frozen.code()==Code.MISSING){fence.run();return resolved(c,"KEY_MISSING");}
    if(frozen.code()!=Code.OK||frozen.snapshot().phase()!=Phase.FROZEN)return failed(c,"REDIS_"+frozen.code());
    var receipt=ledger.lookup(c.intent().ticketId(),c.claim());
    if(receipt.code()==Code.MISSING){fence.run();return resolved(c,"ABSENT");}
    if(receipt.code()!=Code.OK)return failed(c,"REDIS_"+receipt.code());
    if(receipt.receipt().status()==Status.COMMITTED)return failed(c,"REDIS_COMMITTED_REQUIRES_RECONCILIATION");
    var released=ledger.release(c.intent().ticketId(),c.claim());
    if(released.code()!=Code.OK||released.receipt().status()!=Status.RELEASED)return failed(c,"REDIS_"+released.code());
    fence.run();return resolved(c,"RELEASED");
   }catch(Unavailable e){return failed(c,"REDIS_UNKNOWN");}
 }
 private Attempt resolved(Identity c,String evidence){db.update("UPDATE stock_hold_progress SET state='ABORTED',resolved_at=clock_timestamp(),resolution=? WHERE intent_id=?",evidence,c.intent().id());return new Attempt(c.intent(),"ABORTED",null);}
}
