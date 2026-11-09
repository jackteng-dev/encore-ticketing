package dev.elpsy.ticket.orders;
import dev.elpsy.ticket.orders.OrderModels.*;
import dev.elpsy.ticket.orders.PurchaseModels.Purchase;
import dev.elpsy.ticket.customer.CustomerService.Principal;
import dev.elpsy.ticket.admission.AdmissionService;
import dev.elpsy.ticket.catalog.Models.Page;
import dev.elpsy.ticket.common.ApiErrors.Problem;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class PurchaseService {
 private final JdbcTemplate db;private final OrderService orders;private final AdmissionService admission;private final boolean enabled,ordering;private final int seconds;
 @org.springframework.beans.factory.annotation.Autowired private org.springframework.data.redis.core.StringRedisTemplate stockRedis;
 @org.springframework.beans.factory.annotation.Autowired private com.fasterxml.jackson.databind.ObjectMapper stockJson;
 @org.springframework.beans.factory.annotation.Autowired private dev.elpsy.ticket.admission.AdmissionSettings stockSettings;
 public PurchaseService(JdbcTemplate db,OrderService orders,AdmissionService admission,@Value("${ticket.purchases.enabled:false}") boolean enabled,@Value("${ticket.orders.enabled:false}") boolean ordering,@Value("${ticket.purchases.deadline-seconds:60}") int seconds){
  if(seconds<5||seconds>120)throw new IllegalArgumentException("Purchase deadline must be 5..120 seconds");this.db=db;this.orders=orders;this.admission=admission;this.enabled=enabled;this.ordering=ordering;this.seconds=seconds;
 }
 private final RowMapper<Purchase> row=(r,n)->new Purchase(r.getObject("id",UUID.class),r.getObject("request_id",UUID.class),r.getObject("ticket_id",UUID.class),r.getInt("quantity"),r.getLong("ticket_version"),r.getInt("total_twd"),r.getString("status"),r.getLong("version"),r.getString("reason"),r.getObject("order_id",UUID.class),r.getObject("created_at",OffsetDateTime.class),r.getObject("deadline_at",OffsetDateTime.class),r.getObject("closed_at",OffsetDateTime.class));
 private Purchase get(UUID owner,UUID id,boolean lock){var list=db.query("SELECT * FROM purchase_request WHERE customer_id=? AND id=?"+(lock?" FOR UPDATE":""),row,owner,id);if(list.isEmpty())throw new Problem(404,"NOT_FOUND","找不到此購票請求");return list.getFirst();}
 private Purchase replay(UUID owner,UUID request,String fp){var prior=db.queryForList("SELECT fingerprint,purchase_id FROM purchase_command WHERE customer_id=? AND request_id=?",owner,request);if(prior.isEmpty()){if(db.queryForObject("SELECT count(*) FROM order_command WHERE customer_id=? AND request_id=?",Integer.class,owner,request)>0||db.queryForObject("SELECT count(*) FROM stock_hold_intent WHERE customer_id=? AND request_id=?",Integer.class,owner,request)>0)throw new Problem(409,"REQUEST_CONFLICT","此操作編號已用於訂單或持久預扣，請核對原操作");return null;}if(!fp.equals(prior.getFirst().get("fingerprint")))throw new Problem(409,"REQUEST_CONFLICT","此操作編號已使用，請核對原購票請求");return get(owner,(UUID)prior.getFirst().get("purchase_id"),false);}
 private void command(UUID owner,UUID request,String action,String fp,UUID id){db.update("INSERT INTO purchase_command(customer_id,request_id,action,fingerprint,purchase_id) VALUES(?,?,?,?,?)",owner,request,action,fp,id);}
 @Transactional public Purchase submit(Principal principal,Place body){
  var auth=orders.authorize(principal);String fp=orders.fingerprint("SUBMIT",body.ticketId(),body);var prior=replay(principal.id(),body.requestId(),fp);if(prior!=null)return prior;
  if(!enabled||!ordering)throw new Problem(409,"PURCHASE_DISABLED","非同步購票請求尚未開放");
  if(body.requestId()==null||body.ticketId()==null||body.quantity()<1||body.quantity()>10||body.expectedTicketVersion()<1||body.expectedTotalTwd()<1||body.expectedTotalTwd()>10000000)throw new Problem(400,"INVALID_INPUT","購票欄位不正確");
  if(db.queryForObject("SELECT count(*) FROM purchase_request WHERE customer_id=? AND status='PROCESSING'",Integer.class,principal.id())>=5)throw new Problem(409,"REQUEST_LIMIT","最多同時處理五筆購票請求");
  admission.limit(principal,true);
  // Acceptance reads the quote; authoritative stock/quota is rechecked by the shared core at processing.
  var terms=db.query("SELECT e.status,s.sales_start,s.sales_end,t.version,t.price_twd FROM ticket_type t JOIN event_session s ON s.id=t.session_id JOIN event e ON e.id=s.event_id WHERE t.id=?",(r,n)->new Object[]{r.getString(1),r.getObject(2,OffsetDateTime.class),r.getObject(3,OffsetDateTime.class),r.getLong(4),r.getInt(5)},body.ticketId());
  if(terms.isEmpty())throw new Problem(404,"NOT_FOUND","找不到此票種");var t=terms.getFirst();var clock=orders.now();
  if(!t[0].equals("PUBLISHED")||clock.isBefore((OffsetDateTime)t[1])||!clock.isBefore((OffsetDateTime)t[2]))throw new Problem(409,"SALES_CLOSED","活動未開賣、已截止或已封存");
  if((Long)t[3]!=body.expectedTicketVersion()||(Integer)t[4]*body.quantity()!=body.expectedTotalTwd())throw new Problem(409,"QUOTE_CHANGED","票價或版本已變更，請重新核對");
  admission.require(principal,body.ticketId());clock=orders.now();orders.stillAuthenticated(auth,clock);var salesEnd=(OffsetDateTime)t[2];if(!clock.isBefore(salesEnd))throw new Problem(409,"SALES_CLOSED","售票已截止");
  var deadline=clock.plusSeconds(seconds).isBefore(salesEnd)?clock.plusSeconds(seconds):salesEnd;UUID id=UUID.randomUUID();
  db.update("INSERT INTO purchase_request(id,customer_id,request_id,ticket_id,quantity,ticket_version,total_twd,session_version,session_hash,fingerprint,created_at,deadline_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,principal.id(),body.requestId(),body.ticketId(),body.quantity(),body.expectedTicketVersion(),body.expectedTotalTwd(),principal.sessionVersion(),principal.sessionHash(),fp,clock,deadline);
  command(principal.id(),body.requestId(),"SUBMIT",fp,id);db.update("INSERT INTO purchase_outbox(id,purchase_id,event_type,schema_version) VALUES(?,?,'PURCHASE_REQUESTED',1)",UUID.randomUUID(),id);
  // FK checks may wait behind a ticket writer; recheck the captured deadlines before acceptance commits.
  clock=orders.now();orders.stillAuthenticated(auth,clock);if(!clock.isBefore(deadline))throw new Problem(409,"REQUEST_EXPIRED","購票請求已超過受理期限，請核對原操作");return get(principal.id(),id,false);
 }
 /** Internal bridge only; no controller calls this method. Original immutable PG
  * intent supplies the body/session/deadline, never message or HTTP ownership.
  * Acceptance + original command + outbox + exact link commit in this transaction.
  */
 @Transactional public Purchase acceptHold(Principal principal,UUID intentId){
  var auth=orders.authorize(principal);
  var intents=db.queryForList("SELECT * FROM stock_hold_intent WHERE id=? AND customer_id=?",intentId,principal.id());
  if(intents.isEmpty())throw new Problem(404,"NOT_FOUND","找不到本人原預扣命令");var i=intents.getFirst();
  var linked=db.query("SELECT purchase_id FROM stock_hold_acceptance WHERE intent_id=?",(r,n)->r.getObject(1,UUID.class),intentId);
  if(!linked.isEmpty())return get(principal.id(),linked.getFirst(),false); // committed replay needs no Redis or enabled flag
  if(!enabled||!ordering)throw new Problem(409,"PURCHASE_DISABLED","非同步購票請求尚未開放");
  var ticket=(UUID)i.get("ticket_id");var t=orders.lockTicket(ticket);
  String progress=db.queryForObject("SELECT state FROM stock_hold_progress WHERE intent_id=? FOR UPDATE",String.class,intentId);
  if(!progress.equals("HELD"))throw new Problem(409,"STOCK_HOLD_NOT_READY","原預扣尚未核對為 HELD");
  if(principal.sessionVersion()!=(Long)i.get("session_version")||!principal.sessionHash().equals(i.get("session_hash")))throw new Problem(409,"STOCK_SESSION_CHANGED","新受理須核對原預扣登入，請勿換 UUID 重送");
  var body=new Place((UUID)i.get("request_id"),ticket,(Integer)i.get("quantity"),(Long)i.get("ticket_version"),(Integer)i.get("total_twd"));
  if(db.queryForObject("SELECT count(*) FROM purchase_command WHERE customer_id=? AND request_id=?",Integer.class,principal.id(),body.requestId())>0||db.queryForObject("SELECT count(*) FROM order_command WHERE customer_id=? AND request_id=?",Integer.class,principal.id(),body.requestId())>0)throw new Problem(409,"REQUEST_CONFLICT","原操作已存在不同命令");
  if(db.queryForObject("SELECT count(*) FROM stock_projection WHERE ticket_id=? AND generation=? AND source_revision=? AND state='READY'",Integer.class,ticket,i.get("generation"),i.get("source_revision"))!=1)throw new Problem(409,"STOCK_GATE_CLOSED","預扣世代需要核對");
  var deadline=db.queryForObject("SELECT deadline_at FROM stock_hold_intent WHERE id=?",OffsetDateTime.class,intentId);var clock=orders.now();
  if(!clock.isBefore(deadline))throw new Problem(409,"REQUEST_EXPIRED","原預扣已超過受理期限");
  if(!t.state().equals("PUBLISHED")||clock.isBefore(t.start())||!clock.isBefore(t.end()))throw new Problem(409,"SALES_CLOSED","售票已截止");
  if(t.version()!=body.expectedTicketVersion()||t.price()*body.quantity()!=body.expectedTotalTwd())throw new Problem(409,"QUOTE_CHANGED","票價或版本已變更");
  if(db.queryForObject("SELECT count(*) FROM purchase_request WHERE customer_id=? AND status='PROCESSING'",Integer.class,principal.id())>=5)throw new Problem(409,"REQUEST_LIMIT","最多同時處理五筆購票請求");
  admission.limit(principal,true);admission.require(principal,ticket);
  var ledger=dev.elpsy.ticket.inventory.RedisTicketLedger.epochs(stockRedis,stockJson,stockSettings.namespace());
  var claim=new dev.elpsy.ticket.inventory.RedisTicketLedger.Claim((Long)i.get("generation"),intentId,principal.id(),body.quantity(),deadline.toInstant().toEpochMilli(),(String)i.get("claim_fingerprint"));
  try{var r=ledger.lookup(ticket,claim);if(r.code()!=dev.elpsy.ticket.inventory.RedisTicketLedger.Code.OK||r.receipt().status()!=dev.elpsy.ticket.inventory.RedisTicketLedger.Status.HELD)throw new Problem(503,"STOCK_HOLD_UNKNOWN","原預扣收據需要核對，請重讀原命令");}
  catch(dev.elpsy.ticket.inventory.RedisTicketLedger.Unavailable e){throw new Problem(503,"STOCK_HOLD_UNKNOWN","原預扣結果未知，請重讀原命令");}
  clock=orders.now();orders.stillAuthenticated(auth,clock);if(!clock.isBefore(deadline)||!clock.isBefore(t.end()))throw new Problem(409,"REQUEST_EXPIRED","原預扣已超過受理期限");
  UUID id=UUID.randomUUID();String fp=orders.fingerprint("SUBMIT",ticket,body);
  db.update("INSERT INTO purchase_request(id,customer_id,request_id,ticket_id,quantity,ticket_version,total_twd,session_version,session_hash,fingerprint,created_at,deadline_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,principal.id(),body.requestId(),ticket,body.quantity(),body.expectedTicketVersion(),body.expectedTotalTwd(),principal.sessionVersion(),principal.sessionHash(),fp,clock,deadline);
  command(principal.id(),body.requestId(),"SUBMIT",fp,id);db.update("INSERT INTO purchase_outbox(id,purchase_id,event_type,schema_version) VALUES(?,?,'PURCHASE_REQUESTED',1)",UUID.randomUUID(),id);
  db.update("INSERT INTO stock_hold_acceptance(intent_id,purchase_id) VALUES(?,?)",intentId,id);
  clock=orders.now();orders.stillAuthenticated(auth,clock);if(!clock.isBefore(deadline))throw new Problem(409,"REQUEST_EXPIRED","原預扣已超過受理期限");
  return get(principal.id(),id,false);
 }
 @Transactional public Purchase detail(Principal principal,UUID id){var auth=orders.authorize(principal);var result=get(principal.id(),id,false);orders.stillAuthenticated(auth,orders.now());return result;}
 @Transactional public Page<Purchase> list(Principal principal,int page,int size){var auth=orders.authorize(principal);if(page<1||page>100000||size<1||size>20)throw new Problem(400,"INVALID_INPUT","分頁超出範圍");var items=db.query("SELECT * FROM purchase_request WHERE customer_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",row,principal.id(),size,(page-1)*size);var total=db.queryForObject("SELECT count(*) FROM purchase_request WHERE customer_id=?",Long.class,principal.id());orders.stillAuthenticated(auth,orders.now());return new Page<>(items,total,page,size);}
 private void finish(Purchase p,String status,String reason,UUID order,OffsetDateTime clock){if(db.update("UPDATE purchase_request SET status=?,reason=?,order_id=?,version=2,closed_at=? WHERE id=? AND status='PROCESSING'",status,reason,order,clock,p.id())!=1)throw new IllegalStateException("Purchase transition requires reconciliation");}
 @Transactional public Purchase cancel(Principal principal,UUID id,Cancel body){var auth=orders.authorize(principal);String fp=orders.fingerprint("CANCEL_PURCHASE",id,body);var prior=replay(principal.id(),body.requestId(),fp);if(prior!=null)return prior;var p=get(principal.id(),id,true);var clock=orders.now();orders.stillAuthenticated(auth,clock);if(p.version()!=body.expectedVersion())throw new Problem(409,"STALE_VERSION","購票請求已變更，請重新讀取");if(p.status().equals("RESERVED"))throw new Problem(409,"ORDER_CREATED","已建立保票訂單，請核對本人訂單再取消");if(p.status().equals("PROCESSING")){boolean due=!clock.isBefore(p.deadlineAt());finish(p,due?"EXPIRED":"CANCELLED",due?"REQUEST_EXPIRED":"CUSTOMER_CANCELLED",null,clock);}command(principal.id(),body.requestId(),"CANCEL",fp,id);return get(principal.id(),id,false);}
 public enum Delivery { PROCESSED,DUPLICATE,INVALID }
 /** Broker identity must match immutable PG intent; no message-supplied owner, quote or quantity. */
 @Transactional public Delivery deliver(UUID event,UUID purchase){
  if(db.queryForObject("SELECT count(*) FROM purchase_outbox WHERE id=? AND purchase_id=? AND event_type='PURCHASE_REQUESTED' AND schema_version=1",Integer.class,event,purchase)!=1)return Delivery.INVALID;
  return processCore(event)?Delivery.PROCESSED:Delivery.DUPLICATE;
 }
 /** Internal processing boundary; no public operator endpoint. */
 @Transactional public boolean process(UUID event){
  return processCore(event);
 }
 private boolean processCore(UUID event){
  var ids=db.query("SELECT p.customer_id,p.id FROM purchase_outbox o JOIN purchase_request p ON p.id=o.purchase_id WHERE o.id=? AND o.event_type='PURCHASE_REQUESTED' AND o.schema_version=1",(r,n)->Map.entry(r.getObject(1,UUID.class),r.getObject(2,UUID.class)),event);if(ids.isEmpty())return false;
  UUID owner=ids.getFirst().getKey(),id=ids.getFirst().getValue();orders.lockOwner(owner);var p=get(owner,id,true);if(!p.status().equals("PROCESSING"))return false;
  var clock=orders.now();if(!clock.isBefore(p.deadlineAt())){finish(p,"EXPIRED","REQUEST_EXPIRED",null,clock);return true;}
  var identity=db.queryForMap("SELECT session_version,session_hash FROM purchase_request WHERE id=?",id);var principal=new Principal(owner,"",(Long)identity.get("session_version"),(String)identity.get("session_hash"));
  try{
   var auth=orders.authorize(principal);var order=orders.reserve(owner,new Place(p.requestId(),p.ticketId(),p.quantity(),p.expectedTicketVersion(),p.expectedTotalTwd()),auth,p.deadlineAt(),()->{});finish(p,"RESERVED",null,order.id(),order.createdAt());
  }catch(Problem e){if(!Set.of("UNAUTHENTICATED","REQUEST_EXPIRED","NOT_FOUND","SALES_CLOSED","QUOTE_CHANGED","PURCHASE_LIMIT","ORDER_LIMIT","SOLD_OUT").contains(e.code))throw e;clock=orders.now();boolean expired=!clock.isBefore(p.deadlineAt());finish(p,expired?"EXPIRED":"REJECTED",expired?"REQUEST_EXPIRED":e.code,null,clock);}
  return true;
 }
 @Transactional public boolean expire(UUID id){var owners=db.query("SELECT customer_id FROM purchase_request WHERE id=?",(r,n)->r.getObject(1,UUID.class),id);if(owners.isEmpty())return false;UUID owner=owners.getFirst();orders.lockOwner(owner);var p=get(owner,id,true);var clock=orders.now();if(!p.status().equals("PROCESSING")||clock.isBefore(p.deadlineAt()))return false;finish(p,"EXPIRED","REQUEST_EXPIRED",null,clock);return true;}
}
