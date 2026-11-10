package dev.jackteng.encore.catalog;
import dev.jackteng.encore.catalog.Models.*;
import dev.jackteng.encore.common.ApiErrors.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
@Service
public class CatalogService {
 private final JdbcTemplate db; private final ObjectMapper json;private final boolean orderingEnabled;
 @org.springframework.beans.factory.annotation.Value("${ticket.admission.enabled:false}") private boolean waitingRoomEnabled;
 @org.springframework.beans.factory.annotation.Value("${ticket.purchases.enabled:false}") private boolean purchasesEnabled;
 @org.springframework.beans.factory.annotation.Value("${ticket.delivery.enabled:false}") private boolean deliveryEnabled;
 @org.springframework.beans.factory.annotation.Value("${ticket.delivery.publisher-enabled:false}") private boolean publisherEnabled;
 @org.springframework.beans.factory.annotation.Autowired private dev.jackteng.encore.orders.PreDebitSettings preDebit;
 public CatalogService(JdbcTemplate db,ObjectMapper json,@org.springframework.beans.factory.annotation.Value("${ticket.orders.enabled}") boolean orderingEnabled) {this.db=db;this.json=json;this.orderingEnabled=orderingEnabled;}
 private final RowMapper<Event> eventRow=(r,n)->new Event(r.getObject("id",UUID.class),r.getString("slug"),r.getString("title"),
  r.getString("description"),r.getString("venue"),r.getString("status"),r.getLong("version"));
 private final RowMapper<Ticket> ticketRow=(r,n)->new Ticket(r.getObject("id",UUID.class),r.getString("name"),r.getInt("price_twd"),
  r.getInt("capacity"),r.getInt("max_per_customer"),r.getLong("version"),r.getInt("capacity")-r.getInt("held")-r.getInt("sold"));
 private Session sessionRow(java.sql.ResultSet r) throws java.sql.SQLException {
  UUID id=r.getObject("id",UUID.class);
  OffsetDateTime start=r.getObject("sales_start",OffsetDateTime.class),end=r.getObject("sales_end",OffsetDateTime.class);
  OffsetDateTime now=OffsetDateTime.now();
  return new Session(id,r.getObject("event_id",UUID.class),r.getString("name"),r.getObject("starts_at",OffsetDateTime.class),start,end,
   r.getLong("version"),now.isBefore(start)?"UPCOMING":now.isBefore(end)?"SELLING":"CLOSED",
   db.query("SELECT * FROM ticket_type WHERE session_id=? ORDER BY id",ticketRow,id));
 }
 private Event event(UUID id) {return one(db.query("SELECT * FROM event WHERE id=?",eventRow,id));}
 private Session session(UUID id) {return one(db.query("SELECT * FROM event_session WHERE id=?",(r,n)->sessionRow(r),id));}
 private Ticket ticket(UUID id) {return one(db.query("SELECT * FROM ticket_type WHERE id=?",ticketRow,id));}
 private static <T> T one(List<T> rows) {if(rows.isEmpty())throw new Problem(404,"NOT_FOUND","找不到此資料");return rows.getFirst();}
 private static void draft(Event event) {if(!event.status().equals("DRAFT"))throw new Problem(409,"TERMS_LOCKED","發布或封存後條件不可修改");}
 private static void version(long actual,long expected) {if(actual!=expected)throw new Problem(409,"STALE_VERSION","版本已變更，請重新讀取");}
 private static void createVersion(long v) {if(v!=0)throw new Problem(400,"INVALID_INPUT","新增資料 expectedVersion 必須為 0");}
 private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Catalog encoding failed",e);}}
 private <T> T command(String actor,UUID request,String action,UUID scope,Object input,Class<T> type,Supplier<T> operation) {
  db.queryForObject("SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE",Integer.class);
  var staff=db.query("SELECT id FROM staff_account WHERE username=? AND enabled AND role='MANAGER' FOR UPDATE",
   (r,n)->r.getObject(1,UUID.class),actor);
  if(staff.isEmpty())throw new Problem(403,"FORBIDDEN","沒有管理權限");
  UUID actorId=staff.getFirst();String fingerprint;
  try{fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((action+":"+scope+":"+encode(input)).getBytes(StandardCharsets.UTF_8)));}
  catch(Exception e){throw new IllegalStateException(e);}
  var prior=db.queryForList("SELECT actor_id,fingerprint,result::text FROM catalog_command WHERE request_id=?",request);
  if(!prior.isEmpty()){
   var p=prior.getFirst();if(!actorId.equals(p.get("actor_id"))||!fingerprint.equals(p.get("fingerprint")))
    throw new Problem(409,"REQUEST_CONFLICT","此操作編號已使用，請核對原操作");
   try{return json.readValue((String)p.get("result"),type);}catch(Exception e){throw new IllegalStateException(e);}
  }
  T value=operation.get();UUID resource=value instanceof Event e?e.id():value instanceof Session s?s.id():((Ticket)value).id();
  db.update("INSERT INTO catalog_command(request_id,actor_id,action,resource_id,fingerprint,result) VALUES(?,?,?,?,?,?::jsonb)",
   request,actorId,action,resource,fingerprint,encode(value));
  return value;
 }
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public Page<Event> list(boolean publicOnly,int page,int size) {
  if(page<1||page>100000||size<1||size>20)throw new Problem(400,"INVALID_INPUT","分頁超出範圍");
  String filter=publicOnly?" WHERE status='PUBLISHED'":"";
  return new Page<>(db.query("SELECT * FROM event"+filter+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",eventRow,size,(page-1)*size),
   db.queryForObject("SELECT count(*) FROM event"+filter,Long.class),page,size);
 }
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public Detail detail(UUID id,boolean publicOnly) {
  Event e=event(id);if(publicOnly&&!e.status().equals("PUBLISHED"))throw new Problem(404,"NOT_FOUND","找不到此活動");
  boolean open=orderingEnabled&&e.status().equals("PUBLISHED");
  return new Detail(e,db.query("SELECT * FROM event_session WHERE event_id=? ORDER BY starts_at,id",(r,n)->sessionRow(r),id),open,waitingRoomEnabled&&open,open&&purchasesEnabled&&deliveryEnabled&&publisherEnabled,open&&preDebit!=null&&preDebit.enabled());
 }
 @Transactional public Event createEvent(String actor,EventInput in) {
  return command(actor,in.requestId(),"EVENT_CREATE",null,in,Event.class,()->{
   createVersion(in.expectedVersion());UUID id=UUID.randomUUID();
   db.update("INSERT INTO event(id,slug,title,description,venue) VALUES(?,?,?,?,?)",id,in.slug(),in.title().trim(),in.description(),in.venue().trim());return event(id);
  });
 }
 @Transactional public Event updateEvent(String actor,UUID id,EventInput in) {
  return command(actor,in.requestId(),"EVENT_UPDATE",id,in,Event.class,()->{
   Event e=event(id);draft(e);version(e.version(),in.expectedVersion());
   db.update("UPDATE event SET slug=?,title=?,description=?,venue=?,version=version+1 WHERE id=?",in.slug(),in.title().trim(),in.description(),in.venue().trim(),id);return event(id);
  });
 }
 @Transactional public Event transition(String actor,UUID id,Transition in,boolean publish) {
  return command(actor,in.requestId(),publish?"EVENT_PUBLISH":"EVENT_ARCHIVE",id,in,Event.class,()->{
   Event e=event(id);version(e.version(),in.expectedVersion());
   if(publish){draft(e);var sessions=detail(id,false).sessions();
    if(sessions.isEmpty()||sessions.stream().anyMatch(s->s.tickets().isEmpty()||!s.salesEnd().isAfter(OffsetDateTime.now())))
     throw new Problem(409,"INCOMPLETE_EVENT","請先建立有效場次與票種");
   }else if(e.status().equals("ARCHIVED"))throw new Problem(409,"TERMS_LOCKED","活動已封存");
   db.update("UPDATE event SET status=?,version=version+1 WHERE id=?",publish?"PUBLISHED":"ARCHIVED",id);return event(id);
  });
 }
 private static void times(SessionInput in) {
  if(!in.salesStart().isBefore(in.salesEnd())||in.salesEnd().isAfter(in.startsAt()))throw new Problem(400,"INVALID_INPUT","售票開始需早於結束，結束不得晚於活動時間");
 }
 @Transactional public Session createSession(String actor,UUID eventId,SessionInput in) {
  return command(actor,in.requestId(),"SESSION_CREATE",eventId,in,Session.class,()->{
   createVersion(in.expectedVersion());draft(event(eventId));times(in);
   if(db.queryForObject("SELECT count(*) FROM event_session WHERE event_id=?",Long.class,eventId)>=20)throw new Problem(409,"LIMIT_REACHED","每活動最多20場次");
   UUID id=UUID.randomUUID();db.update("INSERT INTO event_session(id,event_id,name,starts_at,sales_start,sales_end) VALUES(?,?,?,?,?,?)",id,eventId,in.name().trim(),in.startsAt(),in.salesStart(),in.salesEnd());return session(id);
  });
 }
 @Transactional public Session updateSession(String actor,UUID id,SessionInput in) {
  return command(actor,in.requestId(),"SESSION_UPDATE",id,in,Session.class,()->{
   Session s=session(id);draft(event(s.eventId()));version(s.version(),in.expectedVersion());times(in);
   db.update("UPDATE event_session SET name=?,starts_at=?,sales_start=?,sales_end=?,version=version+1 WHERE id=?",in.name().trim(),in.startsAt(),in.salesStart(),in.salesEnd(),id);return session(id);
  });
 }
 private static void ticketRules(TicketInput in) {if(in.maxPerCustomer()>in.capacity())throw new Problem(400,"INVALID_INPUT","限購不得大於票額");}
 @Transactional public Ticket createTicket(String actor,UUID sessionId,TicketInput in) {
  return command(actor,in.requestId(),"TICKET_CREATE",sessionId,in,Ticket.class,()->{
   createVersion(in.expectedVersion());draft(event(session(sessionId).eventId()));ticketRules(in);
   if(db.queryForObject("SELECT count(*) FROM ticket_type WHERE session_id=?",Long.class,sessionId)>=10)throw new Problem(409,"LIMIT_REACHED","每場次最多10票種");
   UUID id=UUID.randomUUID();db.update("INSERT INTO ticket_type(id,session_id,name,price_twd,capacity,max_per_customer) VALUES(?,?,?,?,?,?)",id,sessionId,in.name().trim(),in.priceTwd(),in.capacity(),in.maxPerCustomer());return ticket(id);
  });
 }
 @Transactional public Ticket updateTicket(String actor,UUID id,TicketInput in) {
  return command(actor,in.requestId(),"TICKET_UPDATE",id,in,Ticket.class,()->{
   Ticket t=ticket(id);UUID sessionId=db.queryForObject("SELECT session_id FROM ticket_type WHERE id=?",UUID.class,id);
   draft(event(session(sessionId).eventId()));version(t.version(),in.expectedVersion());ticketRules(in);
   db.update("UPDATE ticket_type SET name=?,price_twd=?,capacity=?,max_per_customer=?,version=version+1 WHERE id=?",in.name().trim(),in.priceTwd(),in.capacity(),in.maxPerCustomer(),id);return ticket(id);
  });
 }
 @Transactional(readOnly=true) public Page<Audit> audit(int page,int size) {
  if(page<1||page>100000||size<1||size>20)throw new Problem(400,"INVALID_INPUT","分頁超出範圍");
  return new Page<>(db.query("SELECT request_id,action,resource_id,created_at FROM catalog_command ORDER BY created_at DESC,request_id LIMIT ? OFFSET ?",
   (r,n)->new Audit(r.getObject(1,UUID.class),r.getString(2),r.getObject(3,UUID.class),r.getObject(4,OffsetDateTime.class)),size,(page-1)*size),
   db.queryForObject("SELECT count(*) FROM catalog_command",Long.class),page,size);
 }
}
