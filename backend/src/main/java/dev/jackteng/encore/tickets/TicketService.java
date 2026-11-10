package dev.jackteng.encore.tickets;
import dev.jackteng.encore.tickets.TicketModels.*;
import dev.jackteng.encore.customer.CustomerService.Principal;
import dev.jackteng.encore.customer.CustomerAccess;
import dev.jackteng.encore.catalog.Models.Page;
import dev.jackteng.encore.common.ApiErrors.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class TicketService {
 private final JdbcTemplate db;private final ObjectMapper json;private final boolean issuance,admission;private final SecureRandom random=new SecureRandom();
 public TicketService(JdbcTemplate db,ObjectMapper json,@Value("${ticket.issuance.enabled:false}")boolean issuance,@Value("${ticket.check-in.enabled:false}")boolean admission){this.db=db;this.json=json;this.issuance=issuance;this.admission=admission;}
 private static Problem fail(int s,String c){return new Problem(s,c,"請保留原操作核對票券；不會退款或改變票額");}
 private OffsetDateTime now(){return db.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class);}
 private String fp(String action,Object body){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((action+":"+json.writeValueAsString(body)).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private Map<String,Object> order(UUID owner,UUID id,boolean lock){var rows=db.queryForList("SELECT * FROM ticket_order WHERE id=? AND customer_id=?"+(lock?" FOR UPDATE":""),id,owner);if(rows.isEmpty())throw fail(404,"NOT_FOUND");return rows.getFirst();}
 private boolean paid(UUID id){return Boolean.TRUE.equals(db.queryForObject("SELECT ticket_paid_proof(?)",Boolean.class,id));}
 private record Header(UUID request,long version,OffsetDateTime created){}
 private Bundle bundle(UUID owner,UUID id){var o=order(owner,id,false);var h=db.query("SELECT request_id,order_version,created_at FROM ticket_issuance WHERE order_id=?",(r,n)->new Header(r.getObject(1,UUID.class),r.getLong(2),r.getObject(3,OffsetDateTime.class)),id);var tickets=db.query("SELECT * FROM issued_ticket WHERE order_id=? ORDER BY unit",(r,n)->new CustomerTicket(r.getObject("id",UUID.class),r.getInt("unit"),r.getString("code"),r.getString("state"),r.getLong("version"),r.getObject("created_at",OffsetDateTime.class),r.getObject("changed_at",OffsetDateTime.class)),id);return new Bundle(id,h.isEmpty()?null:h.getFirst().request(),h.isEmpty()?0:h.getFirst().version(),(Integer)o.get("quantity"),issuance,paid(id),h.isEmpty()?null:h.getFirst().created(),tickets);}
 @Transactional public Bundle read(Principal p,UUID id){var deadline=CustomerAccess.authorize(db,p);var b=bundle(p.id(),id);CustomerAccess.stillAuthenticated(deadline,now());return b;}
 @Transactional public Bundle issue(Principal p,UUID id,Issue body){
  var deadline=CustomerAccess.authorize(db,p);String hash=fp("ISSUE:"+id,body);var prior=db.queryForList("SELECT action,fingerprint,order_id FROM order_command WHERE customer_id=? AND request_id=?",p.id(),body.requestId());
  if(!prior.isEmpty()){if(!hash.equals(prior.getFirst().get("fingerprint"))||!"ISSUE".equals(prior.getFirst().get("action")))throw fail(409,"REQUEST_CONFLICT");var b=bundle(p.id(),id);CustomerAccess.stillAuthenticated(deadline,now());return b;}
  if(db.queryForObject("SELECT count(*) FROM ticket_issuance_fence WHERE customer_id=? AND request_id=?",Integer.class,p.id(),body.requestId())>0)throw fail(409,"REQUEST_FENCED");
  if(db.queryForObject("SELECT count(*) FROM purchase_command WHERE customer_id=? AND request_id=?",Integer.class,p.id(),body.requestId())>0||db.queryForObject("SELECT count(*) FROM stock_hold_intent WHERE customer_id=? AND request_id=?",Integer.class,p.id(),body.requestId())>0)throw fail(409,"REQUEST_CONFLICT");
  var o=order(p.id(),id,true);db.queryForList("SELECT id FROM payment_attempt WHERE order_id=? FOR SHARE",id);CustomerAccess.stillAuthenticated(deadline,now());
  if(db.queryForObject("SELECT count(*) FROM ticket_issuance WHERE order_id=?",Integer.class,id)>0)throw fail(409,"ISSUANCE_EXISTS");
  if(!issuance)throw fail(409,"ISSUANCE_DISABLED");if(((Number)o.get("version")).longValue()!=body.expectedVersion())throw fail(409,"STALE_VERSION");if(!paid(id))throw fail(409,"PAYMENT_NOT_VERIFIED");
  var event=db.queryForObject("SELECT event_id FROM payment_inventory_event WHERE order_id=?",UUID.class,id);
  db.update("INSERT INTO order_command(customer_id,request_id,action,fingerprint,order_id) VALUES(?,?,'ISSUE',?,?)",p.id(),body.requestId(),hash,id);
  db.update("INSERT INTO ticket_issuance(order_id,customer_id,request_id,order_version,quantity,payment_event_id) VALUES(?,?,?,?,?,?)",id,p.id(),body.requestId(),body.expectedVersion(),o.get("quantity"),event);
  for(int i=1;i<=(Integer)o.get("quantity");i++){byte[] bytes=new byte[32];random.nextBytes(bytes);db.update("INSERT INTO issued_ticket(id,order_id,unit,code) VALUES(?,?,?,?)",UUID.randomUUID(),id,i,Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));}
  CustomerAccess.stillAuthenticated(deadline,now());return bundle(p.id(),id);
 }
 private UUID staff(String actor,boolean manager){var rows=db.queryForList("SELECT id,role FROM staff_account WHERE username=? AND enabled FOR SHARE",actor);if(rows.isEmpty()||manager&&!"MANAGER".equals(rows.getFirst().get("role")))throw fail(403,"FORBIDDEN");return(UUID)rows.getFirst().get("id");}
 private static final String VIEW="SELECT x.id,x.order_id,t.session_id,o.event_title,o.session_name,o.ticket_name,x.unit,x.state,x.version,x.changed_at,ticket_paid_proof(o.id) AS paid FROM issued_ticket x JOIN ticket_order o ON o.id=x.order_id JOIN ticket_type t ON t.id=o.ticket_id ";
 private StaffTicket staffTicket(UUID id){var rows=db.query(VIEW+"WHERE x.id=?",(r,n)->new StaffTicket(id,r.getObject("order_id",UUID.class),r.getObject("session_id",UUID.class),r.getString("event_title"),r.getString("session_name"),r.getString("ticket_name"),r.getInt("unit"),r.getString("state"),r.getLong("version"),r.getBoolean("paid")&&r.getString("state").equals("ACTIVE"),r.getObject("changed_at",OffsetDateTime.class)),id);if(rows.isEmpty())throw fail(404,"NOT_FOUND");return rows.getFirst();}
 private UUID byCode(String code){var rows=db.query("SELECT id FROM issued_ticket WHERE code=?",(r,n)->r.getObject(1,UUID.class),code);if(rows.isEmpty())throw fail(404,"NOT_FOUND");return rows.getFirst();}
 private void session(StaffTicket t,UUID expected){if(!t.sessionId().equals(expected))throw fail(409,"WRONG_SESSION");}
 @Transactional public Settings settings(String actor){staff(actor,false);return new Settings(admission);}
 @Transactional public StaffTicket inspect(String actor,Inspect b){staff(actor,true);var t=staffTicket(byCode(b.code()));session(t,b.expectedSessionId());return t;}
 @Transactional public Page<StaffTicket> list(String actor,UUID sid,int page,int size){staff(actor,false);bounds(page,size);var ids=db.query(VIEW+"WHERE t.session_id=? ORDER BY x.created_at DESC,x.id LIMIT ? OFFSET ?",(r,n)->r.getObject("id",UUID.class),sid,size,(page-1)*size);return new Page<>(ids.stream().map(this::staffTicket).toList(),db.queryForObject("SELECT count(*) FROM issued_ticket x JOIN ticket_order o ON o.id=x.order_id JOIN ticket_type t ON t.id=o.ticket_id WHERE t.session_id=?",Long.class,sid),page,size);}
 private void bounds(int page,int size){if(page<1||page>100000||size<1||size>20)throw fail(400,"INVALID_INPUT");}
 @Transactional public Page<Audit> audit(String actor,UUID id,int page,int size){staff(actor,false);bounds(page,size);staffTicket(id);return new Page<>(db.query("SELECT a.*,c.action,c.reason FROM ticket_admission_audit a JOIN ticket_admission_command c USING(request_id) WHERE a.ticket_id=? ORDER BY a.created_at DESC LIMIT ? OFFSET ?",(r,n)->new Audit(r.getObject("request_id",UUID.class),id,r.getString("action"),r.getString("reason"),r.getString("state"),r.getLong("version"),r.getObject("created_at",OffsetDateTime.class)),id,size,(page-1)*size),db.queryForObject("SELECT count(*) FROM ticket_admission_audit WHERE ticket_id=?",Long.class,id),page,size);}
 @Transactional public Receipt checkIn(String actor,CheckIn b){return transition(actor,b.requestId(),"CHECK_IN",fp("CHECK_IN",b),()->byCode(b.code()),b.expectedSessionId(),b.expectedVersion(),null);}
 @Transactional public Receipt revoke(String actor,Revoke b){if(!Set.of("LOST_CODE","SECURITY","OPERATION_ERROR").contains(b.reason()))throw fail(400,"INVALID_INPUT");return transition(actor,b.requestId(),"REVOKE",fp("REVOKE",b),()->b.ticketId(),b.expectedSessionId(),b.expectedVersion(),b.reason());}
 private Receipt receipt(UUID request,String action,UUID id){
  var proof=AdmissionWindowStore.proof(db,request);
  if(action.equals("CHECK_IN")&&proof==null&&db.queryForObject("SELECT count(*) FROM ticket_window_legacy WHERE request_id=?",Integer.class,request)!=1)throw fail(409,"RECEIPT_INCOMPLETE");
  if(action.equals("REVOKE")&&proof!=null)throw fail(409,"RECEIPT_INCOMPLETE");
  return new Receipt(request,action,staffTicket(id),proof);
 }
 private Receipt transition(String actor,UUID request,String action,String hash,java.util.function.Supplier<UUID> resolve,UUID sid,long expected,String reason){
  UUID who=staff(actor,true);db.queryForList("SELECT ticket_admission_request_lock(?)",request);var prior=db.queryForList("SELECT * FROM ticket_admission_command WHERE request_id=?",request);
  if(!prior.isEmpty()){var c=prior.getFirst();if(!who.equals(c.get("actor_id")))throw fail(404,"NOT_FOUND");if(!hash.equals(c.get("fingerprint")))throw fail(409,"REQUEST_CONFLICT");return receipt(request,(String)c.get("action"),(UUID)c.get("ticket_id"));}
  if(db.queryForObject("SELECT count(*) FROM ticket_admission_fence WHERE request_id=?",Integer.class,request)>0)throw fail(409,"REQUEST_FENCED");
  if(!admission)throw fail(409,"ADMISSION_DISABLED");UUID id=resolve.get();var t=staffTicket(id);session(t,sid);
  // Customer -> order -> attempt -> ticket matches payment/issuance lock order.
  UUID owner=db.queryForObject("SELECT customer_id FROM ticket_order WHERE id=?",UUID.class,t.orderId());db.queryForList("SELECT id FROM customer_account WHERE id=? FOR SHARE",owner);db.queryForList("SELECT id FROM ticket_order WHERE id=? FOR SHARE",t.orderId());db.queryForList("SELECT id FROM payment_attempt WHERE order_id=? FOR SHARE",t.orderId());db.queryForList("SELECT id FROM issued_ticket WHERE id=? FOR UPDATE",id);t=staffTicket(id);
  if(!t.state().equals("ACTIVE"))throw fail(409,"TICKET_TERMINAL");if(t.version()!=expected)throw fail(409,"STALE_VERSION");if(action.equals("CHECK_IN")&&!t.eligible())throw fail(409,"PAYMENT_NOT_VERIFIED");
  if(action.equals("CHECK_IN")){var window=AdmissionWindowStore.status(db,sid);if(!window.state().equals("OPEN"))throw fail(409,"ADMISSION_WINDOW_"+window.state());}
  db.update("INSERT INTO ticket_admission_command(request_id,actor_id,ticket_id,session_id,expected_version,action,reason,fingerprint) VALUES(?,?,?,?,?,?,?,?)",request,who,id,sid,expected,action,reason,hash);
  db.update("UPDATE issued_ticket SET state=?,version=version+1,changed_at=clock_timestamp(),changed_request_id=? WHERE id=?",action.equals("CHECK_IN")?"CHECKED_IN":"REVOKED",request,id);return receipt(request,action,id);
 }

 // Read-only receipt lookup never applies the original command. Both lookup and
 // explicit stop use the exact original body; no flag or business-version gate.
 @Transactional public IssueRecovery issueReceipt(Principal p,UUID id,Issue b){return recoverIssue(p,id,b,false);}
 @Transactional public IssueRecovery stopIssue(Principal p,UUID id,Issue b){return recoverIssue(p,id,b,true);}
 private IssueRecovery recoverIssue(Principal p,UUID id,Issue b,boolean stop){
  var deadline=CustomerAccess.authorize(db,p);order(p.id(),id,false);String hash=fp("ISSUE:"+id,b);
  var c=db.queryForList("SELECT action,fingerprint,order_id FROM order_command WHERE customer_id=? AND request_id=?",p.id(),b.requestId());
  if(!c.isEmpty()){
   if(!"ISSUE".equals(c.getFirst().get("action"))||!hash.equals(c.getFirst().get("fingerprint"))||!id.equals(c.getFirst().get("order_id")))throw fail(409,"REQUEST_CONFLICT");
   var result=bundle(p.id(),id);if(!b.requestId().equals(result.requestId())||result.orderVersionAtIssue()!=b.expectedVersion())throw fail(409,"RECEIPT_INCOMPLETE");
   CustomerAccess.stillAuthenticated(deadline,now());return new IssueRecovery(b.requestId(),id,b.expectedVersion(),hash,"APPLIED",result,null);
  }
  if(db.queryForObject("SELECT count(*) FROM purchase_command WHERE customer_id=? AND request_id=?",Integer.class,p.id(),b.requestId())>0||db.queryForObject("SELECT count(*) FROM stock_hold_intent WHERE customer_id=? AND request_id=?",Integer.class,p.id(),b.requestId())>0)throw fail(409,"REQUEST_CONFLICT");
  var f=db.queryForList("SELECT * FROM ticket_issuance_fence WHERE customer_id=? AND request_id=?",p.id(),b.requestId());
  if(!f.isEmpty()&&(!hash.equals(f.getFirst().get("fingerprint"))||!id.equals(f.getFirst().get("order_id"))))throw fail(409,"REQUEST_CONFLICT");
  if(f.isEmpty()&&stop){CustomerAccess.stillAuthenticated(deadline,now());db.update("INSERT INTO ticket_issuance_fence(customer_id,request_id,order_id,expected_version,fingerprint) VALUES(?,?,?,?,?)",p.id(),b.requestId(),id,b.expectedVersion(),hash);}
  var times=db.query("SELECT created_at FROM ticket_issuance_fence WHERE customer_id=? AND request_id=?",(r,n)->r.getObject(1,OffsetDateTime.class),p.id(),b.requestId());
  CustomerAccess.stillAuthenticated(deadline,now());return new IssueRecovery(b.requestId(),id,b.expectedVersion(),hash,times.isEmpty()?"UNKNOWN":"FENCED",null,times.isEmpty()?null:times.getFirst());
 }
 @Transactional public AdmissionRecovery checkInReceipt(String actor,CheckIn b){return recoverAdmission(actor,b.requestId(),"CHECK_IN",fp("CHECK_IN",b),()->byCode(b.code()),b.expectedSessionId(),b.expectedVersion(),null,false);}
 @Transactional public AdmissionRecovery stopCheckIn(String actor,CheckIn b){return recoverAdmission(actor,b.requestId(),"CHECK_IN",fp("CHECK_IN",b),()->byCode(b.code()),b.expectedSessionId(),b.expectedVersion(),null,true);}
 @Transactional public AdmissionRecovery revokeReceipt(String actor,Revoke b){return recoverAdmission(actor,b.requestId(),"REVOKE",fp("REVOKE",b),()->b.ticketId(),b.expectedSessionId(),b.expectedVersion(),b.reason(),false);}
 @Transactional public AdmissionRecovery stopRevoke(String actor,Revoke b){return recoverAdmission(actor,b.requestId(),"REVOKE",fp("REVOKE",b),()->b.ticketId(),b.expectedSessionId(),b.expectedVersion(),b.reason(),true);}
 private AdmissionRecovery recoverAdmission(String actor,UUID request,String action,String hash,java.util.function.Supplier<UUID> resolve,UUID sid,long expected,String reason,boolean stop){
  if(action.equals("REVOKE")&&!Set.of("LOST_CODE","SECURITY","OPERATION_ERROR").contains(reason))throw fail(400,"INVALID_INPUT");
  UUID who=staff(actor,true);db.queryForList("SELECT ticket_admission_request_lock(?)",request);
  var c=db.queryForList("SELECT * FROM ticket_admission_command WHERE request_id=?",request);
  if(!c.isEmpty()){
   var row=c.getFirst();if(!who.equals(row.get("actor_id")))throw fail(404,"NOT_FOUND");if(!hash.equals(row.get("fingerprint")))throw fail(409,"REQUEST_CONFLICT");
   var receipt=receipt(request,action,(UUID)row.get("ticket_id"));return new AdmissionRecovery(request,action,receipt.ticket().id(),sid,expected,reason,hash,"APPLIED",receipt,null);
  }
  var f=db.queryForList("SELECT * FROM ticket_admission_fence WHERE request_id=?",request);UUID id;
  if(!f.isEmpty()){var row=f.getFirst();if(!who.equals(row.get("actor_id")))throw fail(404,"NOT_FOUND");if(!hash.equals(row.get("fingerprint")))throw fail(409,"REQUEST_CONFLICT");id=(UUID)row.get("ticket_id");}
  else {id=resolve.get();staffTicket(id);if(stop)db.update("INSERT INTO ticket_admission_fence(request_id,actor_id,ticket_id,session_id,expected_version,action,reason,fingerprint) VALUES(?,?,?,?,?,?,?,?)",request,who,id,sid,expected,action,reason,hash);}
  var times=db.query("SELECT created_at FROM ticket_admission_fence WHERE request_id=?",(r,n)->r.getObject(1,OffsetDateTime.class),request);
  return new AdmissionRecovery(request,action,id,sid,expected,reason,hash,times.isEmpty()?"UNKNOWN":"FENCED",null,times.isEmpty()?null:times.getFirst());
 }
}
