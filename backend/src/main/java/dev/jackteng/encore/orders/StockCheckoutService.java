package dev.jackteng.encore.orders;

import dev.jackteng.encore.common.ApiErrors.Problem;
import dev.jackteng.encore.customer.CustomerService.Principal;
import dev.jackteng.encore.inventory.StockHoldService;
import dev.jackteng.encore.orders.OrderModels.Place;
import dev.jackteng.encore.orders.PurchaseModels.Purchase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Customer coordinator. Each PG phase commits separately; never compensate an
 * unknown Redis/PG outcome from the HTTP error or invent a new request id.
 */
@Service
public class StockCheckoutService {
 public record Progress(UUID requestId,UUID ticketId,int quantity,long expectedTicketVersion,int expectedTotalTwd,
  String state,OffsetDateTime deadlineAt,boolean canRetrySameRequest,Purchase purchase) {}
 private record Existing(UUID intent,Progress progress) {}
 private final JdbcTemplate db;private final OrderService orders;private final PurchaseService purchases;
 private final dev.jackteng.encore.admission.AdmissionService admission;private final StockHoldService holds;private final PreDebitSettings settings;private final ObjectMapper json;private final TransactionTemplate read;
 public StockCheckoutService(JdbcTemplate db,OrderService orders,PurchaseService purchases,StockHoldService holds,dev.jackteng.encore.admission.AdmissionService admission,PreDebitSettings settings,ObjectMapper json,PlatformTransactionManager transactions){this.db=db;this.orders=orders;this.purchases=purchases;this.holds=holds;this.admission=admission;this.settings=settings;this.json=json;read=new TransactionTemplate(transactions);read.setTimeout(10);}
 private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Checkout requires independent committed phases");}
 private String hash(Place body){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private Progress accepted(Purchase p){return new Progress(p.requestId(),p.ticketId(),p.quantity(),p.expectedTicketVersion(),p.expectedTotalTwd(),p.status(),p.deadlineAt(),false,p);}
 private Existing find(Principal who,UUID request,Place body){
  var auth=orders.authorize(who);
  var rows=db.queryForList("SELECT i.*,p.state FROM stock_hold_intent i JOIN stock_hold_progress p ON p.intent_id=i.id WHERE i.customer_id=? AND i.request_id=?",who.id(),request);
  Existing result;
  if(!rows.isEmpty()){
   var i=rows.getFirst();if(body!=null&&!hash(body).equals(i.get("fingerprint")))throw new Problem(409,"REQUEST_CONFLICT","原操作內容不一致，請核對原操作");
   UUID intent=(UUID)i.get("id");var linked=db.query("SELECT purchase_id FROM stock_hold_acceptance WHERE intent_id=?",(r,n)->r.getObject(1,UUID.class),intent);
   if(!linked.isEmpty())result=new Existing(intent,accepted(purchases.detail(who,linked.getFirst())));
   else{
    String state=(String)i.get("state");boolean review=db.queryForObject("SELECT count(*) FROM stock_recovery_job WHERE intent_id=? AND kind='UNUSED' AND state='REVIEW'",Integer.class,intent)>0;
    if(!state.equals("ABORTED")&&review)state="REVIEW";
    var deadline=db.queryForObject("SELECT deadline_at FROM stock_hold_intent WHERE id=?",OffsetDateTime.class,intent);
    boolean retry=settings.enabled()&&Set.of("PREPARED","HELD").contains(state)&&orders.now().isBefore(deadline)
     &&who.sessionVersion()==(Long)i.get("session_version")&&who.sessionHash().equals(i.get("session_hash"));
    result=new Existing(intent,new Progress(request,(UUID)i.get("ticket_id"),(Integer)i.get("quantity"),(Long)i.get("ticket_version"),(Integer)i.get("total_twd"),state,deadline,retry,null));
   }
  }else{
   var commands=db.queryForList("SELECT action,fingerprint,purchase_id FROM purchase_command WHERE customer_id=? AND request_id=?",who.id(),request);
   if(commands.isEmpty()){
    if(body!=null&&db.queryForObject("SELECT count(*) FROM order_command WHERE customer_id=? AND request_id=?",Integer.class,who.id(),request)>0)throw new Problem(409,"REQUEST_CONFLICT","原操作已用於訂單，請核對原操作");
    result=null;
   }else{
    var c=commands.getFirst();if(!c.get("action").equals("SUBMIT")||body!=null&&!orders.fingerprint("SUBMIT",body.ticketId(),body).equals(c.get("fingerprint")))throw new Problem(409,"REQUEST_CONFLICT","原操作已使用，請核對原操作");
    result=new Existing(null,accepted(purchases.detail(who,(UUID)c.get("purchase_id"))));
   }
  }
  orders.stillAuthenticated(auth,orders.now());return result;
 }
 /** PG-only, verified owner. Available with entry disabled and Redis offline. */
 public Progress progress(Principal who,UUID request){outside();return read.execute(tx->{var known=find(who,request,null);if(known==null)throw new Problem(404,"NOT_FOUND","目前未找到本人原購票操作，請保留原編號核對");return known.progress();});}
 public Progress submit(Principal who,Place body){
  outside();if(body==null||body.requestId()==null||body.ticketId()==null||body.quantity()<1||body.quantity()>10||body.expectedTicketVersion()<1||body.expectedTotalTwd()<1||body.expectedTotalTwd()>10000000)throw new Problem(400,"INVALID_INPUT","購票欄位不正確");
  var known=read.execute(tx->find(who,body.requestId(),body));
  // Committed replay first, before Redis and every new-entry flag.
  if(known!=null&&(known.progress().purchase()!=null||!known.progress().canRetrySameRequest()))return known.progress();
  if(!settings.enabled())throw new Problem(409,"PREDEBIT_DISABLED","預扣購票尚未開放，請保留原操作核對");
  read.execute(tx->{var auth=orders.authorize(who);admission.require(who,body.ticketId());orders.stillAuthenticated(auth,orders.now());return null;});
  var intent=holds.prepare(who,body);var attempt=holds.apply(intent.id());
  if(!attempt.state().equals("HELD")||attempt.failure()!=null)return progress(who,body.requestId());
  try{purchases.acceptHold(who,intent.id());}
  catch(Problem e){if(Set.of("STOCK_HOLD_UNKNOWN","STOCK_GATE_CLOSED","REQUEST_EXPIRED","SALES_CLOSED","QUOTE_CHANGED","STOCK_HOLD_NOT_READY","STOCK_SESSION_CHANGED").contains(e.code))return progress(who,body.requestId());throw e;}
  return progress(who,body.requestId());
 }
}
