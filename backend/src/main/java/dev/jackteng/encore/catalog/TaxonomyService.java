package dev.jackteng.encore.catalog;
import dev.jackteng.encore.catalog.Models.Page;
import dev.jackteng.encore.catalog.TaxonomyModels.*;
import dev.jackteng.encore.common.ApiErrors.Problem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service
public class TaxonomyService {
 private final JdbcTemplate db;private final CatalogService catalog;
 public TaxonomyService(JdbcTemplate db,CatalogService catalog){this.db=db;this.catalog=catalog;}
 private static final RowMapper<Venue> venueRow=(r,n)->new Venue(r.getObject("id",UUID.class),r.getString("slug"),r.getString("name"),
  r.getString("city"),r.getString("address"),r.getString("description"),r.getString("image_url"),r.getLong("version"));
 private static final RowMapper<Performer> performerRow=(r,n)->new Performer(r.getObject("id",UUID.class),r.getString("slug"),
  r.getString("name"),r.getString("description"),r.getString("image_url"),r.getLong("version"));
 private static <T> T one(List<T> rows){if(rows.isEmpty())throw new Problem(404,"NOT_FOUND","找不到此資料");return rows.getFirst();}
 private static void bounded(int page,int size,int max){if(page<1||page>100000||size<1||size>max)throw new Problem(400,"INVALID_INPUT","分頁超出範圍");}
 private static String blank(String s){return s==null||s.isBlank()?null:s.strip();}

 /** 分類與已發布活動數；後台與前台共用。 */
 @Transactional(readOnly=true) public List<Category> categories(){
  return db.query("SELECT c.*,(SELECT count(*) FROM event_profile p JOIN event e ON e.id=p.event_id WHERE p.category_id=c.id AND e.status='PUBLISHED') n"
   +" FROM category c ORDER BY c.sort_order,c.slug",(r,n)->new Category(r.getObject("id",UUID.class),r.getString("slug"),r.getString("name"),r.getInt("sort_order"),r.getLong("n")));
 }
 @Transactional(readOnly=true) public Page<Venue> venues(int page,int size){bounded(page,size,50);
  return new Page<>(db.query("SELECT * FROM venue ORDER BY name,id LIMIT ? OFFSET ?",venueRow,size,(page-1)*size),db.queryForObject("SELECT count(*) FROM venue",Long.class),page,size);}
 @Transactional(readOnly=true) public Page<Performer> performers(int page,int size){bounded(page,size,50);
  return new Page<>(db.query("SELECT * FROM performer ORDER BY name,id LIMIT ? OFFSET ?",performerRow,size,(page-1)*size),db.queryForObject("SELECT count(*) FROM performer",Long.class),page,size);}
 @Transactional(readOnly=true) public Venue venue(String slug){return one(db.query("SELECT * FROM venue WHERE slug=?",venueRow,slug));}
 @Transactional(readOnly=true) public Performer performer(String slug){return one(db.query("SELECT * FROM performer WHERE slug=?",performerRow,slug));}

 @Transactional public Venue createVenue(String actor,VenueInput in){
  return catalog.command(actor,in.requestId(),"VENUE_CREATE",null,in,Venue.class,Venue::id,()->{
   if(in.expectedVersion()!=0)throw new Problem(400,"INVALID_INPUT","新增資料 expectedVersion 必須為 0");UUID id=UUID.randomUUID();
   db.update("INSERT INTO venue(id,slug,name,city,address,description,image_url) VALUES(?,?,?,?,?,?,?)",id,in.slug(),in.name().strip(),in.city().strip(),in.address().strip(),in.description(),blank(in.imageUrl()));
   return one(db.query("SELECT * FROM venue WHERE id=?",venueRow,id));});
 }
 @Transactional public Venue updateVenue(String actor,UUID id,VenueInput in){
  return catalog.command(actor,in.requestId(),"VENUE_UPDATE",id,in,Venue.class,Venue::id,()->{
   Venue v=one(db.query("SELECT * FROM venue WHERE id=? FOR UPDATE",venueRow,id));stale(v.version(),in.expectedVersion());
   db.update("UPDATE venue SET slug=?,name=?,city=?,address=?,description=?,image_url=?,version=version+1 WHERE id=?",in.slug(),in.name().strip(),in.city().strip(),in.address().strip(),in.description(),blank(in.imageUrl()),id);
   return one(db.query("SELECT * FROM venue WHERE id=?",venueRow,id));});
 }
 @Transactional public Performer createPerformer(String actor,PerformerInput in){
  return catalog.command(actor,in.requestId(),"PERFORMER_CREATE",null,in,Performer.class,Performer::id,()->{
   if(in.expectedVersion()!=0)throw new Problem(400,"INVALID_INPUT","新增資料 expectedVersion 必須為 0");UUID id=UUID.randomUUID();
   db.update("INSERT INTO performer(id,slug,name,description,image_url) VALUES(?,?,?,?,?)",id,in.slug(),in.name().strip(),in.description(),blank(in.imageUrl()));
   return one(db.query("SELECT * FROM performer WHERE id=?",performerRow,id));});
 }
 @Transactional public Performer updatePerformer(String actor,UUID id,PerformerInput in){
  return catalog.command(actor,in.requestId(),"PERFORMER_UPDATE",id,in,Performer.class,Performer::id,()->{
   Performer p=one(db.query("SELECT * FROM performer WHERE id=? FOR UPDATE",performerRow,id));stale(p.version(),in.expectedVersion());
   db.update("UPDATE performer SET slug=?,name=?,description=?,image_url=?,version=version+1 WHERE id=?",in.slug(),in.name().strip(),in.description(),blank(in.imageUrl()),id);
   return one(db.query("SELECT * FROM performer WHERE id=?",performerRow,id));});
 }
 private static void stale(long actual,long expected){if(actual!=expected)throw new Problem(409,"STALE_VERSION","版本已變更，請重新讀取");}

