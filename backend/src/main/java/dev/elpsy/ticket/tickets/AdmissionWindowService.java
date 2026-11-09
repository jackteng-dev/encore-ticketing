package dev.elpsy.ticket.tickets;

import dev.elpsy.ticket.tickets.AdmissionWindowModels.*;
import dev.elpsy.ticket.tickets.TicketModels.Settings;
import dev.elpsy.ticket.customer.CustomerAccess;
import dev.elpsy.ticket.customer.CustomerService.Principal;
import dev.elpsy.ticket.catalog.Models.Page;
import dev.elpsy.ticket.common.ApiErrors.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdmissionWindowService {
 private final JdbcTemplate db;
 private final ObjectMapper json;
 private final boolean enabled;
 public AdmissionWindowService(JdbcTemplate db,ObjectMapper json,@Value("${ticket.admission-window.enabled:false}")boolean enabled) {this.db=db;this.json=json;this.enabled=enabled;}
 private static Problem fail(int status,String code) {return new Problem(status,code,"請保留原時間窗操作核對；不會更改票額或撤回入場");}
 private UUID staff(String actor,boolean write) {
  var rows=db.queryForList("SELECT id,role FROM staff_account WHERE username=? AND enabled FOR SHARE",actor);
  if(rows.isEmpty()||write&&!"MANAGER".equals(rows.getFirst().get("role")))throw fail(403,"FORBIDDEN");
  return (UUID)rows.getFirst().get("id");
 }
 private String fingerprint(UUID sid,Configure body) {
  try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("ADMISSION_WINDOW:"+sid+":"+json.writeValueAsString(body)).getBytes(StandardCharsets.UTF_8)));}
  catch(Exception error) {throw new IllegalStateException(error);}
 }
 private OffsetDateTime parse(String raw) {
  try {if(raw==null||!raw.matches(AdmissionWindowModels.UTC_SECONDS))throw new IllegalArgumentException();return OffsetDateTime.parse(raw);}
  catch(RuntimeException error) {throw fail(400,"INVALID_INPUT");}
 }
 private void valid(Configure body) {
  if(body.requestId()==null||body.expectedVersion()<0||body.expectedVersion()>1000000000)throw fail(400,"INVALID_INPUT");
  parse(body.opensAt());parse(body.closesAt());
 }
 @Transactional public Settings settings(String actor) {staff(actor,false);return new Settings(enabled);}
 @Transactional public Status status(String actor,UUID sid) {staff(actor,false);return AdmissionWindowStore.status(db,sid);}
 @Transactional public Status customerStatus(Principal actor,UUID order) {
  var deadline=CustomerAccess.authorize(db,actor);
  var ids=db.query("SELECT t.session_id FROM ticket_order o JOIN ticket_type t ON t.id=o.ticket_id WHERE o.id=? AND o.customer_id=?",(r,n)->r.getObject(1,UUID.class),order,actor.id());
  if(ids.isEmpty())throw fail(404,"NOT_FOUND");
  var status=AdmissionWindowStore.status(db,ids.getFirst());
  CustomerAccess.stillAuthenticated(deadline,db.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class));
  return status;
 }
 private Recovery row(UUID sid,Configure body,String hash) {
  var rows=db.queryForList("SELECT * FROM ticket_window_command WHERE request_id=?",body.requestId());
  if(rows.isEmpty())return null;
  var c=rows.getFirst();
  if(!hash.equals(c.get("fingerprint"))||!sid.equals(c.get("session_id")))throw fail(409,"REQUEST_CONFLICT");
  var policies=db.query("SELECT * FROM ticket_window_policy WHERE request_id=?",(r,n)->new Policy(sid,r.getLong("version"),body.requestId(),r.getObject("opens_at",OffsetDateTime.class),r.getObject("closes_at",OffsetDateTime.class),r.getObject("created_at",OffsetDateTime.class)),body.requestId());
  String state=(String)c.get("state");
  if(state.equals("APPLIED")&&policies.isEmpty()||state.equals("FENCED")&&!policies.isEmpty())throw fail(409,"RECEIPT_INCOMPLETE");
  var time=db.queryForObject("SELECT created_at FROM ticket_window_command WHERE request_id=?",OffsetDateTime.class,body.requestId());
  return new Recovery(body.requestId(),sid,body.expectedVersion(),body.opensAt(),body.closesAt(),hash,state,policies.isEmpty()?null:policies.getFirst(),time);
 }
 private void originalActor(UUID actor,UUID request) {
  var owners=db.query("SELECT actor_id FROM ticket_window_command WHERE request_id=?",(r,n)->r.getObject(1,UUID.class),request);
  if(!owners.isEmpty()&&!owners.getFirst().equals(actor))throw fail(404,"NOT_FOUND");
 }
 @Transactional public Recovery configure(String actor,UUID sid,Configure body) {
  valid(body);
  UUID who=staff(actor,true);
  db.queryForList("SELECT ticket_window_request_lock(?)",body.requestId());
  originalActor(who,body.requestId());
  String hash=fingerprint(sid,body);
  var prior=row(sid,body,hash);
  if(prior!=null) {if(prior.state().equals("FENCED"))throw fail(409,"REQUEST_FENCED");return prior;}
  if(!enabled)throw fail(409,"WINDOW_MANAGEMENT_DISABLED");
  AdmissionWindowStore.session(db,sid,true);
  var current=AdmissionWindowStore.policy(db,sid);
  if(body.expectedVersion()!=(current==null?0:current.version()))throw fail(409,"STALE_VERSION");
  if(!parse(body.opensAt()).isBefore(parse(body.closesAt())))throw fail(400,"INVALID_WINDOW");
  insert(who,sid,body,hash,"APPLIED");
  db.update("INSERT INTO ticket_window_policy(session_id,version,request_id,opens_at,closes_at) VALUES(?,?,?,?,?)",sid,body.expectedVersion()+1,body.requestId(),parse(body.opensAt()),parse(body.closesAt()));
  return row(sid,body,hash);
 }
 private void insert(UUID actor,UUID sid,Configure body,String hash,String state) {
  db.update("INSERT INTO ticket_window_command(request_id,actor_id,session_id,expected_version,opens_at_text,closes_at_text,fingerprint,state,policy_version) VALUES(?,?,?,?,?,?,?,?,?)",body.requestId(),actor,sid,body.expectedVersion(),body.opensAt(),body.closesAt(),hash,state,state.equals("APPLIED")?body.expectedVersion()+1:null);
 }
 @Transactional public Recovery receipt(String actor,UUID sid,Configure body) {return recover(actor,sid,body,false);}
 @Transactional public Recovery stop(String actor,UUID sid,Configure body) {return recover(actor,sid,body,true);}
 private Recovery recover(String actor,UUID sid,Configure body,boolean stop) {
  valid(body);
  UUID who=staff(actor,true);
  db.queryForList("SELECT ticket_window_request_lock(?)",body.requestId());
  originalActor(who,body.requestId());
  String hash=fingerprint(sid,body);
  var prior=row(sid,body,hash);
  if(prior!=null)return prior;
  // Stop is independent of feature flag, current version and interval ordering.
  AdmissionWindowStore.session(db,sid,false);
  if(stop) {insert(who,sid,body,hash,"FENCED");return row(sid,body,hash);}
  return new Recovery(body.requestId(),sid,body.expectedVersion(),body.opensAt(),body.closesAt(),hash,"UNKNOWN",null,null);
 }
 @Transactional public Page<Recovery> audit(String actor,UUID sid,int page,int size) {
  staff(actor,false);
  if(page<1||page>100000||size<1||size>20)throw fail(400,"INVALID_INPUT");
  AdmissionWindowStore.session(db,sid,false);
  var commands=db.query("SELECT * FROM ticket_window_audit WHERE session_id=? ORDER BY created_at DESC,request_id LIMIT ? OFFSET ?",(r,n)->new Configure(r.getObject("request_id",UUID.class),r.getLong("expected_version"),r.getString("opens_at_text"),r.getString("closes_at_text")),sid,size,(page-1)*size);
  return new Page<>(commands.stream().map(b->row(sid,b,fingerprint(sid,b))).toList(),db.queryForObject("SELECT count(*) FROM ticket_window_audit WHERE session_id=?",Long.class,sid),page,size);
 }
}
