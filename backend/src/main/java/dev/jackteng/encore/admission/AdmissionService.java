package dev.jackteng.encore.admission;
import dev.jackteng.encore.customer.CustomerService;
import dev.jackteng.encore.customer.CustomerService.Principal;
import dev.jackteng.encore.customer.CustomerAccess;
import dev.jackteng.encore.common.ApiErrors.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class AdmissionService {
 public record Join(@NotNull UUID requestId) {}
 public record State(UUID ticketId,String state,UUID requestId,long position,Long expiresAt,long serverNow) {}
 private final JdbcTemplate db;private final StringRedisTemplate redis;private final ObjectMapper json;private final AdmissionSettings settings;
 private final DefaultRedisScript<String> room=new DefaultRedisScript<>();private final DefaultRedisScript<Long> rate=new DefaultRedisScript<>();
 public AdmissionService(JdbcTemplate db,StringRedisTemplate redis,ObjectMapper json,AdmissionSettings settings){
  this.db=db;this.redis=redis;this.json=json;this.settings=settings;
  room.setLocation(new ClassPathResource("redis/waiting-room.lua"));room.setResultType(String.class);
  rate.setLocation(new ClassPathResource("redis/rate-limit.lua"));rate.setResultType(Long.class);
 }
 private static Problem unavailable(){return new Problem(503,"ADMISSION_UNAVAILABLE","等候室暫時無法確認，請稍後核對原操作");}
 public List<String> keys(UUID ticket){String prefix=settings.namespace()+":room:{"+ticket+"}:";return List.of(prefix+"queue",prefix+"leases",prefix+"records",prefix+"sequence",prefix+"history");}
 public String member(Principal p){return CustomerService.hash(p.id().toString()+":"+p.sessionVersion());}
 public String rateKey(Principal p,boolean placing){return settings.namespace()+":rate:{"+CustomerService.hash(p.id().toString())+"}:"+(placing?"place":"room");}
 public void limit(Principal principal,boolean placing){
  if(!settings.enabled())return;
  try{
   Long delay=redis.execute(rate,List.of(rateKey(principal,placing)),String.valueOf(placing?settings.placeRate():settings.joinRate()),placing?"10000":"60000");
   if(delay==null||delay<0)throw unavailable();
   if(delay>0)throw new Problem(429,"RATE_LIMITED","操作過於頻繁，請稍後重試原操作",(int)Math.max(1,(delay+999)/1000));
  }catch(Problem e){throw e;}catch(RuntimeException e){throw unavailable();}
 }
 private State evaluate(Principal p,UUID ticket,String mode,UUID request,long salesEnd){
  try{
   String raw=redis.execute(room,keys(ticket),mode,member(p),request==null?"":request.toString(),""+settings.active(),""+settings.queue(),""+(settings.permitSeconds()*1000),""+(settings.idleSeconds()*1000),""+(settings.waitSeconds()*1000),""+salesEnd);
   var node=json.readTree(raw);String state=node.path("state").asText();
   if(state.equals("CONFLICT"))throw new Problem(409,"QUEUE_COMMAND_CONFLICT","已有排隊操作，請讀取原等候室狀態");
   if(state.equals("FULL"))throw new Problem(429,"ROOM_FULL","等候室已達展示容量，請稍後重試原排隊操作",10);
   if(!List.of("NOT_JOINED","WAITING","ADMITTED","EXPIRED","DENIED").contains(state))throw unavailable();
   UUID id=node.path("requestId").isTextual()?UUID.fromString(node.get("requestId").asText()):null;
   return new State(ticket,state,id,node.path("position").asLong(),node.path("expiresAt").isNumber()?node.get("expiresAt").asLong():null,node.path("serverNow").asLong());
  }catch(Problem e){throw e;}catch(Exception e){throw unavailable();}
 }
 @Transactional public State join(Principal p,UUID ticket,Join body){return visit(p,ticket,body.requestId());}
 @Transactional public State status(Principal p,UUID ticket){return visit(p,ticket,null);}
 private State visit(Principal p,UUID ticket,UUID request){
  var auth=CustomerAccess.authorize(db,p);
  if(!settings.enabled())throw new Problem(409,"ADMISSION_DISABLED","等候室尚未啟用");
  limit(p,false);
  var rows=db.query("SELECT s.sales_end FROM ticket_type t JOIN event_session s ON s.id=t.session_id JOIN event e ON e.id=s.event_id WHERE t.id=? AND e.status='PUBLISHED' AND s.sales_start<=clock_timestamp() AND s.sales_end>clock_timestamp()",(r,n)->r.getObject(1,OffsetDateTime.class),ticket);
  if(rows.isEmpty())throw new Problem(404,"NOT_FOUND","找不到開放中的票種等候室");
  var result=evaluate(p,ticket,request==null?"POLL":"JOIN",request,rows.getFirst().toInstant().toEpochMilli());
  CustomerAccess.stillAuthenticated(auth,db.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class));return result;
 }
 /** Call at the last boundary before PG changes held, after waiting for the ticket lock. */
 public void require(Principal p,UUID ticket){
  if(!settings.enabled())return;
  var state=evaluate(p,ticket,"CHECK",null,0);
  if(!state.state().equals("ADMITTED"))throw new Problem(409,"ADMISSION_REQUIRED","入場資格尚未取得或已到期，請先核對等候室");
 }
}
