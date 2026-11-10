package dev.jackteng.encore.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.encore.admission.AdmissionSettings;
import dev.jackteng.encore.common.ApiErrors.Problem;
import dev.jackteng.encore.inventory.RedisTicketLedger.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal recovery, deliberately no endpoint. Formal PG decisions
 * are committed by the purchase result trigger BEFORE any Redis COMMIT/RELEASE.
 * A separately committed DIRTY gate fences new writers before reconciliation.
 */
@Service
public class StockSettlementService {
 public record Outcome(UUID intentId,String target,String state,String resolution,String failure) {}
 private record Decision(UUID intentId,UUID owner,UUID request,UUID ticket,long generation,int quantity,int total,OffsetDateTime deadline,String fingerprint,UUID purchase,String target,String status,UUID order,String reason) {
  Claim claim(){return new Claim(generation,intentId,owner,quantity,deadline.toInstant().toEpochMilli(),fingerprint);}
 }
 private final JdbcTemplate db;private final RedisTicketLedger ledger;private final TransactionTemplate write;
 public StockSettlementService(JdbcTemplate db,StringRedisTemplate redis,ObjectMapper json,AdmissionSettings settings,PlatformTransactionManager transactions){this.db=db;ledger=RedisTicketLedger.epochs(redis,json,settings.namespace());write=new TransactionTemplate(transactions);write.setTimeout(10);}
 private static Problem conflict(String code){return new Problem(409,code,"正式預扣決定需要核對原命令");}
 private Decision find(UUID id){var rows=db.query("SELECT i.*,a.purchase_id,d.target,d.purchase_status,d.order_id,d.reason FROM stock_hold_intent i JOIN stock_hold_acceptance a ON a.intent_id=i.id JOIN stock_hold_decision d ON d.intent_id=i.id WHERE i.id=?",(r,n)->new Decision(id,r.getObject("customer_id",UUID.class),r.getObject("request_id",UUID.class),r.getObject("ticket_id",UUID.class),r.getLong("generation"),r.getInt("quantity"),r.getInt("total_twd"),r.getObject("deadline_at",OffsetDateTime.class),r.getString("claim_fingerprint"),r.getObject("purchase_id",UUID.class),r.getString("target"),r.getString("purchase_status"),r.getObject("order_id",UUID.class),r.getString("reason")),id);if(rows.isEmpty())throw conflict("STOCK_DECISION_NOT_READY");return rows.getFirst();}
 private void lock(Decision d){db.queryForObject("SELECT id FROM customer_account WHERE id=? FOR UPDATE",UUID.class,d.owner());db.queryForObject("SELECT id FROM ticket_type WHERE id=? FOR UPDATE /* stock settlement guard */",UUID.class,d.ticket());db.queryForObject("SELECT id FROM purchase_request WHERE id=? FOR UPDATE",UUID.class,d.purchase());}
 private void verify(Decision d){
  if(db.queryForObject("SELECT count(*) FROM purchase_request WHERE id=? AND customer_id=? AND request_id=? AND ticket_id=? AND quantity=? AND total_twd=? AND status=? AND order_id IS NOT DISTINCT FROM ?::uuid AND COALESCE(reason,'FORMAL_RESERVATION_COMMITTED')=?",Integer.class,d.purchase(),d.owner(),d.request(),d.ticket(),d.quantity(),d.total(),d.status(),d.order(),d.reason())!=1)throw conflict("STOCK_DECISION_CONFLICT");
  if(d.target().equals("COMMIT")){
   if(db.queryForObject("SELECT count(*) FROM ticket_order o JOIN order_inventory_event e ON e.order_id=o.id AND e.phase='HOLD' WHERE o.id=? AND o.customer_id=? AND o.request_id=? AND o.ticket_id=? AND o.quantity=? AND o.total_twd=? AND e.ticket_id=o.ticket_id AND e.delta=o.quantity",Integer.class,d.order(),d.owner(),d.request(),d.ticket(),d.quantity(),d.total())!=1)throw conflict("STOCK_DECISION_CONFLICT");
  }else if(db.queryForObject("SELECT count(*) FROM ticket_order WHERE customer_id=? AND request_id=?",Integer.class,d.owner(),d.request())>0)throw conflict("STOCK_DECISION_CONFLICT");
 }
 private Outcome acknowledged(Decision d){var rows=db.query("SELECT resolution FROM stock_hold_ack WHERE intent_id=?",(r,n)->r.getString(1),d.intentId());return rows.isEmpty()?null:new Outcome(d.intentId(),d.target(),"ACKNOWLEDGED",rows.getFirst(),null);}
 private void generation(Decision d){if(db.queryForObject("SELECT generation FROM stock_projection WHERE ticket_id=?",Long.class,d.ticket())!=d.generation())throw conflict("GENERATION_FENCED");}
 private Outcome pending(Decision d,String reason){return new Outcome(d.intentId(),d.target(),"PENDING",null,reason);}
 private Outcome ack(Decision d,String evidence){db.update("INSERT INTO stock_hold_ack(intent_id,resolution) VALUES(?,?)",d.intentId(),evidence);return new Outcome(d.intentId(),d.target(),"ACKNOWLEDGED",evidence,null);}
 public Outcome reconcile(UUID id){
  return reconcile(id,()->{});
 }
 public Outcome reconcile(UUID id,Runnable fence){return reconcileReviewed(id,()->{},fence,r->{});}
 /** The actor/operation guard precedes original domain locks in each independent phase. */
 public Outcome reconcileReviewed(UUID id,Runnable guard,Runnable fence,java.util.function.Consumer<Outcome> committed){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Settlement requires independent committed phases");
  var d=find(id);
  var prior=write.execute(tx->{guard.run();lock(d);fence.run();verify(d);var known=acknowledged(d);if(known!=null){committed.accept(known);return known;}generation(d);db.update("UPDATE stock_projection SET state='DIRTY' WHERE ticket_id=?",d.ticket());fence.run();committed.accept(new Outcome(id,d.target(),"FENCED",null,null));return null;});
  if(prior!=null)return prior;
  return write.execute(tx->{guard.run();var result=reconcileLocked(d,fence);committed.accept(result);return result;});
 }
 private Outcome reconcileLocked(Decision d,Runnable fence){
   lock(d);fence.run();verify(d);var known=acknowledged(d);if(known!=null)return known;generation(d);
   try{
    var closed=ledger.close(d.ticket(),d.generation());
    if(closed.code()==Code.MISSING){fence.run();return ack(d,"KEY_MISSING");}
    if(closed.code()!=Code.OK||closed.snapshot().phase()!=Phase.FROZEN)return pending(d,"REDIS_"+closed.code());
    var before=ledger.lookup(d.ticket(),d.claim());
    if(before.code()==Code.MISSING){fence.run();return ack(d,"ABSENT");}
    if(before.code()!=Code.OK)return pending(d,"REDIS_"+before.code());
    Status expected=d.target().equals("COMMIT")?Status.COMMITTED:Status.RELEASED;
    if(before.receipt().status()!=Status.HELD&&before.receipt().status()!=expected)return pending(d,"REDIS_DECISION_CONFLICT");
    var resolved=d.target().equals("COMMIT")?ledger.commit(d.ticket(),d.claim()):ledger.release(d.ticket(),d.claim());
    if(resolved.code()!=Code.OK||resolved.receipt().status()!=expected)return pending(d,"REDIS_"+resolved.code());
    fence.run();return ack(d,expected.name());
   }catch(Unavailable e){return pending(d,"REDIS_UNKNOWN");}
 }
}
