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
  return command(actor,request,action,scope,input,type,v->v instanceof Event e?e.id():v instanceof Session s?s.id():((Ticket)v).id(),operation);
 }
 /** 同一套 catalog_guard／catalog_command 原命令重送與稽核，供同套件的場館、演出者、活動檔案使用。 */
 public <T> T command(String actor,UUID request,String action,UUID scope,Object input,Class<T> type,java.util.function.Function<T,UUID> resourceOf,Supplier<T> operation) {
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
  T value=operation.get();UUID resource=resourceOf.apply(value);
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
 private static final java.time.ZoneId TAIPEI=java.time.ZoneId.of("Asia/Taipei");
 /** 公開活動搜尋：關鍵字比對標題與場館，日期以台北時間的場次開演日為準。 */
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public Page<PublicEvent> search(String q,java.time.LocalDate from,java.time.LocalDate to,PublicSort sort,boolean onSale,int page,int size) {
  return search(q,null,null,null,from,to,sort,onSale,page,size);
 }
 /** category／venue／performer 為 slug；不存在的 slug 只會回傳空結果。 */
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public Page<PublicEvent> search(String q,String category,String venue,String performer,java.time.LocalDate from,java.time.LocalDate to,PublicSort sort,boolean onSale,int page,int size) {
  if(page<1||page>100000||size<1||size>20)throw new Problem(400,"INVALID_INPUT","分頁超出範圍");
  String keyword=q==null?"":q.strip();
  if(keyword.length()>60)throw new Problem(400,"INVALID_INPUT","搜尋字數最多 60 字");
  if(from!=null&&to!=null&&to.isBefore(from))throw new Problem(400,"INVALID_INPUT","結束日期不可早於開始日期");
  OffsetDateTime now=OffsetDateTime.now();
  List<Object> args=new ArrayList<>();args.add(now);args.add(now);
  StringBuilder where=new StringBuilder(" WHERE e.status='PUBLISHED'");
  if(!keyword.isEmpty()){
   String like="%"+keyword.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
   where.append(" AND (e.title ILIKE ? ESCAPE '\\' OR e.venue ILIKE ? ESCAPE '\\')");args.add(like);args.add(like);
  }
  if(from!=null||to!=null){
   where.append(" AND EXISTS(SELECT 1 FROM event_session d WHERE d.event_id=e.id");
   if(from!=null){where.append(" AND d.starts_at>=?");args.add(from.atStartOfDay(TAIPEI).toOffsetDateTime());}
   if(to!=null){where.append(" AND d.starts_at<?");args.add(to.plusDays(1).atStartOfDay(TAIPEI).toOffsetDateTime());}
   where.append(")");
  }
  if(onSale)where.append(" AND coalesce(a.selling,false)");
  for(String slug:new String[]{category,venue,performer})if(slug!=null&&slug.length()>80)throw new Problem(400,"INVALID_INPUT","篩選條件過長");
  if(category!=null){where.append(" AND c.slug=?");args.add(category);}
  if(venue!=null){where.append(" AND EXISTS(SELECT 1 FROM venue v WHERE v.id=ep.venue_id AND v.slug=?)");args.add(venue);}
  if(performer!=null){where.append(" AND EXISTS(SELECT 1 FROM event_performer x JOIN performer f ON f.id=x.performer_id WHERE x.event_id=e.id AND f.slug=?)");args.add(performer);}
  String from_=" FROM event e LEFT JOIN LATERAL (SELECT min(s.starts_at) first_starts_at,max(s.starts_at) last_starts_at,count(*) session_count,"
   +"bool_or(s.sales_start<=? AND ?<s.sales_end) selling FROM event_session s WHERE s.event_id=e.id) a ON true"
   +" LEFT JOIN LATERAL (SELECT min(t.price_twd) min_price,max(t.price_twd) max_price FROM ticket_type t JOIN event_session s ON s.id=t.session_id WHERE s.event_id=e.id) p ON true"
   +" LEFT JOIN event_profile ep ON ep.event_id=e.id LEFT JOIN category c ON c.id=ep.category_id";
  String order=switch(sort==null?PublicSort.NEWEST:sort){
   case DATE->" ORDER BY a.first_starts_at ASC NULLS LAST,e.id";
   case PRICE->" ORDER BY p.min_price ASC NULLS LAST,e.id";
   case NEWEST->" ORDER BY e.created_at DESC,e.id";
  };
  long total=db.queryForObject("SELECT count(*)"+from_+where,Long.class,args.toArray());
  List<Object> pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add((page-1)*size);
  List<PublicEvent> items=db.query("SELECT e.*,a.first_starts_at,a.last_starts_at,a.session_count,a.selling,p.min_price,p.max_price,c.slug category_slug,c.name category_name,ep.image_url"+from_+where+order+" LIMIT ? OFFSET ?",
   (r,n)->new PublicEvent(r.getObject("id",UUID.class),r.getString("slug"),r.getString("title"),r.getString("description"),r.getString("venue"),
    r.getString("status"),r.getLong("version"),r.getObject("first_starts_at",OffsetDateTime.class),r.getObject("last_starts_at",OffsetDateTime.class),
    r.getInt("session_count"),(Integer)r.getObject("min_price"),(Integer)r.getObject("max_price"),r.getBoolean("selling"),
    r.getString("category_slug"),r.getString("category_name"),r.getString("image_url")),pageArgs.toArray());
  return new Page<>(items,total,page,size);
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
