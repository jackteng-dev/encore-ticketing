package dev.jackteng.encore.identity;

import dev.jackteng.encore.common.ApiErrors.Problem;
import dev.jackteng.encore.customer.CustomerService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Structured PG sessions, not serialized HttpSession or a Redis/cache fallback. */
@Service
public class StaffSessionService {
 public record Principal(UUID id,String username,String role,long sessionVersion,String sessionHash,OffsetDateTime expiresAt) implements java.security.Principal {
  @Override public String getName(){return username;}
 }
 public record Issued(String token) {}
 private record Account(UUID id,String hash,String role,boolean enabled,long version) {}
 private final JdbcTemplate db;private final PasswordEncoder passwords;private final SecureRandom random=new SecureRandom();private final String dummy;
 public StaffSessionService(JdbcTemplate db,PasswordEncoder passwords){this.db=db;this.passwords=passwords;dummy=passwords.encode(UUID.randomUUID().toString());}
 public static boolean validToken(String token){return token!=null&&token.matches("[A-Za-z0-9_-]{43}");}
 public static String hash(String token){return CustomerService.hash(token);}
 private static Problem denied(){return new Problem(401,"UNAUTHENTICATED","登入失敗");}
 @Transactional public Issued login(String username,String password,String previousToken){
  if(username==null||username.isBlank()||username.length()>80||password==null||password.isEmpty()||password.getBytes(StandardCharsets.UTF_8).length>72)throw denied();
  var accounts=db.query("SELECT id,password_hash,role,enabled,session_version FROM staff_account WHERE username=? FOR UPDATE",(r,n)->new Account(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getBoolean(4),r.getLong(5)),username);
  var a=accounts.isEmpty()?null:accounts.getFirst();boolean match=passwords.matches(password,a==null?dummy:a.hash());
  if(a==null||!match||!a.enabled())throw denied();
  // Account lock serializes caps across API processes; all effects roll back together.
  db.update("DELETE FROM staff_session WHERE staff_id=? AND (expires_at<=clock_timestamp() OR session_version<>?)",a.id(),a.version());
  db.update("DELETE FROM staff_session WHERE token_hash IN (SELECT token_hash FROM staff_session WHERE staff_id=? ORDER BY created_at DESC,token_hash DESC OFFSET 4)",a.id());
  if(validToken(previousToken))db.update("DELETE FROM staff_session WHERE token_hash=?",hash(previousToken));
  byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  db.update("INSERT INTO staff_session(token_hash,staff_id,session_version,role_snapshot,expires_at) VALUES(?,?,?,?,clock_timestamp()+interval '30 minutes')",hash(token),a.id(),a.version(),a.role());
  return new Issued(token);
 }
 public Optional<Principal> authenticate(String token){
  if(!validToken(token))return Optional.empty();
  String hash=hash(token);
  return db.query("SELECT a.id,a.username,a.role,a.session_version,s.expires_at FROM staff_session s JOIN staff_account a ON a.id=s.staff_id WHERE s.token_hash=? AND s.expires_at>clock_timestamp() AND a.enabled AND a.session_version=s.session_version AND a.role=s.role_snapshot",(r,n)->new Principal(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getLong(4),hash,r.getObject(5,OffsetDateTime.class)),hash).stream().findFirst();
 }
 @Transactional public void logout(String token){if(validToken(token))db.update("DELETE FROM staff_session WHERE token_hash=?",hash(token));}
}
