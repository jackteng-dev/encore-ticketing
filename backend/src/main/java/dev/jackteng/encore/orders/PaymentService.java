package dev.jackteng.encore.orders;
import dev.jackteng.encore.orders.OrderModels.Order;
import dev.jackteng.encore.orders.PaymentModels.*;
import dev.jackteng.encore.customer.CustomerService.Principal;
import dev.jackteng.encore.common.ApiErrors.Problem;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class PaymentService {
 private final JdbcTemplate db;private final OrderService orders;private final boolean enabled;
 public PaymentService(JdbcTemplate db,OrderService orders,@Value("${ticket.mock-payments.enabled}") boolean enabled){this.db=db;this.orders=orders;this.enabled=enabled;}
 @Transactional public Order start(Principal principal,UUID id,Pay body){
  var deadline=orders.authorize(principal);var fingerprint=orders.fingerprint("PAY",id,body);
  var prior=orders.replay(principal.id(),body.requestId(),fingerprint);if(prior!=null)return prior;
  if(!enabled)throw new Problem(409,"PAYMENT_DISABLED","模擬付款尚未開放");
  if(!List.of("SUCCESS","DECLINED").contains(body.scenario()))throw new Problem(400,"INVALID_INPUT","模擬情境不正確");
  var order=orders.get(principal.id(),id);orders.lockTicket(order.ticketId());
  order=orders.lockOrder(principal.id(),id);var clock=orders.now();orders.stillAuthenticated(deadline,clock);
  if(order.version()!=body.expectedVersion()||order.totalTwd()!=body.expectedTotalTwd())throw new Problem(409,"QUOTE_CHANGED","訂單金額或版本已變更");
  if(!order.status().equals("PENDING_PAYMENT")||!clock.isBefore(order.expiresAt()))throw new Problem(409,"PAYMENT_CLOSED","保票已截止或不在待付款狀態");
  if(db.queryForObject("SELECT count(*) FROM payment_attempt WHERE order_id=?",Integer.class,id)>0)throw new Problem(409,"ATTEMPT_EXISTS","已有模擬付款，請核對原結果，不重複付款");
  UUID attempt=UUID.randomUUID();
  orders.command(principal.id(),body.requestId(),"PAY",fingerprint,id);
  db.update("INSERT INTO payment_attempt(id,order_id,customer_id,request_id,amount_twd,created_at) VALUES(?,?,?,?,?,?)",attempt,id,principal.id(),body.requestId(),order.totalTwd(),clock);
  db.update("INSERT INTO mock_payment_receipt(event_id,attempt_id,outcome,amount,currency) VALUES(?,?,?,?,'TWD')",UUID.randomUUID(),attempt,body.scenario(),order.totalTwd());
  db.update("UPDATE ticket_order SET version=version+1 WHERE id=?",id);
  return orders.get(principal.id(),id);
 }
 @Transactional public Order reconcile(Principal principal,UUID id,UUID attempt,Reconcile body){
  var deadline=orders.authorize(principal);String fingerprint;
  // Bind both order and attempt into the fingerprint so a UUID cannot cross paths.
  fingerprint=orders.fingerprint("RECONCILE:"+id,attempt,body);
  var prior=orders.replay(principal.id(),body.requestId(),fingerprint);if(prior!=null)return prior;
  var order=orders.get(principal.id(),id);var terms=orders.lockTicket(order.ticketId());order=orders.lockOrder(principal.id(),id);
  var clock=orders.now();orders.stillAuthenticated(deadline,clock);
  var attempts=db.queryForList("SELECT * FROM payment_attempt WHERE id=? AND order_id=? AND customer_id=? FOR UPDATE",attempt,id,principal.id());
  if(attempts.isEmpty())throw new Problem(404,"NOT_FOUND","找不到此付款");
  var receipts=db.queryForList("SELECT * FROM mock_payment_receipt WHERE attempt_id=? ORDER BY created_at,event_id LIMIT 20 FOR SHARE",attempt);
  if(receipts.isEmpty())throw new Problem(409,"RESULT_UNKNOWN","模擬結果仍未確認，請讀取原付款");
  clock=orders.now();orders.stillAuthenticated(deadline,clock);
  for(var receipt:receipts){
   UUID event=(UUID)receipt.get("event_id");String outcome=(String)receipt.get("outcome"),currency=(String)receipt.get("currency");int amount=(Integer)receipt.get("amount");
   String eventFingerprint=orders.fingerprint("MOCK_EVENT",attempt,List.of(event,outcome,amount,currency));
   var previous=db.queryForList("SELECT fingerprint FROM payment_event WHERE event_id=?",event);
   if(!previous.isEmpty()){
    if(!eventFingerprint.equals(previous.getFirst().get("fingerprint")))throw new Problem(409,"EVENT_CONFLICT","模擬事件內容已變更，需要人工核對");
    continue;
   }
   String state=db.queryForObject("SELECT status FROM payment_attempt WHERE id=?",String.class,attempt),decision,reason=null;
   if(!state.equals("PENDING")){decision="REVIEW";reason="EXTRA_EVENT";}
   else if(amount!=order.totalTwd()||amount!=(Integer)attempts.getFirst().get("amount_twd")||!currency.equals("TWD")){decision="REVIEW";reason="AMOUNT_MISMATCH";}
   else if(outcome.equals("DECLINED")){decision="DECLINED";}
   else if(!order.status().equals("PENDING_PAYMENT")||!clock.isBefore(order.expiresAt())){decision="REVIEW";reason="LATE_SUCCESS";}
   else{
    decision="SUCCEEDED";
    if(db.update("UPDATE ticket_type SET held=held-?,sold=sold+? WHERE id=? AND held>=?",order.quantity(),order.quantity(),order.ticketId(),order.quantity())!=1)throw new IllegalStateException("Inventory reconciliation required");
    db.update("UPDATE ticket_order SET status='PAID',closed_at=? WHERE id=? AND status='PENDING_PAYMENT'",clock,id);
    db.update("INSERT INTO payment_inventory_event(order_id,event_id,ticket_id,quantity,held_before,held_after,sold_before,sold_after) VALUES(?,?,?,?,?,?,?,?)",id,event,order.ticketId(),order.quantity(),terms.held(),terms.held()-order.quantity(),terms.sold(),terms.sold()+order.quantity());
   }
   // A due hold is released in this same transaction; late success never re-holds it.
   if(order.status().equals("PENDING_PAYMENT")&&!clock.isBefore(order.expiresAt()))orders.release(order,terms,"EXPIRED",clock);
   db.update("INSERT INTO payment_event(event_id,attempt_id,order_id,fingerprint,decision,reason) VALUES(?,?,?,?,?,?)",event,attempt,id,eventFingerprint,decision,reason);
   db.update("UPDATE payment_attempt SET status=?,reason=?,processed_at=? WHERE id=?",decision,reason,clock,attempt);
   db.update("UPDATE ticket_order SET version=version+1 WHERE id=?",id);
   order=orders.get(principal.id(),id);
  }
  orders.command(principal.id(),body.requestId(),"RECONCILE",fingerprint,id);
  return orders.get(principal.id(),id);
 }
}
