package dev.jackteng.encore.customer;
import dev.jackteng.encore.common.ApiErrors.Problem;
import dev.jackteng.encore.customer.CustomerService.Principal;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/**
 * 會員實名資料與聯絡方式驗證。Email／簡訊尚未串接正式服務：驗證碼以「模擬寄送」直接回傳給本人畫面顯示，
 * 資料庫只存雜湊。不蒐集身分證字號。
 */
@Service
public class ProfileService {
 public record Profile(String realName,String email,OffsetDateTime emailVerifiedAt,String phone,OffsetDateTime phoneVerifiedAt,long version) {}
 public record Update(@Min(0) long expectedVersion,@Size(max=80) String realName,@Size(max=254) String email,@Size(max=10) String phone) {}
 public record Start(@NotNull @Pattern(regexp="EMAIL|PHONE") String channel) {}
 public record Confirm(@NotNull @Pattern(regexp="EMAIL|PHONE") String channel,@NotNull @Pattern(regexp="[0-9]{6}") String code) {}
 /** 模擬寄送結果：demoCode 只在展示版回給本人，正式串接時移除。 */
 public record Sent(String channel,String maskedTarget,OffsetDateTime expiresAt,int resendAfterSeconds,String demoCode) {}
 static final int TTL_MINUTES=10,RESEND_SECONDS=60,HOURLY_LIMIT=5,MAX_ATTEMPTS=5;
 private static final java.util.regex.Pattern EMAIL=java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"),PHONE=java.util.regex.Pattern.compile("^09[0-9]{8}$");
 private final JdbcTemplate db;private final SecureRandom random=new SecureRandom();
 public ProfileService(JdbcTemplate db){this.db=db;}

 private Profile load(UUID id){
  var rows=db.query("SELECT * FROM customer_profile WHERE customer_id=?",(r,n)->new Profile(r.getString("real_name"),r.getString("email"),
   r.getObject("email_verified_at",OffsetDateTime.class),r.getString("phone"),r.getObject("phone_verified_at",OffsetDateTime.class),r.getLong("version")),id);
  return rows.isEmpty()?new Profile(null,null,null,null,null,0):rows.getFirst();
 }
 private static String blank(String s){return s==null||s.isBlank()?null:s.strip();}
 @Transactional public Profile read(Principal p){CustomerAccess.authorize(db,p);return load(p.id());}

 /** expectedVersion 0 代表尚未建立。變更 Email／手機會清除該項驗證狀態。 */
 @Transactional public Profile update(Principal p,Update in){
  CustomerAccess.authorize(db,p);
  String name=blank(in.realName()),email=blank(in.email()),phone=blank(in.phone());
  if(email!=null){email=email.toLowerCase(Locale.ROOT);if(!EMAIL.matcher(email).matches())throw new Problem(400,"INVALID_INPUT","Email 格式不正確");}
  if(phone!=null&&!PHONE.matcher(phone).matches())throw new Problem(400,"INVALID_INPUT","手機號碼需為 09 開頭的 10 碼數字");
  var cur=db.queryForList("SELECT version,email,phone FROM customer_profile WHERE customer_id=? FOR UPDATE",p.id());
  long version=cur.isEmpty()?0:((Number)cur.getFirst().get("version")).longValue();
  if(version!=in.expectedVersion())throw new Problem(409,"STALE_VERSION","資料已變更，請重新讀取");
  if(cur.isEmpty())db.update("INSERT INTO customer_profile(customer_id,real_name,email,phone) VALUES(?,?,?,?)",p.id(),name,email,phone);
  else db.update("UPDATE customer_profile SET real_name=?,"
   +"email_verified_at=CASE WHEN email IS NOT DISTINCT FROM ? THEN email_verified_at END,email=?,"
   +"phone_verified_at=CASE WHEN phone IS NOT DISTINCT FROM ? THEN phone_verified_at END,phone=?,"
   +"version=version+1,updated_at=clock_timestamp() WHERE customer_id=?",name,email,email,phone,phone,p.id());
  return load(p.id());
 }

