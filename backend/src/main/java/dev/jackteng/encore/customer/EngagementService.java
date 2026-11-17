package dev.jackteng.encore.customer;
import dev.jackteng.encore.catalog.Models.Page;
import dev.jackteng.encore.common.ApiErrors.Problem;
import dev.jackteng.encore.customer.CustomerService.Principal;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/** 會員收藏、開賣提醒與站內通知。只讀寫本人資料；owner 一律取自已驗證身分。 */
@Service
public class EngagementService {
 public record EventFlags(UUID eventId,boolean favorite,boolean reminder,boolean reminderAvailable) {}
 public record FavoriteItem(UUID eventId,String title,String venue,OffsetDateTime firstStartsAt,Integer minPriceTwd,boolean selling,OffsetDateTime savedAt) {}
 public record Notification(UUID id,String kind,String title,String body,String link,OffsetDateTime createdAt,OffsetDateTime readAt) {}
 public record Summary(long unread) {}
 public record Toggle(@NotNull Boolean on) {}
 public record MarkRead(@NotNull @Size(max=100) List<@NotNull UUID> ids,boolean all) {}
 public static final int MAX_FAVORITES=500;
 private final JdbcTemplate db;
 public EngagementService(JdbcTemplate db){this.db=db;}
 private static void bounded(int page,int size){if(page<1||page>100000||size<1||size>50)throw new Problem(400,"INVALID_INPUT","分頁超出範圍");}

