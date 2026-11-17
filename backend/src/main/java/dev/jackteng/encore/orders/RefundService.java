package dev.jackteng.encore.orders;
import dev.jackteng.encore.catalog.Models.Page;
import dev.jackteng.encore.common.ApiErrors.Problem;
import dev.jackteng.encore.customer.CustomerAccess;
import dev.jackteng.encore.customer.CustomerService.Principal;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/**
 * 退票申請與審核（模擬退款）。不改訂單狀態、票券或票額：
 * 核准只記錄模擬退款編號，票券撤銷仍由員工走既有受保護的撤銷流程，票額不自動回補。
 */
@Service
public class RefundService {
 public record Refund(UUID id,UUID orderId,String eventTitle,String sessionName,OffsetDateTime startsAt,String ticketName,int quantity,int amountTwd,
  String reason,String status,String reviewNote,OffsetDateTime reviewedAt,UUID mockRefundId,long version,OffsetDateTime createdAt,long activeTickets,long checkedInTickets,OffsetDateTime withdrawnAt) {}
 public record RefundTicket(UUID ticketId,UUID sessionId,int unit,String state) {}
 public record Apply(@NotNull UUID requestId,@NotBlank @Size(max=500) String reason) {}
 public record Withdraw(@Min(1) long expectedVersion) {}
 public record Review(@Min(1) long expectedVersion,@NotNull Boolean approve,@Size(max=500) String note) {}
 /** 開演前至少這麼多小時才可申請。 */
 public static final int CUTOFF_HOURS=24;
 private final JdbcTemplate db;
 public RefundService(JdbcTemplate db){this.db=db;}
 private static final String VIEW="SELECT r.*,o.event_title,o.session_name,o.starts_at,o.ticket_name,o.quantity,"
  +"(SELECT count(*) FROM issued_ticket i WHERE i.order_id=r.order_id AND i.state='ACTIVE') active_tickets,"
  +"(SELECT count(*) FROM issued_ticket i WHERE i.order_id=r.order_id AND i.state='CHECKED_IN') checked_tickets"
  +" FROM refund_request r JOIN ticket_order o ON o.id=r.order_id ";
 private static final RowMapper<Refund> ROW=(r,n)->new Refund(r.getObject("id",UUID.class),r.getObject("order_id",UUID.class),r.getString("event_title"),
  r.getString("session_name"),r.getObject("starts_at",OffsetDateTime.class),r.getString("ticket_name"),r.getInt("quantity"),r.getInt("amount_twd"),
  r.getString("reason"),r.getString("status"),r.getString("review_note"),r.getObject("reviewed_at",OffsetDateTime.class),r.getObject("mock_refund_id",UUID.class),
  r.getLong("version"),r.getObject("created_at",OffsetDateTime.class),r.getLong("active_tickets"),r.getLong("checked_tickets"),r.getObject("withdrawn_at",OffsetDateTime.class));
 private Refund one(UUID id){var rows=db.query(VIEW+"WHERE r.id=?",ROW,id);if(rows.isEmpty())throw new Problem(404,"NOT_FOUND","找不到此申請");return rows.getFirst();}
 private static void bounded(int page,int size){if(page<1||page>100000||size<1||size>50)throw new Problem(400,"INVALID_INPUT","分頁超出範圍");}

