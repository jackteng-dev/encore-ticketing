package dev.jackteng.encore.catalog;
import dev.jackteng.encore.common.ApiErrors.Problem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/** 場次區域座位圖（版面資料）。票額仍以 ticket_type 與既有保票流程為準。 */
@Service
public class SeatMapService {
 public record Zone(@NotNull UUID ticketTypeId,@NotBlank @Size(max=40) String label,
  @Min(0) @Max(99) int x,@Min(0) @Max(99) int y,@Min(1) @Max(100) int w,@Min(1) @Max(100) int h) {}
 /** id 等同 sessionId，讓後台共用的原命令核對（需要 id 與 version）可沿用。 */
 @com.fasterxml.jackson.annotation.JsonIgnoreProperties(value="id",allowGetters=true)
 public record SeatMap(UUID sessionId,String stageLabel,List<Zone> zones,long version) {
  @com.fasterxml.jackson.annotation.JsonProperty("id") public UUID id(){return sessionId;}
 }
 public record SeatMapInput(@NotNull UUID requestId,@NotNull @Min(0) Long expectedVersion,@NotBlank @Size(max=40) String stageLabel,
  @NotNull @Size(max=10) List<@NotNull @Valid Zone> zones) {}
 private final JdbcTemplate db;private final CatalogService catalog;
 public SeatMapService(JdbcTemplate db,CatalogService catalog){this.db=db;this.catalog=catalog;}

 private SeatMap load(UUID sessionId){
  var head=db.queryForList("SELECT stage_label,version FROM seat_map WHERE session_id=?",sessionId);
  if(head.isEmpty())return new SeatMap(sessionId,"舞台",List.of(),0);
  var zones=db.query("SELECT * FROM seat_zone WHERE session_id=? ORDER BY y,x,ticket_type_id",(r,n)->new Zone(r.getObject("ticket_type_id",UUID.class),
   r.getString("label"),r.getInt("x"),r.getInt("y"),r.getInt("w"),r.getInt("h")),sessionId);
  return new SeatMap(sessionId,(String)head.getFirst().get("stage_label"),zones,((Number)head.getFirst().get("version")).longValue());
 }
 private String eventStatus(UUID sessionId){
  var s=db.queryForList("SELECT e.status FROM event_session s JOIN event e ON e.id=s.event_id WHERE s.id=?",String.class,sessionId);
  if(s.isEmpty())throw new Problem(404,"NOT_FOUND","找不到此場次");
  return s.getFirst();
 }
 /** 已發布活動的所有場次座位圖（未設定的場次不列出）。 */
 @Transactional(readOnly=true) public List<SeatMap> forEvent(UUID eventId){
  var s=db.queryForList("SELECT status FROM event WHERE id=?",String.class,eventId);
  if(s.isEmpty()||!s.getFirst().equals("PUBLISHED"))throw new Problem(404,"NOT_FOUND","找不到此活動");
  return db.queryForList("SELECT m.session_id FROM seat_map m JOIN event_session s ON s.id=m.session_id WHERE s.event_id=? ORDER BY s.starts_at,s.id",UUID.class,eventId)
   .stream().map(this::load).toList();
 }
 @Transactional(readOnly=true) public SeatMap forSession(UUID sessionId){eventStatus(sessionId);return load(sessionId);}

 @Transactional public SeatMap update(String actor,UUID sessionId,SeatMapInput in){
  return catalog.command(actor,in.requestId(),"SEAT_MAP_UPDATE",sessionId,in,SeatMap.class,SeatMap::sessionId,()->{
   // 已由 catalog_guard 序列化；不鎖 event_session／ticket_type，避免與購票熱路徑競爭。
   if(eventStatus(sessionId).equals("ARCHIVED"))throw new Problem(409,"TERMS_LOCKED","封存後不可修改");
   var current=db.queryForList("SELECT version FROM seat_map WHERE session_id=? FOR UPDATE",Long.class,sessionId);
   if((current.isEmpty()?0L:current.getFirst())!=in.expectedVersion())throw new Problem(409,"STALE_VERSION","版本已變更，請重新讀取");
   validate(sessionId,in.zones());
   if(current.isEmpty())db.update("INSERT INTO seat_map(session_id,stage_label) VALUES(?,?)",sessionId,in.stageLabel().strip());
   else db.update("UPDATE seat_map SET stage_label=?,version=version+1 WHERE session_id=?",in.stageLabel().strip(),sessionId);
   db.update("DELETE FROM seat_zone WHERE session_id=?",sessionId);
   for(Zone z:in.zones())db.update("INSERT INTO seat_zone(ticket_type_id,session_id,label,x,y,w,h) VALUES(?,?,?,?,?,?,?)",z.ticketTypeId(),sessionId,z.label().strip(),z.x(),z.y(),z.w(),z.h());
   return load(sessionId);});
 }
 private void validate(UUID sessionId,List<Zone> zones){
  Set<UUID> seen=new HashSet<>();
  for(Zone z:zones){
   if(!seen.add(z.ticketTypeId()))throw new Problem(400,"INVALID_INPUT","每個票種只能對應一個區塊");
   if(z.x()+z.w()>100||z.y()+z.h()>100)throw new Problem(400,"INVALID_INPUT","區塊超出座位圖範圍");
   if(db.queryForObject("SELECT count(*) FROM ticket_type WHERE id=? AND session_id=?",Long.class,z.ticketTypeId(),sessionId)==0)
    throw new Problem(400,"INVALID_INPUT","票種不屬於此場次");
  }
  for(int i=0;i<zones.size();i++)for(int j=i+1;j<zones.size();j++){
   Zone a=zones.get(i),b=zones.get(j);
   if(a.x()<b.x()+b.w()&&b.x()<a.x()+a.w()&&a.y()<b.y()+b.h()&&b.y()<a.y()+a.h())
    throw new Problem(400,"INVALID_INPUT","「"+a.label()+"」與「"+b.label()+"」區塊重疊");
  }
 }
}