 private void requirePublished(UUID eventId){
  var s=db.queryForList("SELECT status FROM event WHERE id=?",String.class,eventId);
  if(s.isEmpty()||!s.getFirst().equals("PUBLISHED"))throw new Problem(404,"NOT_FOUND","找不到此活動");
 }
 private boolean reminderAvailable(UUID eventId,OffsetDateTime now){
  return db.queryForObject("SELECT EXISTS(SELECT 1 FROM event_session WHERE event_id=? AND sales_start>?)",Boolean.class,eventId,now);
 }
 private EventFlags flags(UUID customerId,UUID eventId,OffsetDateTime now){
  boolean fav=db.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_favorite WHERE customer_id=? AND event_id=?)",Boolean.class,customerId,eventId);
  boolean rem=db.queryForObject("SELECT EXISTS(SELECT 1 FROM sale_reminder WHERE customer_id=? AND event_id=? AND notified_at IS NULL)",Boolean.class,customerId,eventId);
  return new EventFlags(eventId,fav,rem,reminderAvailable(eventId,now));
 }
 private OffsetDateTime now(){return db.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class);}

 @Transactional public EventFlags flags(Principal p,UUID eventId){CustomerAccess.authorize(db,p);requirePublished(eventId);return flags(p.id(),eventId,now());}

 /** 設定式操作（不是切換），重送相同請求結果相同，不需要操作編號。 */
 @Transactional public EventFlags setFavorite(Principal p,UUID eventId,boolean on){
  CustomerAccess.authorize(db,p);
  if(on){
   requirePublished(eventId);
   if(db.queryForObject("SELECT count(*) FROM customer_favorite f JOIN event e ON e.id=f.event_id WHERE f.customer_id=? AND e.status='PUBLISHED'",Long.class,p.id())>=MAX_FAVORITES
    &&db.queryForObject("SELECT count(*) FROM customer_favorite WHERE customer_id=? AND event_id=?",Long.class,p.id(),eventId)==0)
    throw new Problem(409,"LIMIT_REACHED","收藏最多 "+MAX_FAVORITES+" 個活動");
   db.update("INSERT INTO customer_favorite(customer_id,event_id) VALUES(?,?) ON CONFLICT DO NOTHING",p.id(),eventId);
  }else db.update("DELETE FROM customer_favorite WHERE customer_id=? AND event_id=?",p.id(),eventId);
  return flags(p.id(),eventId,now());
 }
 @Transactional public EventFlags setReminder(Principal p,UUID eventId,boolean on){
  CustomerAccess.authorize(db,p);OffsetDateTime now=now();
  if(on){
   requirePublished(eventId);
   if(db.queryForObject("SELECT EXISTS(SELECT 1 FROM sale_reminder WHERE customer_id=? AND event_id=? AND notified_at IS NULL)",Boolean.class,p.id(),eventId))
    return flags(p.id(),eventId,now);
   OffsetDateTime due=db.queryForObject("SELECT min(sales_start) FROM event_session WHERE event_id=? AND sales_start>?",OffsetDateTime.class,eventId,now);
   if(due==null)throw new Problem(409,"ALREADY_ON_SALE","此活動沒有尚未開賣的場次");
   db.update("INSERT INTO sale_reminder(customer_id,event_id,due_at) VALUES(?,?,?) ON CONFLICT(customer_id,event_id) DO UPDATE SET due_at=EXCLUDED.due_at,created_at=now(),notified_at=NULL",p.id(),eventId,due);
  }else db.update("DELETE FROM sale_reminder WHERE customer_id=? AND event_id=?",p.id(),eventId);
  return flags(p.id(),eventId,now);
 }

 @Transactional public Page<FavoriteItem> favorites(Principal p,int page,int size){
  bounded(page,size);CustomerAccess.authorize(db,p);OffsetDateTime now=now();
  long total=db.queryForObject("SELECT count(*) FROM customer_favorite f JOIN event e ON e.id=f.event_id WHERE f.customer_id=? AND e.status='PUBLISHED'",Long.class,p.id());
  var items=db.query("SELECT e.id,e.title,e.venue,f.created_at,(SELECT min(starts_at) FROM event_session s WHERE s.event_id=e.id) first_starts_at,"
   +"(SELECT min(t.price_twd) FROM ticket_type t JOIN event_session s ON s.id=t.session_id WHERE s.event_id=e.id) min_price,"
   +"EXISTS(SELECT 1 FROM event_session s WHERE s.event_id=e.id AND s.sales_start<=? AND ?<s.sales_end) selling"
   +" FROM customer_favorite f JOIN event e ON e.id=f.event_id WHERE f.customer_id=? AND e.status='PUBLISHED' ORDER BY f.created_at DESC,e.id LIMIT ? OFFSET ?",
   (r,n)->new FavoriteItem(r.getObject("id",UUID.class),r.getString("title"),r.getString("venue"),r.getObject("first_starts_at",OffsetDateTime.class),
    (Integer)r.getObject("min_price"),r.getBoolean("selling"),r.getObject("created_at",OffsetDateTime.class)),now,now,p.id(),size,(page-1)*size);
  return new Page<>(items,total,page,size);
 }

 /** 將已到開賣時間的提醒轉成通知；與讀取在同一交易，dedupe_key 保證多實例同時讀取也只建立一次。 */
 private void materialize(UUID customerId,OffsetDateTime now){
  record Due(UUID eventId,String title,OffsetDateTime dueAt) {}
  var due=db.query("SELECT r.event_id,e.title,r.due_at FROM sale_reminder r JOIN event e ON e.id=r.event_id WHERE r.customer_id=? AND r.notified_at IS NULL AND e.status='PUBLISHED'"
   +" AND r.due_at<=? ORDER BY r.event_id FOR UPDATE OF r",
   (r,n)->new Due(r.getObject(1,UUID.class),r.getString(2),r.getObject(3,OffsetDateTime.class)),customerId,now);
  for(var d:due){
   db.update("INSERT INTO customer_notification(id,customer_id,kind,title,body,link,dedupe_key) VALUES(?,?,'SALE_STARTED',?,?,?,?) ON CONFLICT(customer_id,dedupe_key) DO NOTHING",
    UUID.randomUUID(),customerId,"開賣了："+truncate(d.title(),150),"你設定提醒的場次已開始售票，名額有限，請盡早購票。","/events/"+d.eventId(),"sale:"+d.eventId()+":"+d.dueAt().toInstant().toEpochMilli());
   db.update("UPDATE sale_reminder SET notified_at=? WHERE customer_id=? AND event_id=?",now,customerId,d.eventId());
  }
 }
 private static String truncate(String s,int n){return s.length()<=n?s:s.substring(0,n);}
 @Transactional public Page<Notification> notifications(Principal p,int page,int size){
  bounded(page,size);CustomerAccess.authorize(db,p);materialize(p.id(),now());
  long total=db.queryForObject("SELECT count(*) FROM customer_notification WHERE customer_id=?",Long.class,p.id());
  var items=db.query("SELECT * FROM customer_notification WHERE customer_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
   (r,n)->new Notification(r.getObject("id",UUID.class),r.getString("kind"),r.getString("title"),r.getString("body"),r.getString("link"),
    r.getObject("created_at",OffsetDateTime.class),r.getObject("read_at",OffsetDateTime.class)),p.id(),size,(page-1)*size);
  return new Page<>(items,total,page,size);
 }
 @Transactional public Summary summary(Principal p){
  CustomerAccess.authorize(db,p);materialize(p.id(),now());
  return new Summary(db.queryForObject("SELECT count(*) FROM customer_notification WHERE customer_id=? AND read_at IS NULL",Long.class,p.id()));
 }
 @Transactional public Summary markRead(Principal p,MarkRead in){
  CustomerAccess.authorize(db,p);
  if(in.all())db.update("UPDATE customer_notification SET read_at=clock_timestamp() WHERE customer_id=? AND read_at IS NULL",p.id());
  else for(UUID id:in.ids())db.update("UPDATE customer_notification SET read_at=clock_timestamp() WHERE customer_id=? AND id=? AND read_at IS NULL",p.id(),id);
  return new Summary(db.queryForObject("SELECT count(*) FROM customer_notification WHERE customer_id=? AND read_at IS NULL",Long.class,p.id()));
 }
}
