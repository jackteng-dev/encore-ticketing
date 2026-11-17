package dev.jackteng.encore.catalog;
import dev.jackteng.encore.catalog.Models.Page;
import dev.jackteng.encore.common.ApiErrors.Problem;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
/**
 * 主辦方銷售報表（唯讀）。票額以 PostgreSQL ticket_type.held／sold 為準，營收只計已付款（PAID）訂單。
 * 只輸出彙總數字，不含會員個資。
 */
@Service
public class SalesReportService {
 public record TicketReport(UUID ticketId,String name,int priceTwd,int capacity,int held,int sold,int available,
  long paidOrders,long paidQuantity,long paidRevenueTwd,long pendingOrders,long cancelledOrders,long expiredOrders,long issuedTickets,long checkedIn) {}
 public record SessionReport(UUID sessionId,String name,OffsetDateTime startsAt,OffsetDateTime salesStart,OffsetDateTime salesEnd,List<TicketReport> tickets) {}
 public record DailySales(LocalDate date,long paidOrders,long paidQuantity,long revenueTwd) {}
 public record Totals(long capacity,long held,long sold,long available,long paidOrders,long revenueTwd,long issuedTickets,long checkedIn) {}
 public record EventReport(UUID eventId,String title,String status,OffsetDateTime generatedAt,Totals totals,List<SessionReport> sessions,List<DailySales> daily) {}
 public record EventSummary(UUID eventId,String title,String status,long capacity,long sold,long held,long revenueTwd) {}
 private final JdbcTemplate db;
 public SalesReportService(JdbcTemplate db){this.db=db;}

 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Page<EventSummary> events(int page,int size){
  if(page<1||page>100000||size<1||size>50)throw new Problem(400,"INVALID_INPUT","分頁超出範圍");
  long total=db.queryForObject("SELECT count(*) FROM event",Long.class);
  // 先取本頁活動，再對這些活動的票種與已付款訂單各彙總一次（避免每個活動各掃一次訂單）。
  var items=db.query("WITH pe AS (SELECT id,title,status,created_at FROM event ORDER BY created_at DESC,id LIMIT ? OFFSET ?),"
   +" tt AS (SELECT s.event_id,t.id,t.capacity,t.sold,t.held FROM ticket_type t JOIN event_session s ON s.id=t.session_id WHERE s.event_id IN (SELECT id FROM pe)),"
   +" inv AS (SELECT event_id,sum(capacity) capacity,sum(sold) sold,sum(held) held FROM tt GROUP BY event_id),"
   +" rev AS (SELECT tt.event_id,sum(o.total_twd) revenue FROM ticket_order o JOIN tt ON tt.id=o.ticket_id WHERE o.status='PAID' GROUP BY tt.event_id)"
   +" SELECT pe.id,pe.title,pe.status,coalesce(inv.capacity,0) capacity,coalesce(inv.sold,0) sold,coalesce(inv.held,0) held,coalesce(rev.revenue,0) revenue"
   +" FROM pe LEFT JOIN inv ON inv.event_id=pe.id LEFT JOIN rev ON rev.event_id=pe.id ORDER BY pe.created_at DESC,pe.id",
   (r,n)->new EventSummary(r.getObject("id",UUID.class),r.getString("title"),r.getString("status"),r.getLong("capacity"),r.getLong("sold"),r.getLong("held"),r.getLong("revenue")),
   size,(page-1)*size);
  return new Page<>(items,total,page,size);
 }

 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public EventReport event(UUID eventId){
  var head=db.queryForList("SELECT title,status FROM event WHERE id=?",eventId);
  if(head.isEmpty())throw new Problem(404,"NOT_FOUND","找不到此活動");
  OffsetDateTime now=db.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class);
  Map<UUID,List<TicketReport>> byTicketSession=new LinkedHashMap<>();
  // 訂單與出票各彙總一次（GROUP BY ticket_id）後再與票種 join，避免每個票種各掃一次訂單。
  db.query("WITH t AS (SELECT t.*,s.starts_at s_starts FROM ticket_type t JOIN event_session s ON s.id=t.session_id WHERE s.event_id=?),"
   +" o AS (SELECT ticket_id,count(*) FILTER (WHERE status='PAID') paid_orders,coalesce(sum(quantity) FILTER (WHERE status='PAID'),0) paid_qty,"
   +"coalesce(sum(total_twd) FILTER (WHERE status='PAID'),0) paid_rev,count(*) FILTER (WHERE status='PENDING_PAYMENT') pending,"
   +"count(*) FILTER (WHERE status='CANCELLED') cancelled,count(*) FILTER (WHERE status='EXPIRED') expired"
   +" FROM ticket_order WHERE ticket_id IN (SELECT id FROM t) GROUP BY ticket_id),"
   +" i AS (SELECT o.ticket_id,count(*) FILTER (WHERE i.state<>'REVOKED') issued,count(*) FILTER (WHERE i.state='CHECKED_IN') checked"
   +" FROM issued_ticket i JOIN ticket_order o ON o.id=i.order_id WHERE o.ticket_id IN (SELECT id FROM t) GROUP BY o.ticket_id)"
   +" SELECT t.*,coalesce(o.paid_orders,0) paid_orders,coalesce(o.paid_qty,0) paid_qty,coalesce(o.paid_rev,0) paid_rev,coalesce(o.pending,0) pending,"
   +"coalesce(o.cancelled,0) cancelled,coalesce(o.expired,0) expired,coalesce(i.issued,0) issued,coalesce(i.checked,0) checked"
   +" FROM t LEFT JOIN o ON o.ticket_id=t.id LEFT JOIN i ON i.ticket_id=t.id ORDER BY t.s_starts,t.session_id,t.price_twd DESC,t.id",
   rs->{UUID sid=rs.getObject("session_id",UUID.class);int cap=rs.getInt("capacity"),held=rs.getInt("held"),sold=rs.getInt("sold");
    byTicketSession.computeIfAbsent(sid,k->new ArrayList<>()).add(new TicketReport(rs.getObject("id",UUID.class),rs.getString("name"),rs.getInt("price_twd"),
     cap,held,sold,cap-held-sold,rs.getLong("paid_orders"),rs.getLong("paid_qty"),rs.getLong("paid_rev"),rs.getLong("pending"),rs.getLong("cancelled"),
     rs.getLong("expired"),rs.getLong("issued"),rs.getLong("checked")));},eventId);
  var sessions=db.query("SELECT * FROM event_session WHERE event_id=? ORDER BY starts_at,id",(r,n)->{UUID sid=r.getObject("id",UUID.class);
   return new SessionReport(sid,r.getString("name"),r.getObject("starts_at",OffsetDateTime.class),r.getObject("sales_start",OffsetDateTime.class),
    r.getObject("sales_end",OffsetDateTime.class),byTicketSession.getOrDefault(sid,List.of()));},eventId);
  var all=sessions.stream().flatMap(s->s.tickets().stream()).toList();
  var totals=new Totals(all.stream().mapToLong(TicketReport::capacity).sum(),all.stream().mapToLong(TicketReport::held).sum(),
   all.stream().mapToLong(TicketReport::sold).sum(),all.stream().mapToLong(TicketReport::available).sum(),all.stream().mapToLong(TicketReport::paidOrders).sum(),
   all.stream().mapToLong(TicketReport::paidRevenueTwd).sum(),all.stream().mapToLong(TicketReport::issuedTickets).sum(),all.stream().mapToLong(TicketReport::checkedIn).sum());
  // 每日已付款（以付款確認時間 closed_at 的台北日期計）
  var daily=db.query("SELECT (o.closed_at AT TIME ZONE 'Asia/Taipei')::date d,count(*) n,sum(o.quantity) q,sum(o.total_twd) rev FROM ticket_order o"
   +" JOIN ticket_type t ON t.id=o.ticket_id JOIN event_session s ON s.id=t.session_id WHERE s.event_id=? AND o.status='PAID' GROUP BY 1 ORDER BY 1",
   (r,n)->new DailySales(r.getObject("d",LocalDate.class),r.getLong("n"),r.getLong("q"),r.getLong("rev")),eventId);
  return new EventReport(eventId,(String)head.getFirst().get("title"),(String)head.getFirst().get("status"),now,totals,sessions,daily);
 }
}
