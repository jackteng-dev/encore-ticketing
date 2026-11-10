package dev.jackteng.encore.inventory;
import dev.jackteng.encore.common.ApiErrors.Problem;
import dev.jackteng.encore.catalog.Models.Page;
import dev.jackteng.encore.inventory.StockProjectionService.*;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

/** Operator requests never bypass the original domain gates or resolve REVIEW.
 * Staff/command locks precede the ticket lock; every checkpoint audit is in the
 * same transaction as its domain effect. HTTP is only a multi-phase coordinator.
 */
@Service
public class StockMaintenanceService {
 public record Begin(@NotNull UUID requestId,@Min(1) long expectedSourceRevision) {}
 public record Ticket(UUID ticketId,String eventTitle,String sessionName,String ticketName,int available,
  long sourceRevision,long generation,String state,UUID rebuildRequestId,long unresolvedIntents,long unresolvedReviews,boolean maintenanceEnabled) {}
 public record Receipt(UUID requestId,UUID ticketId,long expectedSourceRevision,String state,Long generation,long version,
  String failure,boolean canResume,OffsetDateTime updatedAt,long currentSourceRevision,long currentGeneration,String currentState) {}
 public record Issue(UUID intentId,UUID requestId,String state,OffsetDateTime deadlineAt,String purchaseStatus,
  String decision,String acknowledgement,String unusedJob,String settlementJob,Integer unusedAttempts,Integer settlementAttempts,String unusedCode,String settlementCode) {}
 public record Audit(long version,String state,Long generation,String failure,OffsetDateTime createdAt) {}
 private record Actor(UUID id,boolean manager) {}
 private static final class Finished extends RuntimeException {}
 private final JdbcTemplate db;private final StockProjectionService projection;private final TransactionTemplate write;private final boolean enabled;
 public StockMaintenanceService(JdbcTemplate db,StockProjectionService projection,PlatformTransactionManager transactions,@Value("${ticket.stock-maintenance.enabled:false}") boolean enabled){this.db=db;this.projection=projection;this.enabled=enabled;write=new TransactionTemplate(transactions);write.setTimeout(10);}
 private static Problem problem(int status,String code){return new Problem(status,code,"請核對原維護操作與目前庫存狀態");}
 private Actor authorize(String actor,boolean manager){var rows=db.query("SELECT id,role FROM staff_account WHERE username=? AND enabled FOR SHARE",(r,n)->new Actor(r.getObject(1,UUID.class),r.getString(2).equals("MANAGER")),actor);if(rows.isEmpty()||manager&&!rows.getFirst().manager())throw problem(403,"FORBIDDEN");return rows.getFirst();}
 private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Maintenance requires independent committed phases");}
 private static void page(int page,int size){if(page<1||page>100000||size<1||size>20)throw problem(400,"INVALID_INPUT");}
 private static final String UNRESOLVED="p.state<>'ABORTED' AND NOT EXISTS(SELECT 1 FROM stock_hold_ack a WHERE a.intent_id=i.id)";
 private static final String TICKETS=" FROM stock_projection g JOIN ticket_type t ON t.id=g.ticket_id JOIN event_session s ON s.id=t.session_id JOIN event e ON e.id=s.event_id";
 private Ticket row(java.sql.ResultSet r)throws java.sql.SQLException {return new Ticket(r.getObject("ticket_id",UUID.class),r.getString("title"),r.getString("session_name"),r.getString("ticket_name"),r.getInt("available"),r.getLong("source_revision"),r.getLong("generation"),r.getString("state"),r.getObject("rebuild_request_id",UUID.class),r.getLong("unresolved"),r.getLong("reviews"),enabled);}
 private static final String SELECT="SELECT g.*,e.title,s.name session_name,t.name ticket_name,t.capacity-t.held-t.sold available,"
  +"(SELECT count(*) FROM stock_hold_intent i JOIN stock_hold_progress p ON p.intent_id=i.id WHERE i.ticket_id=g.ticket_id AND "+UNRESOLVED+") unresolved,"
  +"(SELECT count(*) FROM stock_recovery_job j JOIN stock_hold_intent i ON i.id=j.intent_id JOIN stock_hold_progress p ON p.intent_id=i.id WHERE i.ticket_id=g.ticket_id AND j.state='REVIEW' AND "+UNRESOLVED+") reviews";
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Page<Ticket> tickets(String actor,int p,int size){page(p,size);authorize(actor,false);return new Page<>(db.query(SELECT+TICKETS+" ORDER BY e.created_at DESC,g.ticket_id LIMIT ? OFFSET ?",(r,n)->row(r),size,(p-1)*size),db.queryForObject("SELECT count(*) FROM stock_projection",Long.class),p,size);}
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Ticket ticket(String actor,UUID id){authorize(actor,false);var rows=db.query(SELECT+TICKETS+" WHERE g.ticket_id=?",(r,n)->row(r),id);if(rows.isEmpty())throw problem(404,"NOT_FOUND");return rows.getFirst();}
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Page<Issue> issues(String actor,UUID ticket,int p,int size){page(p,size);ticket(actor,ticket);var from=" FROM stock_hold_intent i JOIN stock_hold_progress p ON p.intent_id=i.id LEFT JOIN stock_hold_acceptance a ON a.intent_id=i.id LEFT JOIN purchase_request q ON q.id=a.purchase_id LEFT JOIN stock_hold_decision d ON d.intent_id=i.id LEFT JOIN stock_hold_ack k ON k.intent_id=i.id LEFT JOIN stock_recovery_job u ON u.intent_id=i.id AND u.kind='UNUSED' LEFT JOIN stock_recovery_job z ON z.intent_id=i.id AND z.kind='SETTLEMENT' WHERE i.ticket_id=? AND "+UNRESOLVED;
  var items=db.query("SELECT i.id,i.request_id,p.state,i.deadline_at,q.status,d.target,k.resolution,u.state unused_job,z.state settlement_job,u.attempts ua,z.attempts za,u.last_code uc,z.last_code zc"+from+" ORDER BY i.created_at,i.id LIMIT ? OFFSET ?",(r,n)->new Issue(r.getObject("id",UUID.class),r.getObject("request_id",UUID.class),r.getString("state"),r.getObject("deadline_at",OffsetDateTime.class),r.getString("status"),r.getString("target"),r.getString("resolution"),r.getString("unused_job"),r.getString("settlement_job"),r.getObject("ua",Integer.class),r.getObject("za",Integer.class),r.getString("uc"),r.getString("zc")),ticket,size,(p-1)*size);
  return new Page<>(items,db.queryForObject("SELECT count(*)"+from,Long.class,ticket),p,size);
 }
 private Map<String,Object> command(UUID request,boolean locked){var rows=db.queryForList("SELECT c.*,p.state,p.generation,p.version,p.failure,p.updated_at FROM stock_maintenance_command c JOIN stock_maintenance_progress p ON p.request_id=c.request_id WHERE c.request_id=?"+(locked?" FOR UPDATE OF p":""),request);return rows.isEmpty()?null:rows.getFirst();}
 private Receipt receipt(Map<String,Object> c,boolean manager){var g=projection.inspect((UUID)c.get("ticket_id"));String state=(String)c.get("state");return new Receipt((UUID)c.get("request_id"),(UUID)c.get("ticket_id"),(Long)c.get("source_revision"),state,(Long)c.get("generation"),(Long)c.get("version"),(String)c.get("failure"),enabled&&manager&&Set.of("REGISTERED","CLOSED").contains(state),db.queryForObject("SELECT updated_at FROM stock_maintenance_progress WHERE request_id=?",OffsetDateTime.class,c.get("request_id")),g.sourceRevision(),g.generation(),g.state());}
 private Map<String,Object> owned(String actor,UUID request,boolean manager,boolean locked){var a=authorize(actor,manager);var c=command(request,locked);if(c==null||!c.get("actor_id").equals(a.id()))throw problem(404,"NOT_FOUND");return c;}
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Receipt progress(String actor,UUID request){var c=owned(actor,request,false,false);return receipt(c,authorize(actor,false).manager());}
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Page<Audit> audit(String actor,UUID request,int p,int size){page(p,size);owned(actor,request,false,false);return new Page<>(db.query("SELECT * FROM stock_maintenance_audit WHERE request_id=? ORDER BY version DESC LIMIT ? OFFSET ?",(r,n)->new Audit(r.getLong("version"),r.getString("state"),r.getObject("generation",Long.class),r.getString("failure"),r.getObject("created_at",OffsetDateTime.class)),request,size,(p-1)*size),db.queryForObject("SELECT count(*) FROM stock_maintenance_audit WHERE request_id=?",Long.class,request),p,size);}
 private void matches(Map<String,Object> c,Actor actor,UUID ticket,Begin b){if(!c.get("actor_id").equals(actor.id())||!c.get("ticket_id").equals(ticket)||!c.get("source_revision").equals(b.expectedSourceRevision()))throw problem(409,"REQUEST_CONFLICT");}
 private Receipt registered(String actor,UUID ticket,Begin b){return write.execute(tx->{var a=authorize(actor,true);var prior=command(b.requestId(),true);if(prior!=null){matches(prior,a,ticket,b);return receipt(prior,true);}if(!enabled)throw problem(409,"MAINTENANCE_DISABLED");
  if(db.queryForObject("SELECT count(*) FROM stock_rebuild_command WHERE request_id=?",Integer.class,b.requestId())>0){
   // Under READ COMMITTED a peer can commit staff registration and the core rebuild
   // after the initial missing lookup. Recheck its actor/body before calling it legacy.
   var appeared=command(b.requestId(),true);if(appeared!=null){matches(appeared,a,ticket,b);return receipt(appeared,true);}
   throw problem(409,"REQUEST_CONFLICT");
  }
  if(db.queryForObject("SELECT count(*) FROM stock_projection WHERE ticket_id=?",Integer.class,ticket)!=1)throw problem(404,"NOT_FOUND");
  db.update("INSERT INTO stock_maintenance_command(request_id,actor_id,ticket_id,source_revision) VALUES(?,?,?,?)",b.requestId(),a.id(),ticket,b.expectedSourceRevision());db.update("INSERT INTO stock_maintenance_progress(request_id,state,version) VALUES(?,'REGISTERED',1)",b.requestId());return receipt(command(b.requestId(),false),true);
 });}
 private void guard(String actor,UUID request){var c=owned(actor,request,true,true);if(!Set.of("REGISTERED","CLOSED").contains(c.get("state")))throw new Finished();if(!enabled)throw problem(409,"MAINTENANCE_DISABLED");}
 private void checkpoint(UUID request,String state,Long generation,String failure){db.update("UPDATE stock_maintenance_progress SET state=?,generation=?,failure=?,version=version+1 WHERE request_id=?",state,generation,failure,request);}
 private void blocked(String actor,UUID request,String code){write.execute(tx->{var c=owned(actor,request,true,true);if(Set.of("REGISTERED","CLOSED").contains(c.get("state")))checkpoint(request,"BLOCKED",(Long)c.get("generation"),code);return null;});}
 public Receipt submit(String actor,UUID ticket,Begin b){outside();if(b==null||b.requestId()==null||ticket==null||b.expectedSourceRevision()<1)throw problem(400,"INVALID_INPUT");Receipt r;
  try{r=registered(actor,ticket,b);}catch(org.springframework.dao.DuplicateKeyException e){r=registered(actor,ticket,b);}
  if(!r.canResume())return r;
  try{projection.begin(ticket,b.requestId(),b.expectedSourceRevision(),()->guard(actor,b.requestId()),c->checkpoint(c.requestId(),"CLOSED",c.generation(),null));
   projection.complete(b.requestId(),()->guard(actor,b.requestId()),p->checkpoint(b.requestId(),p.state(),p.command().generation(),p.failure()));
  }catch(Finished e){/* Another identical HTTP caller committed the terminal checkpoint. */}
  catch(Problem e){if(Set.of("STOCK_CHANGED","ACTIVE_STOCK_INTENTS","GENERATION_EXHAUSTED","GENERATION_FENCED","STOCK_INTENTS_PRESENT","REBUILD_CONFLICT").contains(e.code))blocked(actor,b.requestId(),e.code);else throw e;}
  return write.execute(tx->{var c=owned(actor,b.requestId(),false,false);return receipt(c,authorize(actor,false).manager());});
 }
}