 /** 原 requestId 重送：同內容回原申請，不同內容回 409；不建立第二筆。 */
 @Transactional public Refund apply(Principal p,UUID orderId,Apply in){
  CustomerAccess.authorize(db,p);
  var prior=db.queryForList("SELECT id,order_id,reason FROM refund_request WHERE customer_id=? AND request_id=?",p.id(),in.requestId());
  if(!prior.isEmpty()){var x=prior.getFirst();
   if(!orderId.equals(x.get("order_id"))||!in.reason().strip().equals(x.get("reason")))throw new Problem(409,"REQUEST_CONFLICT","此操作編號已使用，請核對原申請");
   return one((UUID)x.get("id"));}
  var orders=db.queryForList("SELECT status,total_twd,starts_at>clock_timestamp()+make_interval(hours=>?) in_time FROM ticket_order WHERE id=? AND customer_id=? FOR SHARE",CUTOFF_HOURS,orderId,p.id());
  if(orders.isEmpty())throw new Problem(404,"NOT_FOUND","找不到此訂單");
  var o=orders.getFirst();
  if(!"PAID".equals(o.get("status")))throw new Problem(409,"NOT_REFUNDABLE","只有已付款訂單可以申請退票");
  if(!Boolean.TRUE.equals(o.get("in_time")))throw new Problem(409,"NOT_REFUNDABLE","開演前 "+CUTOFF_HOURS+" 小時內不受理退票");
  lockTickets(orderId);
  if(db.queryForObject("SELECT count(*) FROM issued_ticket WHERE order_id=? AND state='CHECKED_IN'",Long.class,orderId)>0)throw new Problem(409,"NOT_REFUNDABLE","已有票券入場，無法退票");
  if(db.queryForObject("SELECT count(*) FROM refund_request WHERE order_id=? AND status IN ('PENDING','APPROVED')",Long.class,orderId)>0)throw new Problem(409,"REFUND_EXISTS","此訂單已有處理中或已核准的退票申請");
  UUID id=UUID.randomUUID();
  db.update("INSERT INTO refund_request(id,order_id,customer_id,request_id,reason,amount_twd,status) VALUES(?,?,?,?,?,?,'PENDING')",
   id,orderId,p.id(),in.requestId(),in.reason().strip(),((Number)o.get("total_twd")).intValue());
  return one(id);
 }
 @Transactional public Refund withdraw(Principal p,UUID id,Withdraw in){
  CustomerAccess.authorize(db,p);
  var rows=db.queryForList("SELECT status,version FROM refund_request WHERE id=? AND customer_id=? FOR UPDATE",id,p.id());
  if(rows.isEmpty())throw new Problem(404,"NOT_FOUND","找不到此申請");
  var r=rows.getFirst();if(((Number)r.get("version")).longValue()!=in.expectedVersion())throw new Problem(409,"STALE_VERSION","申請狀態已變更，請重新讀取");
  if(!"PENDING".equals(r.get("status")))throw new Problem(409,"NOT_PENDING","只有審核中的申請可以撤回");
  db.update("UPDATE refund_request SET status='WITHDRAWN',withdrawn_at=clock_timestamp(),version=version+1 WHERE id=?",id);
  return one(id);
 }
 /** 指定訂單（最多 20 筆）各自最新的一筆申請；不做無上限的歷史列表。 */
 @Transactional public List<Refund> latest(Principal p,List<UUID> orderIds){
  if(orderIds.size()>20)throw new Problem(400,"INVALID_INPUT","一次最多查詢 20 筆訂單");
  CustomerAccess.authorize(db,p);if(orderIds.isEmpty())return List.of();
  String in=String.join(",",Collections.nCopies(orderIds.size(),"?"));List<Object> args=new ArrayList<>();args.add(p.id());args.addAll(orderIds);
  return db.query("SELECT * FROM ("+VIEW+"WHERE r.customer_id=? AND r.order_id IN ("+in+")) x WHERE NOT EXISTS(SELECT 1 FROM refund_request n WHERE n.order_id=x.order_id AND (n.created_at,n.id)>(x.created_at,x.id))",ROW,args.toArray());
 }

