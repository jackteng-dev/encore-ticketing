package dev.elpsy.ticket.inventory;

import dev.elpsy.ticket.catalog.Models.Page;
import dev.elpsy.ticket.common.ApiErrors.Problem;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** PG-only review. The replacement is an existing published snapshot, never another credit. */
@Service
public class StockReturnResolutionService {
 public record Begin(@NotNull UUID requestId,@Min(1) long expectedVersion,@NotNull UUID replacementRequestId) {}
 public record Snapshot(UUID replacementRequestId,long snapshotRevision,long generation,int available,String proofKind,OffsetDateTime publishedAt) {}
 public record Receipt(UUID requestId,UUID ticketId,UUID orderId,long returnVersion,long releaseRevision,
  long sourceGeneration,UUID replacementRequestId,long snapshotRevision,long generation,int available,
  String proofKind,OffsetDateTime publishedAt,String state,OffsetDateTime createdAt) {}
 public record Preview(UUID requestId,long returnVersion,String returnState,boolean enabled,boolean canResolve,
  String reason,Snapshot replacement,Receipt resolution,Fence fence) {}
 public record Audit(UUID requestId,long returnVersion,UUID replacementRequestId,long generation,
  long snapshotRevision,String state,OffsetDateTime createdAt) {}
 public record Fence(UUID requestId,long returnVersion,UUID replacementRequestId,String state,OffsetDateTime createdAt) {}
 public record Recovery(UUID requestId,long returnVersion,UUID replacementRequestId,String state,Fence fence,
  Receipt resolution,UUID previousReplacementRequestId) {}
 public record Rebind(@NotNull UUID requestId,@Min(1) long expectedVersion,
  @NotNull UUID replacementRequestId,@NotNull UUID previousReplacementRequestId) {}
 public record RecoveryAudit(UUID requestId,long returnVersion,UUID replacementRequestId,
  UUID previousReplacementRequestId,String action,OffsetDateTime createdAt) {}
 private record Actor(UUID id,boolean manager) {}
 private final JdbcTemplate db;private final boolean enabled;
 public StockReturnResolutionService(JdbcTemplate db,@Value("${ticket.stock-return-resolution.enabled:false}") boolean enabled){this.db=db;this.enabled=enabled;}
 private static Problem problem(int status,String code){return new Problem(status,code,"請核對原返還及已發布快照，保留原編號與內容");}
 private Actor authorize(String name,boolean manager){
  var rows=db.query("SELECT id,role FROM staff_account WHERE username=? AND enabled FOR SHARE",(r,n)->new Actor(r.getObject(1,UUID.class),r.getString(2).equals("MANAGER")),name);
  if(rows.isEmpty()||manager&&!rows.getFirst().manager())throw problem(403,"FORBIDDEN");return rows.getFirst();
 }
 private Map<String,Object> owned(Actor who,UUID request,boolean lock){
  var rows=db.queryForList("SELECT c.*,p.version,p.state FROM stock_return_staff_command c JOIN stock_return_staff_progress p USING(request_id) WHERE c.request_id=?"+(lock?" FOR UPDATE OF p":""),request);
  if(rows.isEmpty()||!who.id().equals(rows.getFirst().get("actor_id")))throw problem(404,"NOT_FOUND");return rows.getFirst();
 }
 private Receipt find(UUID request){
  var rows=db.query("SELECT * FROM stock_return_resolution WHERE request_id=?",(r,n)->new Receipt(request,r.getObject("ticket_id",UUID.class),r.getObject("order_id",UUID.class),r.getLong("return_version"),r.getLong("release_revision"),r.getLong("source_generation"),r.getObject("snapshot_request_id",UUID.class),r.getLong("snapshot_revision"),r.getLong("generation"),r.getInt("available"),r.getString("proof_kind"),r.getObject("published_at",OffsetDateTime.class),r.getString("state"),r.getObject("created_at",OffsetDateTime.class)),request);
  return rows.isEmpty()?null:rows.getFirst();
 }
 private Snapshot replacement(UUID request){
  var rows=db.query("SELECT * FROM stock_return_resolution_proof WHERE request_id=?",(r,n)->new Snapshot(r.getObject("snapshot_request_id",UUID.class),r.getLong("snapshot_revision"),r.getLong("generation"),r.getInt("available"),r.getString("proof_kind"),r.getObject("published_at",OffsetDateTime.class)),request);return rows.isEmpty()?null:rows.getFirst();
 }
 private long unresolved(UUID ticket){return db.queryForObject("SELECT count(*) FROM stock_hold_intent i JOIN stock_hold_progress p ON p.intent_id=i.id WHERE i.ticket_id=? AND p.state<>'ABORTED' AND NOT EXISTS(SELECT 1 FROM stock_hold_ack a WHERE a.intent_id=i.id)",Long.class,ticket);}
 private Preview preview(Actor who,Map<String,Object> c){
  var request=(UUID)c.get("request_id");var receipt=find(request);var snapshot=replacement(request);var fence=latestFence(request);
  String reason=receipt!=null?"RESOLVED":!c.get("state").equals("BLOCKED")?"RETURN_NOT_BLOCKED":unresolved((UUID)c.get("ticket_id"))>0?"ACTIVE_STOCK_INTENTS":snapshot==null?"PUBLISHED_SNAPSHOT_REQUIRED":fenced(request,(Long)c.get("version"),snapshot.replacementRequestId())?"ATTEMPT_FENCED":!enabled?"RESOLUTION_DISABLED":!who.manager()?"READ_ONLY":null;
  return new Preview(request,(Long)c.get("version"),(String)c.get("state"),enabled,reason==null,reason,snapshot,receipt,fence);
 }
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Preview preview(String actor,UUID request){var who=authorize(actor,false);return preview(who,owned(who,request,false));}
 @Transactional(timeout=10) public Receipt submit(String actor,UUID request,Begin body){
  if(request==null||body==null||body.requestId()==null||body.expectedVersion()<1||body.replacementRequestId()==null)throw problem(400,"INVALID_INPUT");
  if(!request.equals(body.requestId()))throw problem(409,"REQUEST_CONFLICT");
  var who=authorize(actor,true);var c=owned(who,request,true);var prior=find(request);
  if(prior!=null){if(prior.returnVersion()!=body.expectedVersion()||!prior.replacementRequestId().equals(body.replacementRequestId()))throw problem(409,"REQUEST_CONFLICT");return prior;}
  if(fenced(request,body.expectedVersion(),body.replacementRequestId()))throw problem(409,"ATTEMPT_FENCED");
  if(latestFence(request)!=null&&!bound(request,body.expectedVersion(),body.replacementRequestId()))throw problem(409,"REBIND_REQUIRED");
  if(!enabled)throw problem(409,"RESOLUTION_DISABLED");
  if((Long)c.get("version")!=body.expectedVersion())throw problem(409,"VERSION_CONFLICT");
  if(!c.get("state").equals("BLOCKED"))throw problem(409,"RETURN_NOT_BLOCKED");
  db.queryForObject("SELECT id FROM ticket_type WHERE id=? FOR UPDATE",UUID.class,c.get("ticket_id"));
  db.queryForObject("SELECT ticket_id FROM stock_projection WHERE ticket_id=? FOR UPDATE",UUID.class,c.get("ticket_id"));
  if(unresolved((UUID)c.get("ticket_id"))>0)throw problem(409,"ACTIVE_STOCK_INTENTS");
  var snapshot=replacement(request);if(snapshot==null)throw problem(409,"PUBLISHED_SNAPSHOT_REQUIRED");
  if(!snapshot.replacementRequestId().equals(body.replacementRequestId()))throw problem(409,"SNAPSHOT_CHANGED");
  db.update("INSERT INTO stock_return_resolution(request_id,actor_id,ticket_id,order_id,return_version,release_revision,source_generation,snapshot_request_id,snapshot_revision,generation,available,proof_kind,published_at) SELECT request_id,actor_id,ticket_id,order_id,return_version,release_revision,source_generation,snapshot_request_id,snapshot_revision,generation,available,proof_kind,published_at FROM stock_return_resolution_proof WHERE request_id=?",request);
  return find(request);
 }
 private Fence fenceRow(java.sql.ResultSet r)throws java.sql.SQLException{return new Fence(r.getObject("request_id",UUID.class),r.getLong("return_version"),r.getObject("snapshot_request_id",UUID.class),r.getString("state"),r.getObject("created_at",OffsetDateTime.class));}
 private Fence latestFence(UUID request){var rows=db.query("SELECT * FROM stock_return_resolution_fence WHERE request_id=? ORDER BY created_at DESC,snapshot_request_id DESC LIMIT 1",(r,n)->fenceRow(r),request);return rows.isEmpty()?null:rows.getFirst();}
 private Fence fence(UUID request,long version,UUID snapshot){var rows=db.query("SELECT * FROM stock_return_resolution_fence WHERE request_id=? AND return_version=? AND snapshot_request_id=?",(r,n)->fenceRow(r),request,version,snapshot);return rows.isEmpty()?null:rows.getFirst();}
 private boolean fenced(UUID request,long version,UUID snapshot){return fence(request,version,snapshot)!=null;}
 private boolean bound(UUID request,long version,UUID snapshot){return previous(request,version,snapshot)!=null;}
 private UUID previous(UUID request,long version,UUID snapshot){var rows=db.query("SELECT previous_snapshot_request_id FROM stock_return_resolution_rebind WHERE request_id=? AND return_version=? AND snapshot_request_id=?",(r,n)->r.getObject(1,UUID.class),request,version,snapshot);return rows.isEmpty()?null:rows.getFirst();}
 private void input(UUID request,Begin body){if(request==null||body==null||body.requestId()==null||body.expectedVersion()<1||body.replacementRequestId()==null)throw problem(400,"INVALID_INPUT");if(!request.equals(body.requestId()))throw problem(409,"REQUEST_CONFLICT");}
 private Recovery recovery(UUID request,Begin body){
  var receipt=find(request);var fence=fence(request,body.expectedVersion(),body.replacementRequestId());
  if(receipt!=null&&(receipt.returnVersion()!=body.expectedVersion()||!receipt.replacementRequestId().equals(body.replacementRequestId()))){if(fence==null)throw problem(409,"REQUEST_CONFLICT");receipt=null;}
  return new Recovery(request,body.expectedVersion(),body.replacementRequestId(),receipt!=null?"ABSORBED":fence!=null?"FENCED":"UNKNOWN",fence,receipt,previous(request,body.expectedVersion(),body.replacementRequestId()));
 }
 /** A null receipt on GET is not proof: only the lock + committed permanent fence is. */
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Recovery recovery(String actor,UUID request,Begin body){input(request,body);owned(authorize(actor,false),request,false);return recovery(request,body);}
 @Transactional(timeout=10) public Recovery reconcile(String actor,UUID request,Begin body){
  input(request,body);var who=authorize(actor,true);var c=owned(who,request,true);var result=recovery(request,body);
  if(!result.state().equals("UNKNOWN"))return result;
  if((Long)c.get("version")!=body.expectedVersion())throw problem(409,"VERSION_CONFLICT");
  if(!c.get("state").equals("BLOCKED"))throw problem(409,"RETURN_NOT_BLOCKED");
  db.update("INSERT INTO stock_return_resolution_fence(request_id,return_version,snapshot_request_id,actor_id) VALUES(?,?,?,?)",request,body.expectedVersion(),body.replacementRequestId(),who.id());
  return recovery(request,body);
 }
 /** Explicit old fenced body -> new body + ABSORBED receipt, all in one PG transaction. */
 @Transactional(timeout=10) public Receipt rebind(String actor,UUID request,Rebind body){
  if(body==null||body.previousReplacementRequestId()==null)throw problem(400,"INVALID_INPUT");
  var begin=new Begin(body.requestId(),body.expectedVersion(),body.replacementRequestId());input(request,begin);
  var who=authorize(actor,true);owned(who,request,true);
  if(find(request)!=null){if(!body.previousReplacementRequestId().equals(previous(request,body.expectedVersion(),body.replacementRequestId())))throw problem(409,"REQUEST_CONFLICT");return submit(actor,request,begin);}
  if(!enabled)throw problem(409,"RESOLUTION_DISABLED");
  if(body.previousReplacementRequestId().equals(body.replacementRequestId())||!fenced(request,body.expectedVersion(),body.previousReplacementRequestId()))throw problem(409,"FENCE_REQUIRED");
  if(fenced(request,body.expectedVersion(),body.replacementRequestId()))throw problem(409,"ATTEMPT_FENCED");
  db.update("INSERT INTO stock_return_resolution_rebind(request_id,return_version,snapshot_request_id,previous_snapshot_request_id,actor_id) VALUES(?,?,?,?,?)",request,body.expectedVersion(),body.replacementRequestId(),body.previousReplacementRequestId(),who.id());
  return submit(actor,request,begin);
 }
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Page<RecoveryAudit> recoveryAudit(String actor,UUID request,int page,int size){
  if(page<1||page>100000||size<1||size>20)throw problem(400,"INVALID_INPUT");owned(authorize(actor,false),request,false);
  return new Page<>(db.query("SELECT * FROM stock_return_resolution_recovery_audit WHERE request_id=? ORDER BY created_at,snapshot_request_id,action LIMIT ? OFFSET ?",(r,n)->new RecoveryAudit(request,r.getLong("return_version"),r.getObject("snapshot_request_id",UUID.class),r.getObject("previous_snapshot_request_id",UUID.class),r.getString("action"),r.getObject("created_at",OffsetDateTime.class)),request,size,(page-1)*size),db.queryForObject("SELECT count(*) FROM stock_return_resolution_recovery_audit WHERE request_id=?",Long.class,request),page,size);
 }
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Page<Audit> audit(String actor,UUID request,int page,int size){
  if(page<1||page>100000||size<1||size>20)throw problem(400,"INVALID_INPUT");owned(authorize(actor,false),request,false);
  return new Page<>(db.query("SELECT * FROM stock_return_resolution_audit WHERE request_id=? LIMIT ? OFFSET ?",(r,n)->new Audit(request,r.getLong("return_version"),r.getObject("snapshot_request_id",UUID.class),r.getLong("generation"),r.getLong("snapshot_revision"),r.getString("state"),r.getObject("created_at",OffsetDateTime.class)),request,size,(page-1)*size),db.queryForObject("SELECT count(*) FROM stock_return_resolution_audit WHERE request_id=?",Long.class,request),page,size);
 }
}
