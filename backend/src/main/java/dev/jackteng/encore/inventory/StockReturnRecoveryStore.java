package dev.jackteng.encore.inventory;

import dev.jackteng.encore.common.ApiErrors.Problem;
import dev.jackteng.encore.catalog.Models.Page;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Original actor-owned durable queue. Short PG transactions, never wrapping Redis. */
@Service
public class StockReturnRecoveryStore {
 public record Settings(boolean enabled) {}
 public record Job(UUID requestId,long expectedReleaseRevision,String state,int attempts,int maxAttempts,
  long version,OffsetDateTime nextAttemptAt,String lastCode,OffsetDateTime createdAt,OffsetDateTime completedAt,boolean enabled) {}
 public record Audit(long version,String state,int attempts,String lastCode,OffsetDateTime createdAt) {}
 public record Claim(UUID requestId,UUID actorId,String actor,UUID ticketId,UUID orderId,long releaseRevision,UUID token,int attempt) {}
 public static class LostLease extends RuntimeException {}
 private final JdbcTemplate db;private final boolean enabled;
 @Value("${ticket.stock-return-discovery.enabled:false}") private boolean discoveryEnabled;
 public StockReturnRecoveryStore(JdbcTemplate db,@Value("${ticket.stock-return-recovery.enabled:false}")boolean enabled){this.db=db;this.enabled=enabled;}
 private static Problem problem(int status,String code){return new Problem(status,code,"請保留原返還 UUID／內容，核對背景工作與原結果");}
 private Map<String,Object> owned(String actor,UUID request,boolean manager,boolean lock){
  var accounts=db.queryForList("SELECT id,role FROM staff_account WHERE username=? AND enabled FOR SHARE",actor);
  if(accounts.isEmpty()||manager&&!accounts.getFirst().get("role").equals("MANAGER"))throw problem(403,"FORBIDDEN");
  var rows=db.queryForList("SELECT c.*,p.state FROM stock_return_staff_command c JOIN stock_return_staff_progress p USING(request_id) WHERE c.request_id=?"+(lock?" FOR UPDATE OF p":""),request);
  if(rows.isEmpty()||!accounts.getFirst().get("id").equals(rows.getFirst().get("actor_id")))throw problem(404,"NOT_FOUND");return rows.getFirst();
 }
 private Job find(UUID request){var rows=db.query("SELECT * FROM stock_return_recovery_job WHERE request_id=?",(r,n)->new Job(request,r.getLong("release_revision"),r.getString("state"),r.getInt("attempts"),r.getInt("max_attempts"),r.getLong("version"),r.getObject("next_attempt_at",OffsetDateTime.class),r.getString("last_code"),r.getObject("created_at",OffsetDateTime.class),r.getObject("completed_at",OffsetDateTime.class),enabled),request);return rows.isEmpty()?null:rows.getFirst();}
 @Transactional public Settings settings(String actor){if(db.queryForObject("SELECT count(*) FROM staff_account WHERE username=? AND enabled",Integer.class,actor)!=1)throw problem(403,"FORBIDDEN");return new Settings(enabled);}
 @Transactional public Job read(String actor,UUID request){owned(actor,request,false,false);var j=find(request);if(j==null)throw problem(404,"JOB_NOT_FOUND");return j;}
 @Transactional public Job enroll(String actor,UUID request,StockReturnStaffService.Begin body){
  if(request==null||body==null||body.requestId()==null||body.expectedReleaseRevision()<1)throw problem(400,"INVALID_INPUT");
  if(!request.equals(body.requestId()))throw problem(409,"REQUEST_CONFLICT");
  var c=owned(actor,request,true,true);if(!c.get("release_revision").equals(body.expectedReleaseRevision()))throw problem(409,"REQUEST_CONFLICT");
  var j=find(request);if(j!=null)return j;
  if(!enabled)throw problem(409,"RETURN_RECOVERY_DISABLED");
  if(!Set.of("REGISTERED","CLOSED").contains(c.get("state")))throw problem(409,"RETURN_TERMINAL");
  db.update("INSERT INTO stock_return_recovery_job(request_id,actor_id,release_revision) VALUES(?,?,?)",request,c.get("actor_id"),c.get("release_revision"));return find(request);
 }
 @Transactional public Page<Audit> audit(String actor,UUID request,int page,int size){if(page<1||page>100000||size<1||size>20)throw problem(400,"INVALID_INPUT");owned(actor,request,false,false);return new Page<>(db.query("SELECT * FROM stock_return_recovery_audit WHERE request_id=? ORDER BY version DESC LIMIT ? OFFSET ?",(r,n)->new Audit(r.getLong("version"),r.getString("state"),r.getInt("attempts"),r.getString("last_code"),r.getObject("created_at",OffsetDateTime.class)),request,size,(page-1)*size),db.queryForObject("SELECT count(*) FROM stock_return_recovery_audit WHERE request_id=?",Long.class,request),page,size);}
 @Transactional public Optional<Claim> claim(int seconds){
  if(seconds<1||seconds>120)throw new IllegalArgumentException("Lease must be 1..120 seconds");
  db.update("UPDATE stock_return_recovery_job SET state='REVIEW',version=version+1,completed_at=clock_timestamp(),last_code='LEASE_EXHAUSTED',lease_token=NULL,lease_until=NULL WHERE state='LEASED' AND lease_until<=clock_timestamp() AND attempts=max_attempts");
  UUID token=UUID.randomUUID();var rows=db.query("""
   WITH candidate AS (SELECT request_id FROM stock_return_recovery_job WHERE attempts<max_attempts
    AND ((state='WAITING' AND next_attempt_at<=clock_timestamp()) OR (state='LEASED' AND lease_until<=clock_timestamp()))
    ORDER BY next_attempt_at,request_id FOR UPDATE SKIP LOCKED LIMIT 1),
   claimed AS (UPDATE stock_return_recovery_job j SET state='LEASED',version=version+1,attempts=attempts+1,
    lease_token=?,lease_until=clock_timestamp()+make_interval(secs=>?) FROM candidate c WHERE j.request_id=c.request_id RETURNING j.*)
   SELECT j.*,a.username,c.ticket_id,c.order_id FROM claimed j JOIN staff_account a ON a.id=j.actor_id
    JOIN stock_return_staff_command c USING(request_id)
   """,(r,n)->new Claim(r.getObject("request_id",UUID.class),r.getObject("actor_id",UUID.class),r.getString("username"),r.getObject("ticket_id",UUID.class),r.getObject("order_id",UUID.class),r.getLong("release_revision"),token,r.getInt("attempts")),token,seconds);return rows.stream().findFirst();
 }
 /** After original actor/progress locks, before and after Redis, in every domain TX. */
 public void fence(Claim c){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Lease guard requires domain transaction");if(db.query("SELECT request_id FROM stock_return_recovery_job WHERE request_id=? AND actor_id=? AND release_revision=? AND state='LEASED' AND lease_token=? AND attempts=? AND lease_until>clock_timestamp() FOR UPDATE",(r,n)->r.getObject(1,UUID.class),c.requestId(),c.actorId(),c.releaseRevision(),c.token(),c.attempt()).isEmpty())throw new LostLease();
  if(db.queryForObject("SELECT count(*) FROM stock_return_discovery_source WHERE return_request_id=?",Integer.class,c.requestId())>0){if(!discoveryEnabled)throw problem(409,"DISCOVERY_DISABLED");if(db.query("SELECT p.request_id FROM stock_return_discovery_source s JOIN stock_return_discovery_policy p ON p.request_id=s.policy_id WHERE s.return_request_id=? AND p.actor_id=? AND p.state IN ('ACTIVE','EXHAUSTED') FOR SHARE OF p",(r,n)->r.getObject(1,UUID.class),c.requestId(),c.actorId()).isEmpty())throw problem(409,"DISCOVERY_STOPPED");}
 }
 @Transactional public boolean complete(Claim c){return finish(c,"DONE","APPLIED",0);}
 @Transactional public boolean failed(Claim c,String code,boolean permanent){return finish(c,permanent||c.attempt()>=6?"REVIEW":"WAITING",code,1<<Math.min(5,c.attempt()-1));}
 private boolean finish(Claim c,String state,String code,int delay){if(code==null||!code.matches("[A-Z][A-Z0-9_]{0,47}"))throw new IllegalArgumentException("Safe codes only");return db.update("UPDATE stock_return_recovery_job SET state=?,version=version+1,last_code=?,lease_token=NULL,lease_until=NULL,next_attempt_at=clock_timestamp()+make_interval(secs=>?),completed_at=CASE WHEN ? IN ('DONE','REVIEW') THEN clock_timestamp() ELSE NULL END WHERE request_id=? AND actor_id=? AND release_revision=? AND state='LEASED' AND lease_token=? AND attempts=? AND lease_until>clock_timestamp()",state,code,delay,state,c.requestId(),c.actorId(),c.releaseRevision(),c.token(),c.attempt())==1;}
}