 private static String hash(UUID id,String code){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((id+":"+code).getBytes(StandardCharsets.UTF_8)));}
  catch(Exception e){throw new IllegalStateException(e);}
 }
 private static String mask(String channel,String target){
  if(channel.equals("PHONE"))return target.substring(0,4)+"***"+target.substring(7);
  int at=target.indexOf('@');return target.charAt(0)+"***"+target.substring(at);
 }
 @Transactional public Sent start(Principal p,Start in){
  CustomerAccess.authorize(db,p);
  var rows=db.queryForList("SELECT email,email_verified_at,phone,phone_verified_at FROM customer_profile WHERE customer_id=? FOR UPDATE",p.id());
  boolean email=in.channel().equals("EMAIL");
  String target=rows.isEmpty()?null:(String)rows.getFirst().get(email?"email":"phone");
  if(target==null)throw new Problem(409,"NO_TARGET",email?"請先填寫 Email":"請先填寫手機號碼");
  if(rows.getFirst().get(email?"email_verified_at":"phone_verified_at")!=null)throw new Problem(409,"ALREADY_VERIFIED","已完成驗證");
  OffsetDateTime now=db.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class);
  var recent=db.queryForList("SELECT created_at FROM contact_verification WHERE customer_id=? AND channel=? AND created_at>? ORDER BY created_at DESC",OffsetDateTime.class,p.id(),in.channel(),now.minusHours(1));
  if(!recent.isEmpty()&&recent.getFirst().isAfter(now.minusSeconds(RESEND_SECONDS)))throw new Problem(429,"TOO_SOON","請稍後再重新寄送驗證碼");
  if(recent.size()>=HOURLY_LIMIT)throw new Problem(429,"TOO_MANY","一小時內寄送次數已達上限");
  String code=String.format("%06d",random.nextInt(1_000_000));UUID id=UUID.randomUUID();OffsetDateTime expires=now.plusMinutes(TTL_MINUTES);
  db.update("INSERT INTO contact_verification(id,customer_id,channel,target,code_hash,created_at,expires_at) VALUES(?,?,?,?,?,?,?)",id,p.id(),in.channel(),target,hash(id,code),now,expires);
  return new Sent(in.channel(),mask(in.channel(),target),expires,RESEND_SECONDS,code);
 }
 @Transactional(noRollbackFor=WrongCode.class) public Profile confirm(Principal p,Confirm in){
  CustomerAccess.authorize(db,p);
  boolean email=in.channel().equals("EMAIL");
  var prof=db.queryForList("SELECT email,phone FROM customer_profile WHERE customer_id=? FOR UPDATE",p.id());
  if(prof.isEmpty())throw new Problem(409,"NO_TARGET","請先填寫聯絡方式");
  String target=(String)prof.getFirst().get(email?"email":"phone");
  var v=db.queryForList("SELECT id,target,attempts,expires_at>clock_timestamp() live,consumed_at FROM contact_verification WHERE customer_id=? AND channel=? ORDER BY created_at DESC LIMIT 1 FOR UPDATE",p.id(),in.channel());
  if(v.isEmpty())throw new Problem(409,"NO_CODE","請先寄送驗證碼");
  var row=v.getFirst();UUID id=(UUID)row.get("id");
  if(row.get("consumed_at")!=null||!Boolean.TRUE.equals(row.get("live"))||!Objects.equals(target,row.get("target")))throw new Problem(409,"CODE_EXPIRED","驗證碼已失效，請重新寄送");
  if(((Number)row.get("attempts")).intValue()>=MAX_ATTEMPTS)throw new Problem(409,"CODE_LOCKED","錯誤次數過多，請重新寄送");
  String expected=db.queryForObject("SELECT code_hash FROM contact_verification WHERE id=?",String.class,id);
  if(!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),hash(id,in.code()).getBytes(StandardCharsets.US_ASCII))){
   db.update("UPDATE contact_verification SET attempts=attempts+1 WHERE id=?",id);
   // 錯誤次數需保存：WrongCode 不觸發回滾（noRollbackFor），交易提交後由控制器轉成 400
   throw new WrongCode();
  }
  db.update("UPDATE contact_verification SET consumed_at=clock_timestamp() WHERE id=?",id);
  db.update("UPDATE customer_profile SET "+(email?"email_verified_at":"phone_verified_at")+"=clock_timestamp(),version=version+1,updated_at=clock_timestamp() WHERE customer_id=?",p.id());
  return load(p.id());
 }
 /** 驗證碼錯誤：交易照常提交（保留嘗試次數），由控制器轉成 400。 */
 public static final class WrongCode extends RuntimeException {WrongCode(){super("驗證碼錯誤",null,false,false);}}
}
