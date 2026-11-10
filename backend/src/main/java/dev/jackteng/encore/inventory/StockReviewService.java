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

/** An operator reconciles only original durable decisions. REVIEW rows and
 * retry budgets are never changed; each domain effect shares its PG audit phase. */
@Service
public class StockReviewService {
 public record Begin(@NotNull UUID requestId,@NotNull @Pattern(regexp="UNUSED|SETTLEMENT") String kind,@Min(1) @Max(20) int expectedAttempt,@NotNull @Pattern(regexp="ABANDON|COMMIT|RELEASE") String expectedDecision) {}
 public record Settings(boolean enabled) {}
 public record Receipt(UUID requestId,UUID intentId,UUID ticketId,String kind,int expectedAttempt,String expectedDecision,String state,long version,String resolution,String failure,boolean canResume,OffsetDateTime updatedAt,String currentIntentState,String currentAcknowledgement) {}
 public record Audit(long version,String state,String resolution,String failure,OffsetDateTime createdAt) {}
 private record Actor(UUID id,boolean manager) {}
 private static class Finished extends RuntimeException {}
 private final JdbcTemplate db;private final StockHoldService holds;private final StockSettlementService settlement;private final TransactionTemplate write;private final boolean enabled;
 public StockReviewService(JdbcTemplate db,StockHoldService holds,StockSettlementService settlement,PlatformTransactionManager transactions,@Value("${ticket.stock-review.enabled:false}") boolean enabled){this.db=db;this.holds=holds;this.settlement=settlement;this.enabled=enabled;write=new TransactionTemplate(transactions);write.setTimeout(10);}
 private static Problem problem(int status,String code){return new Problem(status,code,"請依原預扣決定核對，保留原人工操作編號");}
 private Actor authorize(String actor,boolean manager){var rows=db.query("SELECT id,role FROM staff_account WHERE username=? AND enabled FOR SHARE",(r,n)->new Actor(r.getObject(1,UUID.class),r.getString(2).equals("MANAGER")),actor);if(rows.isEmpty()||manager&&!rows.getFirst().manager())throw problem(403,"FORBIDDEN");return rows.getFirst();}
 private static boolean active(String state){return Set.of("REGISTERED","PENDING").contains(state);}
 private Map<String,Object> command(UUID id,boolean lock){var rows=db.queryForList("SELECT c.*,p.state,p.version,p.resolution,p.failure FROM stock_review_command c JOIN stock_review_progress p USING(request_id) WHERE c.request_id=?"+(lock?" FOR UPDATE OF p":""),id);return rows.isEmpty()?null:rows.getFirst();}
 private Map<String,Object> owned(String actor,UUID id,boolean manager,boolean lock){var a=authorize(actor,manager);var c=command(id,lock);if(c==null||!c.get("actor_id").equals(a.id()))throw problem(404,"NOT_FOUND");return c;}
 private Receipt receipt(Map<String,Object> c,boolean manager){UUID intent=(UUID)c.get("intent_id");var row=db.queryForMap("SELECT i.ticket_id,p.state,a.resolution FROM stock_hold_intent i JOIN stock_hold_progress p ON p.intent_id=i.id LEFT JOIN stock_hold_ack a ON a.intent_id=i.id WHERE i.id=?",intent);return new Receipt((UUID)c.get("request_id"),intent,(UUID)row.get("ticket_id"),(String)c.get("kind"),(Integer)c.get("expected_attempt"),(String)c.get("expected_decision"),(String)c.get("state"),(Long)c.get("version"),(String)c.get("resolution"),(String)c.get("failure"),enabled&&manager&&active((String)c.get("state")),db.queryForObject("SELECT updated_at FROM stock_review_progress WHERE request_id=?",OffsetDateTime.class,c.get("request_id")),(String)row.get("state"),(String)row.get("resolution"));}
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Settings settings(String actor){authorize(actor,false);return new Settings(enabled);}
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Receipt progress(String actor,UUID id){return receipt(owned(actor,id,false,false),authorize(actor,false).manager());}
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Page<Audit> audit(String actor,UUID id,int page,int size){if(page<1||page>100000||size<1||size>20)throw problem(400,"INVALID_INPUT");owned(actor,id,false,false);return new Page<>(db.query("SELECT * FROM stock_review_audit WHERE request_id=? ORDER BY version DESC LIMIT ? OFFSET ?",(r,n)->new Audit(r.getLong("version"),r.getString("state"),r.getString("resolution"),r.getString("failure"),r.getObject("created_at",OffsetDateTime.class)),id,size,(page-1)*size),db.queryForObject("SELECT count(*) FROM stock_review_audit WHERE request_id=?",Long.class,id),page,size);}
 private void validate(UUID intent,Begin b,boolean fresh){var jobs=db.queryForList("SELECT state,attempts FROM stock_recovery_job WHERE intent_id=? AND kind=?",intent,b.kind());if(jobs.isEmpty())throw problem(404,"NOT_FOUND");if(!jobs.getFirst().get("state").equals("REVIEW")||!jobs.getFirst().get("attempts").equals(b.expectedAttempt()))throw problem(409,"REVIEW_CHANGED");
  if(b.kind().equals("UNUSED")){String state=db.queryForObject("SELECT state FROM stock_hold_progress WHERE intent_id=?",String.class,intent);if(!b.expectedDecision().equals("ABANDON")||!Set.of("ABORTING","ABORTED").contains(state)||fresh&&state.equals("ABORTED")||db.queryForObject("SELECT count(*) FROM stock_hold_acceptance WHERE intent_id=?",Integer.class,intent)>0)throw problem(409,"REVIEW_CHANGED");}
  else{if(db.queryForObject("SELECT count(*) FROM stock_hold_decision WHERE intent_id=? AND target=?",Integer.class,intent,b.expectedDecision())!=1||fresh&&db.queryForObject("SELECT count(*) FROM stock_hold_ack WHERE intent_id=?",Integer.class,intent)>0)throw problem(409,"REVIEW_CHANGED");}
 }
 private Begin body(Map<String,Object> c){return new Begin((UUID)c.get("request_id"),(String)c.get("kind"),(Integer)c.get("expected_attempt"),(String)c.get("expected_decision"));}
 private Receipt registered(String actor,UUID intent,Begin b){return write.execute(tx->{var a=authorize(actor,true);var c=command(b.requestId(),true);if(c!=null){if(!c.get("actor_id").equals(a.id())||!c.get("intent_id").equals(intent)||!body(c).equals(b))throw problem(409,"REQUEST_CONFLICT");return receipt(c,true);}if(!enabled)throw problem(409,"REVIEW_DISABLED");
  try{validate(intent,b,true);}catch(Problem e){
   if(!e.code.equals("REVIEW_CHANGED"))throw e;
   // A peer may commit the original command after the first READ COMMITTED lookup.
   // Recheck its exact actor/intent/body before treating terminal evidence as fresh work.
   c=command(b.requestId(),true);if(c==null)throw e;
   if(!c.get("actor_id").equals(a.id())||!c.get("intent_id").equals(intent)||!body(c).equals(b))throw problem(409,"REQUEST_CONFLICT");
   return receipt(c,true);
  }
  db.update("INSERT INTO stock_review_command(request_id,actor_id,intent_id,kind,expected_attempt,expected_decision) VALUES(?,?,?,?,?,?)",b.requestId(),a.id(),intent,b.kind(),b.expectedAttempt(),b.expectedDecision());db.update("INSERT INTO stock_review_progress(request_id,state,version) VALUES(?,'REGISTERED',1)",b.requestId());return receipt(command(b.requestId(),false),true);});}
 private void guard(String actor,UUID request){var c=owned(actor,request,true,true);if(!active((String)c.get("state")))throw new Finished();if(!enabled)throw problem(409,"REVIEW_DISABLED");}
 /** Called after the original domain ticket lock; terminal domain evidence may
  * be replayed PG-only even after a later generation, never mutate old Redis. */
 private void fence(UUID intent,Begin b){validate(intent,b,false);if(db.queryForObject("SELECT count(*) FROM stock_hold_progress p WHERE p.intent_id=? AND (p.state='ABORTED' OR EXISTS(SELECT 1 FROM stock_hold_ack a WHERE a.intent_id=p.intent_id))",Integer.class,intent)>0)return;if(db.queryForObject("SELECT count(*) FROM stock_hold_intent i JOIN stock_projection g ON g.ticket_id=i.ticket_id WHERE i.id=? AND i.generation=g.generation",Integer.class,intent)!=1)throw problem(409,"GENERATION_FENCED");}
 private void checkpoint(UUID request,String state,String resolution,String failure){db.update("UPDATE stock_review_progress SET state=?,resolution=?,failure=?,version=version+1 WHERE request_id=?",state,resolution,failure,request);}
 private void outcome(UUID request,boolean resolved,boolean phase,String resolution,String failure){checkpoint(request,resolved?"RESOLVED":phase||"REDIS_UNKNOWN".equals(failure)?"PENDING":"BLOCKED",resolved?resolution:null,resolved||phase?null:failure);}
 public Receipt submit(String actor,UUID intent,Begin b){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Review requires independent committed phases");if(intent==null||b==null||b.requestId()==null||!Set.of("UNUSED","SETTLEMENT").contains(Objects.toString(b.kind(),""))||b.expectedAttempt()<1||b.expectedAttempt()>20||!Set.of("ABANDON","COMMIT","RELEASE").contains(Objects.toString(b.expectedDecision(),"")))throw problem(400,"INVALID_INPUT");Receipt r;try{r=registered(actor,intent,b);}catch(org.springframework.dao.DuplicateKeyException e){r=registered(actor,intent,b);}if(!r.canResume())return r;
  try{if(b.kind().equals("UNUSED"))holds.reconcileReviewed(intent,()->guard(actor,b.requestId()),()->fence(intent,b),a->{String resolution=a.state().equals("ABORTED")?db.queryForObject("SELECT resolution FROM stock_hold_progress WHERE intent_id=?",String.class,intent):null;outcome(b.requestId(),a.state().equals("ABORTED"),false,resolution,a.failure());});else settlement.reconcileReviewed(intent,()->guard(actor,b.requestId()),()->fence(intent,b),o->outcome(b.requestId(),o.state().equals("ACKNOWLEDGED"),o.state().equals("FENCED"),o.resolution(),o.failure()));}
  catch(Finished e){/* Identical concurrent caller already committed terminal evidence. */}
  catch(Problem e){if(Set.of("GENERATION_FENCED","PG_COMMAND_CONFLICT","STOCK_DECISION_CONFLICT","STOCK_DECISION_NOT_READY","ABANDONMENT_NOT_DECIDED","REVIEW_CHANGED").contains(e.code))write.execute(tx->{var c=owned(actor,b.requestId(),true,true);if(active((String)c.get("state")))checkpoint(b.requestId(),"BLOCKED",null,e.code);return null;});else throw e;}
  return write.execute(tx->receipt(owned(actor,b.requestId(),false,false),authorize(actor,false).manager()));
 }
}
