package dev.elpsy.ticket.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.elpsy.ticket.admission.AdmissionSettings;
import dev.elpsy.ticket.common.ApiErrors.Problem;
import dev.elpsy.ticket.inventory.RedisTicketLedger.*;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** M4D2A maintenance only: durable epochs and stable empty projections.
 * Not an admission/checkout gate; no holds are issued here. Inventory mutations
 * invalidate the gate in PG. A later durable claim coordinator must handle orphans.
 */
@Service
public class StockProjectionService {
 public record Gate(UUID ticketId,long sourceRevision,long generation,String state,UUID rebuildRequestId,Long preparedRevision,Integer preparedAvailable) {}
 public record Rebuild(UUID requestId,UUID ticketId,long generation,long sourceRevision,int available) {}
 public record Preparation(Rebuild command,String state,String failure) {}
 private record Stock(int capacity,int held,int sold,long version) {int available(){return capacity-held-sold;}}
 private final JdbcTemplate db;private final RedisTicketLedger ledger;private final TransactionTemplate write;
 public StockProjectionService(JdbcTemplate db,StringRedisTemplate redis,ObjectMapper json,AdmissionSettings settings,PlatformTransactionManager transactions){
  this.db=db;ledger=RedisTicketLedger.epochs(redis,json,settings.namespace());write=new TransactionTemplate(transactions);write.setTimeout(10);
 }
 private static Problem conflict(String code){return new Problem(409,code,"庫存投影需要核對原重建命令");}
 private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Projection maintenance requires independent committed phases");}
 public Gate inspect(UUID ticket){return gate(ticket,false);}
 private Stock lock(UUID ticket){
  var rows=db.query("SELECT capacity,held,sold,version FROM ticket_type WHERE id=? FOR UPDATE /* stock projection guard */",(r,n)->new Stock(r.getInt(1),r.getInt(2),r.getInt(3),r.getLong(4)),ticket);
  if(rows.isEmpty())throw new Problem(404,"NOT_FOUND","找不到票種");return rows.getFirst();
 }
 private Gate gate(UUID ticket,boolean locked){
  var rows=db.query("SELECT * FROM stock_projection WHERE ticket_id=?"+(locked?" FOR UPDATE":""),(r,n)->new Gate(ticket,r.getLong("source_revision"),r.getLong("generation"),r.getString("state"),r.getObject("rebuild_request_id",UUID.class),r.getObject("prepared_revision",Long.class),r.getObject("prepared_available",Integer.class)),ticket);
  if(rows.isEmpty())throw new IllegalStateException("Missing durable stock projection");return rows.getFirst();
 }
 private Rebuild find(UUID request){var rows=db.query("SELECT * FROM stock_rebuild_command WHERE request_id=?",(r,n)->new Rebuild(request,r.getObject("ticket_id",UUID.class),r.getLong("generation"),r.getLong("source_revision"),r.getInt("available")),request);return rows.isEmpty()?null:rows.getFirst();}
 /** Commits a CLOSED epoch before any Redis operation. Original UUID never allocates again. */
 public Rebuild begin(UUID ticket,UUID request,long expectedRevision){
  return begin(ticket,request,expectedRevision,()->{},c->{});
 }
 /** The staff operation guard and audit participate in this exact PG phase. */
 public Rebuild begin(UUID ticket,UUID request,long expectedRevision,Runnable guard,java.util.function.Consumer<Rebuild> committed){
  outside();if(ticket==null||request==null||expectedRevision<1)throw new IllegalArgumentException("Rebuild identity/revision required");
  try{return write.execute(tx->{
   guard.run();var stock=lock(ticket);var gate=gate(ticket,true);var prior=find(request);
   if(prior!=null){if(!prior.ticketId().equals(ticket)||prior.sourceRevision()!=expectedRevision)throw conflict("REBUILD_CONFLICT");return prior;}
   if(gate.sourceRevision()!=expectedRevision)throw conflict("STOCK_CHANGED");
   if(db.queryForObject("SELECT count(*) FROM stock_hold_intent i JOIN stock_hold_progress p ON p.intent_id=i.id WHERE i.ticket_id=? AND p.state<>'ABORTED' AND NOT EXISTS(SELECT 1 FROM stock_hold_ack a WHERE a.intent_id=i.id)",Integer.class,ticket)>0)throw conflict("ACTIVE_STOCK_INTENTS");
   if(gate.generation()>=1_000_000_000L)throw conflict("GENERATION_EXHAUSTED");
   long next=gate.generation()+1;
   db.update("INSERT INTO stock_rebuild_command(request_id,ticket_id,generation,source_revision,available) VALUES(?,?,?,?,?)",request,ticket,next,expectedRevision,stock.available());
   db.update("UPDATE stock_projection SET generation=?,state='CLOSED',rebuild_request_id=?,prepared_revision=NULL,prepared_available=NULL WHERE ticket_id=?",next,request,ticket);
   var result=new Rebuild(request,ticket,next,expectedRevision,stock.available());committed.accept(result);return result;
  });}catch(org.springframework.dao.DuplicateKeyException e){
   // A globally identical maintenance UUID can race on different ticket locks.
   // The loser transaction has rolled back; only replay the immutable original command.
   var prior=find(request);if(prior==null)throw e;
   if(!prior.ticketId().equals(ticket)||prior.sourceRevision()!=expectedRevision)throw conflict("REBUILD_CONFLICT");return prior;
  }
 }
 private boolean pristine(Result r,Rebuild c){
  var s=r.snapshot();return r.code()==Code.OK&&s!=null&&s.generation()==c.generation()&&s.baseline()==c.available()&&s.available()==c.available()&&s.held()==0&&s.committed()==0&&s.entries()==0&&s.limit()==4096;
 }
 private Preparation failed(Rebuild c,String state,String reason){db.update("UPDATE stock_projection SET state=? WHERE ticket_id=?",state,c.ticketId());return new Preparation(c,state,reason);}
 /** Under the same ticket row lock as every PG stock update, prepare Redis then PG READY.
  * Rollback after Redis OPEN leaves the separately committed PG CLOSED epoch.
  */
 public Preparation complete(UUID request){
  return complete(request,()->{},p->{});
 }
 public Preparation complete(UUID request,Runnable guard,java.util.function.Consumer<Preparation> committed){
  outside();var command=find(request);if(command==null)throw new Problem(404,"NOT_FOUND","找不到原重建命令");
  return write.execute(tx->{guard.run();var result=completeLocked(command);committed.accept(result);return result;});
 }
 private Preparation completeLocked(Rebuild command){
   var stock=lock(command.ticketId());var gate=gate(command.ticketId(),true);
   if(gate.generation()!=command.generation()||!command.requestId().equals(gate.rebuildRequestId()))throw conflict("GENERATION_FENCED");
   if(db.queryForObject("SELECT count(*) FROM stock_hold_intent WHERE ticket_id=? AND generation=?",Integer.class,command.ticketId(),command.generation())>0)throw conflict("STOCK_INTENTS_PRESENT");
   if(gate.sourceRevision()!=command.sourceRevision()||stock.available()!=command.available()||gate.state().equals("DIRTY"))throw conflict("STOCK_CHANGED");
   try{
    var before=ledger.snapshot(command.ticketId(),command.generation());
    if(before.code()==Code.MISSING){
     if(gate.state().equals("READY"))return failed(command,"DIRTY","REDIS_MISSING");
     before=ledger.initialize(command.ticketId(),command.generation(),command.available(),4096);
    }
    if(!pristine(before,command)||before.snapshot().phase()==Phase.FROZEN)return failed(command,"DIRTY","REDIS_RECONCILIATION_REQUIRED");
    if(gate.state().equals("READY")&&before.snapshot().phase()!=Phase.OPEN)return failed(command,"DIRTY","REDIS_GATE_CLOSED");
    var opened=ledger.open(command.ticketId(),command.generation());
    if(!pristine(opened,command)||opened.snapshot().phase()!=Phase.OPEN)return failed(command,"DIRTY","REDIS_RECONCILIATION_REQUIRED");
    db.update("UPDATE stock_projection SET state='READY',prepared_revision=?,prepared_available=? WHERE ticket_id=?",command.sourceRevision(),command.available(),command.ticketId());
    return new Preparation(command,"READY",null);
   }catch(Unavailable e){return failed(command,gate.state().equals("READY")?"DIRTY":"CLOSED","REDIS_UNKNOWN");}
 }
}
