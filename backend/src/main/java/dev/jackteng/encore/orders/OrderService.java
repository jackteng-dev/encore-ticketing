package dev.jackteng.encore.orders;
import dev.jackteng.encore.orders.OrderModels.*;
import dev.jackteng.encore.customer.CustomerService.Principal;
import dev.jackteng.encore.catalog.Models.Page;
import dev.jackteng.encore.common.ApiErrors.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class OrderService {
 private final JdbcTemplate db;private final ObjectMapper json;private final boolean enabled;
 @Value("${ticket.mock-payments.enabled:false}") private boolean mockPaymentsEnabled;
 public OrderService(JdbcTemplate db,ObjectMapper json,@Value("${ticket.orders.enabled}") boolean enabled){this.db=db;this.json=json;this.enabled=enabled;}
 @org.springframework.beans.factory.annotation.Autowired private dev.jackteng.encore.admission.AdmissionService admission;
 @Value("${ticket.admission.enabled:false}") private boolean admissionEnabled;
 private final RowMapper<Order> row=(r,n)->new Order(r.getObject("id",UUID.class),r.getObject("request_id",UUID.class),r.getObject("ticket_id",UUID.class),r.getString("event_title"),r.getString("session_name"),r.getObject("starts_at",OffsetDateTime.class),r.getString("ticket_name"),r.getInt("price_twd"),r.getInt("quantity"),r.getInt("total_twd"),r.getString("status"),r.getLong("version"),r.getObject("created_at",OffsetDateTime.class),r.getObject("expires_at",OffsetDateTime.class),r.getObject("closed_at",OffsetDateTime.class),payment(r.getObject("id",UUID.class)),mockPaymentsEnabled);
 private PaymentModels.Payment payment(UUID id){var rows=db.query("SELECT * FROM payment_attempt WHERE order_id=?",(r,n)->new PaymentModels.Payment(r.getObject("id",UUID.class),r.getObject("request_id",UUID.class),r.getString("status"),r.getString("reason"),r.getInt("amount_twd"),r.getObject("created_at",OffsetDateTime.class),r.getObject("processed_at",OffsetDateTime.class)),id);return rows.isEmpty()?null:rows.getFirst();}
 record Terms(UUID event,String title,String state,String session,OffsetDateTime starts,OffsetDateTime start,OffsetDateTime end,String name,int price,int limit,long version,int held,int sold,int capacity) {}
 private static <T> T one(List<T> rows){if(rows.isEmpty())throw new Problem(404,"NOT_FOUND","找不到此訂單或票種");return rows.getFirst();}
 OffsetDateTime now(){return db.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class);}
 void lockOwner(UUID id){one(db.query("SELECT id FROM customer_account WHERE id=? FOR UPDATE",(r,n)->r.getObject(1,UUID.class),id));}
 OffsetDateTime authorize(Principal principal){return dev.jackteng.encore.customer.CustomerAccess.authorize(db,principal);}
 void stillAuthenticated(OffsetDateTime deadline,OffsetDateTime now){dev.jackteng.encore.customer.CustomerAccess.stillAuthenticated(deadline,now);}
 Terms lockTicket(UUID ticket){
  UUID event=one(db.query("SELECT s.event_id FROM ticket_type t JOIN event_session s ON s.id=t.session_id WHERE t.id=?",(r,n)->r.getObject(1,UUID.class),ticket));
  // Shared event lock allows different ticket types concurrently; archive UPDATE excludes new placement.
  one(db.query("SELECT id FROM event WHERE id=? FOR SHARE",(r,n)->r.getObject(1,UUID.class),event));
  return one(db.query("SELECT e.id,e.title,e.status,s.name,s.starts_at,s.sales_start,s.sales_end,t.name,t.price_twd,t.max_per_customer,t.version,t.held,t.sold,t.capacity FROM ticket_type t JOIN event_session s ON s.id=t.session_id JOIN event e ON e.id=s.event_id WHERE t.id=? FOR UPDATE OF t",
   (r,n)->new Terms(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getObject(5,OffsetDateTime.class),r.getObject(6,OffsetDateTime.class),r.getObject(7,OffsetDateTime.class),r.getString(8),r.getInt(9),r.getInt(10),r.getLong(11),r.getInt(12),r.getInt(13),r.getInt(14)),ticket));
 }
 String fingerprint(String action,UUID scope,Object body){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((action+":"+scope+":"+json.writeValueAsString(body)).getBytes(StandardCharsets.UTF_8)));}
  catch(Exception e){throw new IllegalStateException(e);}
 }
 Order replay(UUID owner,UUID request,String fingerprint){
  var prior=db.queryForList("SELECT fingerprint,order_id FROM order_command WHERE customer_id=? AND request_id=?",owner,request);
  if(prior.isEmpty()){
   if(db.queryForObject("SELECT count(*) FROM stock_hold_intent WHERE customer_id=? AND request_id=?",Integer.class,owner,request)>0)throw new Problem(409,"REQUEST_CONFLICT","此操作屬於持久預扣命令，請核對原命令");
   if(db.queryForObject("SELECT count(*) FROM purchase_command WHERE customer_id=? AND request_id=?",Integer.class,owner,request)>0)throw new Problem(409,"REQUEST_CONFLICT","此操作屬於已保存的購票請求，請核對原請求");
   return null;
  }
  if(!fingerprint.equals(prior.getFirst().get("fingerprint")))throw new Problem(409,"REQUEST_CONFLICT","此操作編號已使用，請核對原操作");
  return get(owner,(UUID)prior.getFirst().get("order_id"));
 }
 void command(UUID owner,UUID request,String action,String fingerprint,UUID order){db.update("INSERT INTO order_command(customer_id,request_id,action,fingerprint,order_id) VALUES(?,?,?,?,?)",owner,request,action,fingerprint,order);}
 Order get(UUID owner,UUID id){return one(db.query("SELECT * FROM ticket_order WHERE customer_id=? AND id=?",row,owner,id));}
 Order lockOrder(UUID owner,UUID id){return one(db.query("SELECT * FROM ticket_order WHERE customer_id=? AND id=? FOR UPDATE",row,owner,id));}
 @Transactional public Order place(Principal principal,Place body){
  var authDeadline=authorize(principal);String fingerprint=fingerprint("PLACE",body.ticketId(),body);
  var prior=replay(principal.id(),body.requestId(),fingerprint);if(prior!=null)return prior;
  if(!enabled)throw new Problem(409,"ORDERING_DISABLED","保票尚未開放");
  if(body.quantity()<1||body.quantity()>10||body.expectedTicketVersion()<1)throw new Problem(400,"INVALID_INPUT","保票欄位不正確");
  if(admissionEnabled)admission.limit(principal,true);
  return reserve(principal.id(),body,authDeadline,null,()->{if(admissionEnabled)admission.require(principal,body.ticketId());});
 }
 /** Internal shared transaction core. Caller holds owner/session locks; no independent transaction. */
 Order reserve(UUID owner,Place body,OffsetDateTime authDeadline,OffsetDateTime requestDeadline,Runnable gate){
  String fingerprint=fingerprint("PLACE",body.ticketId(),body);
  var t=lockTicket(body.ticketId());var clock=now();stillAuthenticated(authDeadline,clock);
  if(requestDeadline!=null&&!clock.isBefore(requestDeadline))throw new Problem(409,"REQUEST_EXPIRED","購票請求已超過處理期限");
  if(!t.state().equals("PUBLISHED")||clock.isBefore(t.start())||!clock.isBefore(t.end()))throw new Problem(409,"SALES_CLOSED","活動未開賣、已截止或已封存");
  if(t.version()!=body.expectedTicketVersion()||t.price()*body.quantity()!=body.expectedTotalTwd())throw new Problem(409,"QUOTE_CHANGED","票價或版本已變更，請重新核對");
  int held=db.queryForObject("SELECT COALESCE(sum(quantity),0) FROM ticket_order WHERE customer_id=? AND ticket_id=? AND status IN ('PENDING_PAYMENT','PAID')",Integer.class,owner,body.ticketId());
  if(held+body.quantity()>t.limit())throw new Problem(409,"PURCHASE_LIMIT","此票種已達每人限購，已付款與未釋放的保票亦計入");
  if(db.queryForObject("SELECT count(*) FROM ticket_order WHERE customer_id=? AND status='PENDING_PAYMENT'",Integer.class,owner)>=5)throw new Problem(409,"ORDER_LIMIT","最多同時保留五筆訂單");
  gate.run();
  clock=now();stillAuthenticated(authDeadline,clock);
  if(requestDeadline!=null&&!clock.isBefore(requestDeadline))throw new Problem(409,"REQUEST_EXPIRED","購票請求已超過處理期限");
  if(clock.isBefore(t.start())||!clock.isBefore(t.end()))throw new Problem(409,"SALES_CLOSED","售票已截止，請重新核對");
  if(db.update("UPDATE ticket_type SET held=held+? WHERE id=? AND capacity-held-sold>=?",body.quantity(),body.ticketId(),body.quantity())!=1)throw new Problem(409,"SOLD_OUT","剩餘票額不足，請重新核對");
  UUID id=UUID.randomUUID();var expires=clock.plusMinutes(10).isBefore(t.end())?clock.plusMinutes(10):t.end();
  db.update("INSERT INTO ticket_order(id,customer_id,ticket_id,request_id,event_title,session_name,starts_at,ticket_name,price_twd,quantity,total_twd,status,created_at,expires_at,sales_end) VALUES(?,?,?,?,?,?,?,?,?,?,?,'PENDING_PAYMENT',?,?,?)",id,owner,body.ticketId(),body.requestId(),t.title(),t.session(),t.starts(),t.name(),t.price(),body.quantity(),t.price()*body.quantity(),clock,expires,t.end());
  inventory(id,body.ticketId(),"HOLD","CUSTOMER","PENDING_PAYMENT",body.quantity(),t.held());
  command(owner,body.requestId(),"PLACE",fingerprint,id);return get(owner,id);
 }
 private void inventory(UUID order,UUID ticket,String phase,String actor,String state,int delta,int before){db.update("INSERT INTO order_inventory_event(order_id,phase,ticket_id,actor,order_status,delta,held_before,held_after) VALUES(?,?,?,?,?,?,?,?)",order,phase,ticket,actor,state,delta,before,before+delta);}
 void release(Order order,Terms terms,String status,OffsetDateTime now){
  if(db.update("UPDATE ticket_type SET held=held-? WHERE id=? AND held>=?",order.quantity(),order.ticketId(),order.quantity())!=1)throw new IllegalStateException("Inventory reconciliation required");
  db.update("UPDATE ticket_order SET status=?,version=version+1,closed_at=? WHERE id=? AND status='PENDING_PAYMENT'",status,now,order.id());
  inventory(order.id(),order.ticketId(),"RELEASE",status.equals("EXPIRED")?"SYSTEM":"CUSTOMER",status,-order.quantity(),terms.held());
 }
 @Transactional public Order cancel(Principal principal,UUID id,Cancel body){
  var authDeadline=authorize(principal);String fingerprint=fingerprint("CANCEL",id,body);var prior=replay(principal.id(),body.requestId(),fingerprint);if(prior!=null)return prior;
  var order=get(principal.id(),id);var terms=lockTicket(order.ticketId());order=one(db.query("SELECT * FROM ticket_order WHERE id=? AND customer_id=? FOR UPDATE",row,id,principal.id()));var clock=now();stillAuthenticated(authDeadline,clock);
  if(order.version()!=body.expectedVersion())throw new Problem(409,"STALE_VERSION","訂單狀態已變更，請重新讀取");
  if(order.status().equals("PAID"))throw new Problem(409,"REFUND_REQUIRED","已付款訂單需退款流程，不能直接取消保票");
  if(order.status().equals("PENDING_PAYMENT"))release(order,terms,clock.isBefore(order.expiresAt())?"CANCELLED":"EXPIRED",clock);
  command(principal.id(),body.requestId(),"CANCEL",fingerprint,id);return get(principal.id(),id);
 }
 @Transactional public boolean expire(UUID id){
  var owners=db.query("SELECT customer_id FROM ticket_order WHERE id=?",(r,n)->r.getObject(1,UUID.class),id);if(owners.isEmpty())return false;
  UUID owner=owners.getFirst();lockOwner(owner);var order=get(owner,id);var terms=lockTicket(order.ticketId());order=one(db.query("SELECT * FROM ticket_order WHERE id=? FOR UPDATE",row,id));var clock=now();
  if(!order.status().equals("PENDING_PAYMENT")||clock.isBefore(order.expiresAt()))return false;
  release(order,terms,"EXPIRED",clock);return true;
 }
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ) public Page<Order> list(Principal principal,int page,int size){
  if(page<1||page>100000||size<1||size>20)throw new Problem(400,"INVALID_INPUT","分頁超出範圍");
  return new Page<>(db.query("SELECT * FROM ticket_order WHERE customer_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",row,principal.id(),size,(page-1)*size),db.queryForObject("SELECT count(*) FROM ticket_order WHERE customer_id=?",Long.class,principal.id()),page,size);
 }
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ) public Order detail(Principal principal,UUID id){return get(principal.id(),id);}
}
