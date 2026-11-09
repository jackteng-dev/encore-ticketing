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

/** Original-manager finite authorization; discovery only registers PG work, never performs Redis IO. */
@Service
public class StockReturnDiscoveryService {
 public record Enroll(@NotNull UUID requestId,@Min(1) @Max(100) int maxSources) {}
 public record Stop(@NotNull UUID requestId,@Min(1) long expectedVersion) {}
 public record Policy(UUID requestId,UUID ticketId,long startRevision,int maxSources,String state,long version,int discovered,boolean enabled) {}
 public record Source(UUID orderId,long releaseRevision,String disposition,UUID returnRequestId,OffsetDateTime createdAt) {}
 public record Settings(boolean enabled) {}
 public record StopProof(UUID requestId,UUID policyId,long expectedVersion,String state,Policy policy) {}
 public record EnrollmentProof(UUID requestId,UUID ticketId,int maxSources,String state,Policy policy) {}
 private final JdbcTemplate db;private final StockReturnStaffService staff;private final StockReturnRecoveryStore recovery;
 private final boolean enabled,returnEnabled,recoveryEnabled;
 public StockReturnDiscoveryService(JdbcTemplate db,StockReturnStaffService staff,StockReturnRecoveryStore recovery,
  @Value("${ticket.stock-return-discovery.enabled:false}")boolean enabled,@Value("${ticket.stock-return.enabled:false}")boolean returnEnabled,
  @Value("${ticket.stock-return-recovery.enabled:false}")boolean recoveryEnabled){this.db=db;this.staff=staff;this.recovery=recovery;this.enabled=enabled;this.returnEnabled=returnEnabled;this.recoveryEnabled=recoveryEnabled;}
 private static Problem problem(int status,String code){return new Problem(status,code,"請核對原自動返還授權、來源與有界工作，保留原UUID");}
 private UUID actor(String name,boolean manager){var ids=db.query("SELECT id FROM staff_account WHERE username=? AND enabled"+(manager?" AND role='MANAGER'":"")+" FOR SHARE",(r,n)->r.getObject(1,UUID.class),name);if(ids.isEmpty())throw problem(403,"FORBIDDEN");return ids.getFirst();}
 private Map<String,Object> find(UUID id,boolean lock){var rows=db.queryForList("SELECT * FROM stock_return_discovery_policy WHERE request_id=?"+(lock?" FOR UPDATE":""),id);return rows.isEmpty()?null:rows.getFirst();}
 private Map<String,Object> owned(String name,UUID id,boolean manager,boolean lock){var who=actor(name,manager);var row=find(id,lock);if(row==null||!who.equals(row.get("actor_id")))throw problem(404,"NOT_FOUND");return row;}
 private int count(UUID id){return db.queryForObject("SELECT count(*) FROM stock_return_discovery_source WHERE policy_id=?",Integer.class,id);}
 private Policy receipt(Map<String,Object> row){var id=(UUID)row.get("request_id");return new Policy(id,(UUID)row.get("ticket_id"),(Long)row.get("start_revision"),(Integer)row.get("max_sources"),(String)row.get("state"),(Long)row.get("version"),count(id),enabled&&returnEnabled&&recoveryEnabled);}
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Policy read(String name,UUID request){return receipt(owned(name,request,false,false));}
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Settings settings(String name){actor(name,false);return new Settings(enabled&&returnEnabled&&recoveryEnabled);}
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Policy forTicket(String name,UUID ticket){var who=actor(name,false);var ids=db.query("SELECT request_id FROM stock_return_discovery_policy WHERE ticket_id=? AND actor_id=?",(r,n)->r.getObject(1,UUID.class),ticket,who);if(ids.isEmpty())throw problem(404,"NOT_FOUND");return receipt(find(ids.getFirst(),false));}
 @Transactional(isolation=Isolation.REPEATABLE_READ) public EnrollmentProof enrollmentProof(String name,UUID ticket,UUID request,int maxSources){
  if(request==null||maxSources<1||maxSources>100)throw problem(400,"INVALID_INPUT");var who=actor(name,false);
  if(db.query("SELECT ticket_id FROM stock_projection WHERE ticket_id=? FOR SHARE",(r,n)->r.getObject(1,UUID.class),ticket).isEmpty())throw problem(404,"NOT_FOUND");
  var prior=find(request,false);boolean exact=prior!=null&&who.equals(prior.get("actor_id"))&&ticket.equals(prior.get("ticket_id"))&&maxSources==(Integer)prior.get("max_sources");
  boolean superseded=prior!=null||db.queryForObject("SELECT count(*) FROM stock_return_discovery_policy WHERE ticket_id=?",Integer.class,ticket)>0;
  return new EnrollmentProof(request,ticket,maxSources,exact?"APPLIED":superseded?"SUPERSEDED":"UNKNOWN",exact?receipt(prior):null);
 }
 /** No write: lock original policy before proving a missing stop can never match its monotonic version again. */
 @Transactional(isolation=Isolation.REPEATABLE_READ) public StopProof stopProof(String name,UUID policy,UUID request,long expectedVersion){
  if(request==null||expectedVersion<1)throw problem(400,"INVALID_INPUT");var row=owned(name,policy,false,true);
  var commands=db.queryForList("SELECT * FROM stock_return_discovery_stop WHERE request_id=?",request);
  if(!commands.isEmpty()){var c=commands.getFirst();if(!policy.equals(c.get("policy_id"))||!row.get("actor_id").equals(c.get("actor_id"))||!Long.valueOf(expectedVersion).equals(c.get("expected_version")))throw problem(409,"REQUEST_CONFLICT");return new StopProof(request,policy,expectedVersion,"APPLIED",receipt(row));}
  return new StopProof(request,policy,expectedVersion,(Long)row.get("version")>expectedVersion||row.get("state").equals("STOPPED")?"SUPERSEDED":"UNKNOWN",receipt(row));
 }
 @Transactional public Policy enroll(String name,UUID ticket,Enroll body){
  if(ticket==null||body==null||body.requestId()==null||body.maxSources()<1||body.maxSources()>100)throw problem(400,"INVALID_INPUT");
  var who=actor(name,true);db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"stock-return-discovery:"+body.requestId());
  var prior=find(body.requestId(),true);if(prior!=null){if(!who.equals(prior.get("actor_id"))||!ticket.equals(prior.get("ticket_id"))||body.maxSources()!=(Integer)prior.get("max_sources"))throw problem(409,"REQUEST_CONFLICT");return receipt(prior);}
  if(!enabled||!returnEnabled||!recoveryEnabled)throw problem(409,"DISCOVERY_DISABLED");
  var heads=db.query("SELECT source_revision FROM stock_projection WHERE ticket_id=? FOR UPDATE",(r,n)->r.getLong(1),ticket);if(heads.isEmpty())throw problem(404,"NOT_FOUND");
  if(db.queryForObject("SELECT count(*) FROM stock_return_discovery_policy WHERE ticket_id=?",Integer.class,ticket)>0)throw problem(409,"POLICY_EXISTS");
  db.update("INSERT INTO stock_return_discovery_policy(request_id,actor_id,ticket_id,start_revision,max_sources) VALUES(?,?,?,?,?)",body.requestId(),who,ticket,heads.getFirst(),body.maxSources());return receipt(find(body.requestId(),false));
 }
 @Transactional public Policy stop(String name,UUID request,Stop body){
  if(body==null||body.requestId()==null||body.expectedVersion()<1)throw problem(400,"INVALID_INPUT");
  var row=owned(name,request,true,true);var old=db.queryForList("SELECT * FROM stock_return_discovery_stop WHERE request_id=?",body.requestId());
  if(!old.isEmpty()){var c=old.getFirst();if(!request.equals(c.get("policy_id"))||!row.get("actor_id").equals(c.get("actor_id"))||!Long.valueOf(body.expectedVersion()).equals(c.get("expected_version")))throw problem(409,"REQUEST_CONFLICT");return receipt(row);}
  if((Long)row.get("version")!=body.expectedVersion())throw problem(409,"STALE_VERSION");
  if(row.get("state").equals("STOPPED"))throw problem(409,"POLICY_STOPPED");
  db.update("INSERT INTO stock_return_discovery_stop(request_id,policy_id,actor_id,expected_version) VALUES(?,?,?,?)",body.requestId(),request,row.get("actor_id"),body.expectedVersion());
  db.update("UPDATE stock_return_discovery_policy SET state='STOPPED',version=version+1 WHERE request_id=?",request);return receipt(find(request,false));
 }
 @Transactional(isolation=Isolation.REPEATABLE_READ) public Page<Source> sources(String name,UUID request,int page,int size){
  if(page<1||page>100000||size<1||size>20)throw problem(400,"INVALID_INPUT");owned(name,request,false,false);
  return new Page<>(db.query("SELECT * FROM stock_return_discovery_source WHERE policy_id=? ORDER BY release_revision,order_id LIMIT ? OFFSET ?",(r,n)->new Source(r.getObject("order_id",UUID.class),r.getLong("release_revision"),r.getString("disposition"),r.getObject("return_request_id",UUID.class),r.getObject("created_at",OffsetDateTime.class)),request,size,(page-1)*size), (long)count(request),page,size);
 }
 private static final String NEW_SOURCE=" FROM stock_revision_origin o JOIN stock_revision_context x USING(ticket_id,to_revision) JOIN stock_hold_decision d ON d.intent_id=o.intent_id JOIN stock_hold_ack a ON a.intent_id=d.intent_id WHERE o.ticket_id=p.ticket_id AND o.to_revision>p.start_revision AND o.kind='ORDER_RELEASE' AND d.target='COMMIT' AND d.order_id=o.order_id AND a.resolution='COMMITTED' AND NOT EXISTS(SELECT 1 FROM stock_return_discovery_source s WHERE s.order_id=o.order_id)";
 @Transactional public Optional<UUID> discoverOne(){
  if(!enabled||!returnEnabled||!recoveryEnabled)return Optional.empty();
  var candidates=db.queryForList("SELECT p.request_id,a.username FROM stock_return_discovery_policy p JOIN staff_account a ON a.id=p.actor_id WHERE p.state='ACTIVE' AND a.enabled AND a.role='MANAGER' AND EXISTS(SELECT 1"+NEW_SOURCE+") ORDER BY p.created_at,p.request_id LIMIT 1");
  if(candidates.isEmpty())return Optional.empty();var candidate=candidates.getFirst();String name=(String)candidate.get("username");var who=actor(name,true);UUID policy=(UUID)candidate.get("request_id");
  var locked=db.queryForList("SELECT * FROM stock_return_discovery_policy WHERE request_id=? AND actor_id=? AND state='ACTIVE' FOR UPDATE SKIP LOCKED",policy,who);if(locked.isEmpty())return Optional.empty();
  var sources=db.queryForList("SELECT o.order_id,o.ticket_id,o.to_revision FROM stock_return_discovery_policy p JOIN LATERAL (SELECT o.order_id,o.ticket_id,o.to_revision"+NEW_SOURCE+" ORDER BY o.to_revision,o.order_id LIMIT 1) o ON true WHERE p.request_id=?",policy);if(sources.isEmpty())return Optional.empty();
  var source=sources.getFirst();UUID order=(UUID)source.get("order_id"),ticket=(UUID)source.get("ticket_id"),request=null;long revision=(Long)source.get("to_revision");
  boolean existing=db.queryForObject("SELECT (SELECT count(*) FROM stock_return_staff_command WHERE order_id=?)+(SELECT count(*) FROM stock_return_command WHERE order_id=?)",Integer.class,order,order)>0;
  if(!existing){request=UUID.randomUUID();var body=new StockReturnStaffService.Begin(request,revision);staff.registerForDiscovery(name,ticket,order,body);recovery.enroll(name,request,body);}
  db.update("INSERT INTO stock_return_discovery_source(order_id,ticket_id,release_revision,policy_id,disposition,return_request_id) VALUES(?,?,?,?,?,?)",order,ticket,revision,policy,existing?"EXISTING_RETURN":"ENROLLED",request);
  if(count(policy)==(Integer)locked.getFirst().get("max_sources"))db.update("UPDATE stock_return_discovery_policy SET state='EXHAUSTED',version=version+1 WHERE request_id=?",policy);
  return Optional.of(order);
 }
}