 /** 依驗票相同順序取共享鎖（order -> attempt -> tickets），使同時進行的驗票會等待或被我們看見。 */
 private void lockTickets(UUID orderId){
  db.queryForList("SELECT id FROM ticket_order WHERE id=? FOR SHARE",orderId);
  db.queryForList("SELECT id FROM payment_attempt WHERE order_id=? ORDER BY id FOR SHARE",orderId);
  db.queryForList("SELECT id FROM issued_ticket WHERE order_id=? ORDER BY id FOR SHARE",orderId);
 }
 /** 員工查看申請對應訂單的票券，交給既有受保護的撤銷流程。 */
 @Transactional public List<RefundTicket> tickets(String actor,UUID id){
  staff(actor,false);
  var r=db.queryForList("SELECT order_id FROM refund_request WHERE id=?",UUID.class,id);if(r.isEmpty())throw new Problem(404,"NOT_FOUND","找不到此申請");
  return db.query("SELECT i.id,i.unit,i.state,t.session_id FROM issued_ticket i JOIN ticket_order o ON o.id=i.order_id JOIN ticket_type t ON t.id=o.ticket_id WHERE i.order_id=? ORDER BY i.unit",
   (x,n)->new RefundTicket(x.getObject("id",UUID.class),x.getObject("session_id",UUID.class),x.getInt("unit"),x.getString("state")),r.getFirst());
 }
 private UUID staff(String actor,boolean manager){
  var rows=db.queryForList("SELECT id,role FROM staff_account WHERE username=? AND enabled FOR SHARE",actor);
  if(rows.isEmpty()||manager&&!"MANAGER".equals(rows.getFirst().get("role")))throw new Problem(403,"FORBIDDEN","沒有管理權限");
  return (UUID)rows.getFirst().get("id");
 }
 @Transactional public Page<Refund> queue(String actor,String status,int page,int size){
  staff(actor,false);bounded(page,size);
  if(!Set.of("PENDING","APPROVED","REJECTED","WITHDRAWN","ALL").contains(status))throw new Problem(400,"INVALID_INPUT","不支援的狀態");
  String where=status.equals("ALL")?"":"WHERE r.status=? ";Object[] base=status.equals("ALL")?new Object[]{}:new Object[]{status};
  long total=db.queryForObject("SELECT count(*) FROM refund_request r "+where,Long.class,base);
  List<Object> args=new ArrayList<>(List.of(base));args.add(size);args.add((page-1)*size);
  return new Page<>(db.query(VIEW+where+"ORDER BY r.created_at,r.id LIMIT ? OFFSET ?",ROW,args.toArray()),total,page,size);
 }
 /** 審核以 expectedVersion 防止重複或過期操作；回覆遺失時先讀取再決定，不盲目重送。 */
 @Transactional public Refund review(String actor,UUID id,Review in){
  UUID who=staff(actor,true);
  var owner=db.queryForList("SELECT customer_id,order_id FROM refund_request WHERE id=?",id);
  if(owner.isEmpty())throw new Problem(404,"NOT_FOUND","找不到此申請");
  // 鎖順序：customer -> refund -> order -> attempt -> tickets，與撤回（customer -> refund）及驗票（customer -> order -> attempt -> ticket）一致。
  db.queryForList("SELECT id FROM customer_account WHERE id=? FOR SHARE",owner.getFirst().get("customer_id"));
  var rows=db.queryForList("SELECT status,version FROM refund_request WHERE id=? FOR UPDATE",id);
  var r=rows.getFirst();if(((Number)r.get("version")).longValue()!=in.expectedVersion())throw new Problem(409,"STALE_VERSION","申請狀態已變更，請重新讀取");
  if(!"PENDING".equals(r.get("status")))throw new Problem(409,"NOT_PENDING","此申請已處理");
  String note=in.note()==null?null:in.note().strip();
  if(in.approve())lockTickets((UUID)owner.getFirst().get("order_id"));
  if(in.approve()&&db.queryForObject("SELECT count(*) FROM issued_ticket i JOIN refund_request r ON r.order_id=i.order_id WHERE r.id=? AND i.state='CHECKED_IN'",Long.class,id)>0)
   throw new Problem(409,"NOT_REFUNDABLE","已有票券入場，無法核准退票");
  if(in.approve())db.update("UPDATE refund_request SET status='APPROVED',review_note=?,reviewed_by=?,reviewed_at=clock_timestamp(),mock_refund_id=?,version=version+1 WHERE id=?",
   note==null||note.isEmpty()?null:note,who,UUID.randomUUID(),id);
  else{if(note==null||note.isEmpty())throw new Problem(400,"INVALID_INPUT","駁回需填寫原因");
   db.update("UPDATE refund_request SET status='REJECTED',review_note=?,reviewed_by=?,reviewed_at=clock_timestamp(),version=version+1 WHERE id=?",note,who,id);}
  return one(id);
 }
}
