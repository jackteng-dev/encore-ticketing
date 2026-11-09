package dev.elpsy.ticket.customer;

import dev.elpsy.ticket.common.ApiErrors.Problem;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {
 public record Profile(UUID id,String handle,String displayName) {}
 public record Principal(UUID id,String handle,long sessionVersion,String sessionHash) implements java.security.Principal {
  @Override public String getName(){return handle;}
 }
 // Internal only; controllers expose Profile, never Issued or raw tokens.
 public record Issued(Profile profile,String token) {}
 private record Account(Profile profile,String hash,boolean enabled,long version) {}
 private final JdbcTemplate db;
 private final PasswordEncoder passwords;
 private final SecureRandom random=new SecureRandom();
 private final String dummyHash;
 public CustomerService(JdbcTemplate db,PasswordEncoder passwords){
  this.db=db;this.passwords=passwords;this.dummyHash=passwords.encode(UUID.randomUUID().toString());
 }
 public static String hash(String value){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));}
  catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
 private void validatePassword(String password){
  if(password==null||password.length()<12||password.length()>72||password.getBytes(StandardCharsets.UTF_8).length>72)
   throw new Problem(400,"INVALID_INPUT","密碼須為 12–72 字元，UTF-8 不超過 72 bytes");
 }
 @Transactional public Issued register(String handle,String displayName,String password){
  validatePassword(password);
  var profile=new Profile(UUID.randomUUID(),handle,displayName.trim());
  db.update("INSERT INTO customer_account(id,handle,display_name,password_hash) VALUES(?,?,?,?)",profile.id(),handle,profile.displayName(),passwords.encode(password));
  return issue(profile,1);
 }
 @Transactional public Issued login(String handle,String password){
  validatePassword(password);
  var accounts=db.query("SELECT id,handle,display_name,password_hash,enabled,session_version FROM customer_account WHERE handle=? FOR UPDATE",
   (r,n)->new Account(new Profile(r.getObject(1,UUID.class),r.getString(2),r.getString(3)),r.getString(4),r.getBoolean(5),r.getLong(6)),handle);
  var account=accounts.isEmpty()?null:accounts.getFirst();
  boolean matches=passwords.matches(password,account==null?dummyHash:account.hash());
  if(account==null||!matches||!account.enabled())throw new Problem(401,"UNAUTHENTICATED","帳號或密碼不正確");
  return issue(account.profile(),account.version());
 }
 private Issued issue(Profile profile,long version){
  // Account row is locked by login; registration owns its new row. Cap at five live sessions.
  db.update("DELETE FROM customer_session WHERE customer_id=? AND (expires_at<=clock_timestamp() OR session_version<>?)",profile.id(),version);
  db.update("DELETE FROM customer_session WHERE token_hash IN (SELECT token_hash FROM customer_session WHERE customer_id=? ORDER BY created_at DESC,token_hash OFFSET 4)",profile.id());
  byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  db.update("WITH stamp AS MATERIALIZED (SELECT clock_timestamp() AS created) INSERT INTO customer_session(token_hash,customer_id,session_version,created_at,expires_at) SELECT ?,?,?,created,created+interval '30 minutes' FROM stamp",hash(token),profile.id(),version);
  return new Issued(profile,token);
 }
 public Optional<Principal> authenticate(String token){
  if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))return Optional.empty();
  String tokenHash=hash(token);
  return db.query("SELECT a.id,a.handle,a.session_version FROM customer_session s JOIN customer_account a ON a.id=s.customer_id WHERE s.token_hash=? AND s.expires_at>clock_timestamp() AND a.enabled AND a.session_version=s.session_version",
   (r,n)->new Principal(r.getObject(1,UUID.class),r.getString(2),r.getLong(3),tokenHash),tokenHash).stream().findFirst();
 }
 public Profile profile(Principal principal){
  return db.query("SELECT id,handle,display_name FROM customer_account WHERE id=? AND enabled",(r,n)->new Profile(r.getObject(1,UUID.class),r.getString(2),r.getString(3)),principal.id())
   .stream().findFirst().orElseThrow(()->new Problem(401,"UNAUTHENTICATED","請重新登入"));
 }
 @Transactional public void logout(String token){
  if(token!=null&&token.matches("[A-Za-z0-9_-]{43}"))db.update("DELETE FROM customer_session WHERE token_hash=?",hash(token));
 }
}
