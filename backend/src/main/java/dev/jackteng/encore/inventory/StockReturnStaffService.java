package dev.jackteng.encore.inventory;

import dev.jackteng.encore.common.ApiErrors.Problem;
import dev.jackteng.encore.catalog.Models.Page;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

/** Explicit actor-owned return coordinator. Permissions and staff audit share each domain PG phase. */
@Service
public class StockReturnStaffService {
 public record Begin(@NotNull UUID requestId,@Min(1) long expectedReleaseRevision) {}
 public record Settings(boolean enabled) {}
 public record Candidate(UUID orderId,UUID ticketId,String orderStatus,int quantity,long releaseRevision,
  long sourceGeneration,String operationState,UUID ownRequestId,boolean canBegin,String reason) {}
 public record Receipt(UUID requestId,UUID orderId,UUID ticketId,long expectedReleaseRevision,String state,
  Long generation,long version,String failure,boolean canResume,OffsetDateTime updatedAt,
  long currentSourceRevision,long currentGeneration,String currentState) {}
 public record Audit(long version,String state,Long generation,String failure,OffsetDateTime createdAt) {}
 private record Actor(UUID id,boolean manager) {}
 private static class Finished extends RuntimeException {}
 private final JdbcTemplate db; private final StockReturnService returns; private final StockProjectionService projections;
 private final TransactionTemplate write; private final boolean enabled;
 public StockReturnStaffService(JdbcTemplate db,StockReturnService returns,StockProjectionService projections,
  PlatformTransactionManager transactions,@Value("${ticket.stock-return.enabled:false}") boolean enabled){
  this.db=db;this.returns=returns;this.projections=projections;this.enabled=enabled;write=new TransactionTemplate(transactions);write.setTimeout(10);
 }
 private static Problem problem(int status,String code){return new Problem(status,code,"請保留原返還操作，核對來源與目前票額狀態");}
 private Actor authorize(String name,boolean manager){var a=db.query("SELECT id,role FROM staff_account WHERE username=? AND enabled FOR SHARE",(r,n)->new Actor(r.getObject(1,UUID.class),r.getString(2).equals("MANAGER")),name);if(a.isEmpty()||manager&&!a.getFirst().manager())throw problem(403,"FORBIDDEN");return a.getFirst();}
 private static boolean active(String state){return Set.of("REGISTERED","CLOSED").contains(state);}
 private static void page(int page,int size){if(page<1||page>100000||size<1||size>20)throw problem(400,"INVALID_INPUT");}
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Settings settings(String actor){authorize(actor,false);return new Settings(enabled);}
 private Map<String,Object> command(UUID id,boolean locked){var rows=db.queryForList("SELECT c.*,p.state,p.generation,p.version,p.failure,p.updated_at FROM stock_return_staff_command c JOIN stock_return_staff_progress p USING(request_id) WHERE c.request_id=?"+(locked?" FOR UPDATE OF p":""),id);return rows.isEmpty()?null:rows.getFirst();}
 private Map<String,Object> owned(String actor,UUID id,boolean manager,boolean locked){var a=authorize(actor,manager);var c=command(id,locked);if(c==null||!c.get("actor_id").equals(a.id()))throw problem(404,"NOT_FOUND");return c;}
 private Receipt receipt(Map<String,Object> c,boolean manager){var g=projections.inspect((UUID)c.get("ticket_id"));return new Receipt((UUID)c.get("request_id"),(UUID)c.get("order_id"),(UUID)c.get("ticket_id"),(Long)c.get("release_revision"),(String)c.get("state"),(Long)c.get("generation"),(Long)c.get("version"),(String)c.get("failure"),enabled&&manager&&active((String)c.get("state")),((java.sql.Timestamp)c.get("updated_at")).toInstant().atOffset(java.time.ZoneOffset.UTC),g.sourceRevision(),g.generation(),g.state());}
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Receipt progress(String actor,UUID request){return receipt(owned(actor,request,false,false),authorize(actor,false).manager());}
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Page<Audit> audit(String actor,UUID request,int p,int size){page(p,size);owned(actor,request,false,false);return new Page<>(db.query("SELECT * FROM stock_return_staff_audit WHERE request_id=? ORDER BY version DESC LIMIT ? OFFSET ?",(r,n)->new Audit(r.getLong("version"),r.getString("state"),r.getObject("generation",Long.class),r.getString("failure"),r.getObject("created_at",OffsetDateTime.class)),request,size,(p-1)*size),db.queryForObject("SELECT count(*) FROM stock_return_staff_audit WHERE request_id=?",Long.class,request),p,size);}
 private static final String CANDIDATES=" FROM stock_revision_origin o JOIN stock_revision_context x USING(ticket_id,to_revision) JOIN ticket_order t ON t.id=o.order_id JOIN stock_projection g ON g.ticket_id=o.ticket_id JOIN stock_hold_decision d ON d.intent_id=o.intent_id JOIN stock_hold_ack a ON a.intent_id=d.intent_id LEFT JOIN stock_return_staff_command c ON c.order_id=t.id LEFT JOIN stock_return_staff_progress p ON p.request_id=c.request_id LEFT JOIN stock_return_command r ON r.order_id=t.id WHERE o.kind='ORDER_RELEASE' AND o.ticket_id=? AND d.target='COMMIT' AND d.order_id=t.id AND a.resolution='COMMITTED'";
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Page<Candidate> candidates(String actor,UUID ticket,int p,int size){page(p,size);var who=authorize(actor,false);if(db.queryForObject("SELECT count(*) FROM stock_projection WHERE ticket_id=?",Integer.class,ticket)!=1)throw problem(404,"NOT_FOUND");projections.inspect(ticket);
  var rows=db.query("SELECT t.id,o.ticket_id,t.status,t.quantity,o.to_revision,x.generation source_generation,g.source_revision,g.generation current_generation,c.actor_id,c.request_id,p.state,r.request_id internal_request,(SELECT count(*) FROM stock_hold_intent i JOIN stock_hold_progress z ON z.intent_id=i.id WHERE i.ticket_id=o.ticket_id AND z.state<>'ABORTED' AND NOT EXISTS(SELECT 1 FROM stock_hold_ack h WHERE h.intent_id=i.id)) unresolved"+CANDIDATES+" ORDER BY o.to_revision DESC,t.id LIMIT ? OFFSET ?",(r,n)->{
   String reason=r.getObject("request_id")!=null?"RETURN_REGISTERED":r.getObject("internal_request")!=null?"INTERNAL_RETURN_EXISTS":r.getLong("source_revision")!=r.getLong("to_revision")?"STOCK_CHANGED":r.getLong("current_generation")!=r.getLong("source_generation")?"RETURN_ALREADY_INCLUDED":r.getLong("unresolved")>0?"ACTIVE_STOCK_INTENTS":!enabled?"RETURN_DISABLED":!who.manager()?"READ_ONLY":null;
   return new Candidate(r.getObject("id",UUID.class),ticket,r.getString("status"),r.getInt("quantity"),r.getLong("to_revision"),r.getLong("source_generation"),r.getString("state"),who.id().equals(r.getObject("actor_id"))?r.getObject("request_id",UUID.class):null,reason==null,reason);
  },ticket,size,(p-1)*size);
  return new Page<>(rows,db.queryForObject("SELECT count(*)"+CANDIDATES,Long.class,ticket),p,size);
 }
 private Receipt registered(String actor,UUID ticket,UUID order,Begin b,Runnable fence){return write.execute(tx->{var a=authorize(actor,true);db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"staff-stock-return:"+b.requestId());var prior=command(b.requestId(),true);fence.run();if(prior!=null){if(!prior.get("actor_id").equals(a.id())||!prior.get("ticket_id").equals(ticket)||!prior.get("order_id").equals(order)||!prior.get("release_revision").equals(b.expectedReleaseRevision()))throw problem(409,"REQUEST_CONFLICT");return receipt(prior,true);}if(!enabled)throw problem(409,"RETURN_DISABLED");
  if(db.queryForObject("SELECT count(*) FROM stock_rebuild_command WHERE request_id=?",Integer.class,b.requestId())>0||db.queryForObject("SELECT count(*) FROM stock_return_command WHERE order_id=?",Integer.class,order)>0||db.queryForObject("SELECT count(*) FROM stock_return_staff_command WHERE order_id=?",Integer.class,order)>0)throw problem(409,"REQUEST_CONFLICT");
  if(db.queryForObject("SELECT count(*) FROM ticket_order WHERE id=? AND ticket_id=?",Integer.class,order,ticket)!=1)throw problem(404,"NOT_FOUND");
  if(db.queryForObject("SELECT count(*) FROM stock_revision_origin WHERE ticket_id=? AND order_id=? AND to_revision=? AND kind='ORDER_RELEASE' AND intent_id IS NOT NULL",Integer.class,ticket,order,b.expectedReleaseRevision())!=1)throw problem(409,"RETURN_ORIGIN_REQUIRED");
  db.update("INSERT INTO stock_return_staff_command(request_id,actor_id,ticket_id,order_id,release_revision) VALUES(?,?,?,?,?)",b.requestId(),a.id(),ticket,order,b.expectedReleaseRevision());db.update("INSERT INTO stock_return_staff_progress(request_id,state,version) VALUES(?,'REGISTERED',1)",b.requestId());return receipt(command(b.requestId(),false),true);
 });}
 private void guard(String actor,UUID request,Runnable fence){var c=owned(actor,request,true,true);if(!active((String)c.get("state")))throw new Finished();if(!enabled)throw problem(409,"RETURN_DISABLED");fence.run();}
 private void checkpoint(UUID request,String state,Long generation,String failure){db.update("UPDATE stock_return_staff_progress SET state=?,generation=?,failure=?,version=version+1 WHERE request_id=?",state,generation,failure,request);}
 private void blocked(String actor,UUID request,String code,Runnable fence){write.executeWithoutResult(tx->{var c=owned(actor,request,true,true);fence.run();if(active((String)c.get("state")))checkpoint(request,"BLOCKED",(Long)c.get("generation"),code);});}
 public Receipt submit(String actor,UUID ticket,UUID order,Begin b){return submit(actor,ticket,order,b,()->{});}
 /** Only the atomic PG discovery transaction may register; no Redis or projection completion. */
 public Receipt registerForDiscovery(String actor,UUID ticket,UUID order,Begin b){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Atomic discovery transaction required");return registered(actor,ticket,order,b,()->{});}
 public Receipt submit(String actor,UUID ticket,UUID order,Begin b,Runnable fence){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Staff return requires independent committed phases");if(ticket==null||order==null||b==null||b.requestId()==null||b.expectedReleaseRevision()<1)throw problem(400,"INVALID_INPUT");Receipt r;try{r=registered(actor,ticket,order,b,fence);}catch(org.springframework.dao.DuplicateKeyException e){r=registered(actor,ticket,order,b,fence);}if(!r.canResume())return r;
  try{if(r.state().equals("REGISTERED"))returns.begin(b.requestId(),order,b.expectedReleaseRevision(),()->guard(actor,b.requestId(),fence),c->{fence.run();checkpoint(b.requestId(),"CLOSED",c.generation(),null);});
   returns.reconcile(b.requestId(),()->guard(actor,b.requestId(),fence),o->{fence.run();checkpoint(b.requestId(),o.state().equals("APPLIED")?"APPLIED":"REDIS_UNKNOWN".equals(o.failure())?"CLOSED":"BLOCKED",o.command().generation(),o.failure());});
  }catch(Finished done){/* Original concurrent actor completed the checkpoint. */}
  catch(Problem e){if(Set.of("STOCK_CHANGED","ACTIVE_STOCK_INTENTS","GENERATION_EXHAUSTED","GENERATION_FENCED","STOCK_INTENTS_PRESENT","REBUILD_CONFLICT","RETURN_COMMAND_CONFLICT","RETURN_ORIGIN_REQUIRED","RETURN_COMMIT_NOT_ACKNOWLEDGED","RETURN_ALREADY_INCLUDED").contains(e.code))blocked(actor,b.requestId(),e.code,fence);else throw e;}
  return write.execute(tx->receipt(owned(actor,b.requestId(),false,false),authorize(actor,false).manager()));
 }
}