 /** 活動檔案；publicOnly 時只回傳已發布活動。尚未設定時 version 為 0。 */
 @Transactional(readOnly=true) public Profile profile(UUID eventId,boolean publicOnly){
  var status=db.queryForList("SELECT status FROM event WHERE id=?",String.class,eventId);
  if(status.isEmpty()||publicOnly&&!status.getFirst().equals("PUBLISHED"))throw new Problem(404,"NOT_FOUND","找不到此活動");
  return load(eventId);
 }
 private Profile load(UUID eventId){
  var rows=db.queryForList("SELECT p.version,p.image_url,c.id cid,c.slug cslug,c.name cname,p.venue_id FROM event_profile p LEFT JOIN category c ON c.id=p.category_id WHERE p.event_id=?",eventId);
  List<Performer> performers=db.query("SELECT f.* FROM event_performer ep JOIN performer f ON f.id=ep.performer_id WHERE ep.event_id=? ORDER BY ep.position",performerRow,eventId);
  if(rows.isEmpty())return new Profile(eventId,null,null,performers,null,0);
  var r=rows.getFirst();UUID venueId=(UUID)r.get("venue_id");
  CategoryRef category=r.get("cid")==null?null:new CategoryRef((UUID)r.get("cid"),(String)r.get("cslug"),(String)r.get("cname"));
  Venue venue=venueId==null?null:one(db.query("SELECT * FROM venue WHERE id=?",venueRow,venueId));
  return new Profile(eventId,category,venue,performers,(String)r.get("image_url"),((Number)r.get("version")).longValue());
 }
 @Transactional public Profile updateProfile(String actor,UUID eventId,ProfileInput in){
  return catalog.command(actor,in.requestId(),"EVENT_PROFILE_UPDATE",eventId,in,Profile.class,Profile::eventId,()->{
   var status=db.queryForList("SELECT status FROM event WHERE id=? FOR UPDATE",String.class,eventId);
   if(status.isEmpty())throw new Problem(404,"NOT_FOUND","找不到此活動");
   if(status.getFirst().equals("ARCHIVED"))throw new Problem(409,"TERMS_LOCKED","封存後不可修改");
   var current=db.queryForList("SELECT version FROM event_profile WHERE event_id=? FOR UPDATE",Long.class,eventId);
   stale(current.isEmpty()?0:current.getFirst(),in.expectedVersion());
   if(new HashSet<>(in.performerIds()).size()!=in.performerIds().size())throw new Problem(400,"INVALID_INPUT","演出者不可重複");
   if(in.categoryId()!=null&&db.queryForObject("SELECT count(*) FROM category WHERE id=?",Long.class,in.categoryId())==0)throw new Problem(400,"INVALID_INPUT","找不到此分類");
   if(in.venueId()!=null&&db.queryForObject("SELECT count(*) FROM venue WHERE id=?",Long.class,in.venueId())==0)throw new Problem(400,"INVALID_INPUT","找不到此場館");
   for(UUID p:in.performerIds())if(db.queryForObject("SELECT count(*) FROM performer WHERE id=?",Long.class,p)==0)throw new Problem(400,"INVALID_INPUT","找不到此演出者");
   if(current.isEmpty())db.update("INSERT INTO event_profile(event_id,category_id,venue_id,image_url) VALUES(?,?,?,?)",eventId,in.categoryId(),in.venueId(),blank(in.imageUrl()));
   else db.update("UPDATE event_profile SET category_id=?,venue_id=?,image_url=?,version=version+1 WHERE event_id=?",in.categoryId(),in.venueId(),blank(in.imageUrl()),eventId);
   db.update("DELETE FROM event_performer WHERE event_id=?",eventId);
   for(int i=0;i<in.performerIds().size();i++)db.update("INSERT INTO event_performer(event_id,performer_id,position) VALUES(?,?,?)",eventId,in.performerIds().get(i),i);
   return load(eventId);});
 }
}
