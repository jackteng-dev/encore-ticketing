package dev.elpsy.ticket;

import dev.elpsy.ticket.customer.*;
import dev.elpsy.ticket.common.ApiErrors.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"ticket.delivery.enabled=false","ticket.orders.enabled=false","ticket.admission.enabled=false","ticket.expiry.enabled=false","ticket.mock-payments.enabled=false"})
@AutoConfigureMockMvc
class CustomerIntegrationTest {
 @Autowired CustomerService service;@Autowired JdbcTemplate db;@Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired PasswordEncoder passwords;
 private final String password=UUID.randomUUID().toString();
 private String handle(){return "c-"+UUID.randomUUID().toString().substring(0,30);}
 private CustomerService.Issued account(){return service.register(handle(),"合成顧客",password);}
 private Cookie session(String token){return new Cookie(CustomerAuthenticationFilter.COOKIE,token);}
 @Test void expectedCustomerAssertionCannotUseSwitchedCookieOrSupplyIdentity()throws Exception {
  var a=account();var b=account();String owner=a.profile().id().toString();
  mvc.perform(get("/api/customer/auth/me").cookie(session(b.token())).header("X-Expected-Customer-ID",owner)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("CUSTOMER_CHANGED"));
  mvc.perform(get("/api/customer/auth/me").header("X-Expected-Customer-ID",owner)).andExpect(status().isUnauthorized());
  mvc.perform(postCsrf("/api/customer/auth/logout",Map.of()).cookie(session(b.token())).header("X-Expected-Customer-ID",owner)).andExpect(status().isUnauthorized());
  assertTrue(service.authenticate(b.token()).isPresent());
  mvc.perform(get("/api/customer/auth/me").cookie(session(a.token())).header("X-Expected-Customer-ID",owner)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(owner));
  mvc.perform(postCsrf("/api/customer/auth/logout",Map.of()).cookie(session(a.token())).header("X-Expected-Customer-ID",owner)).andExpect(status().isOk());assertTrue(service.authenticate(a.token()).isEmpty());
 }
 @BeforeEach void isolated(){assertEquals("ticket_demo",db.queryForObject("SELECT current_database()",String.class));}
 private record Csrf(Cookie cookie,String header,String token) {}
 private Csrf csrf()throws Exception {
  var result=mvc.perform(get("/api/customer/auth/csrf")).andExpect(status().isOk()).andReturn();
  var body=json.readTree(result.getResponse().getContentAsString());
  return new Csrf(result.getResponse().getCookie("TICKET_CUSTOMER_CSRF"),body.get("headerName").asText(),body.get("token").asText());
 }
 private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postCsrf(String path,Object body)throws Exception {
  var c=csrf();return post(path).cookie(c.cookie()).header(c.header(),c.token()).contentType("application/json").content(json.writeValueAsString(body));
 }
 @Test void passwordsAndSessionSecretsOnlyHashes(){
  var a=account();var hash=db.queryForObject("SELECT password_hash FROM customer_account WHERE id=?",String.class,a.profile().id());
  assertNotEquals(password,hash);assertTrue(passwords.matches(password,hash));assertEquals(43,a.token().length());
  assertEquals(1,db.queryForObject("SELECT count(*) FROM customer_session WHERE token_hash=?",Integer.class,CustomerService.hash(a.token())));
  assertEquals(0,db.queryForObject("SELECT count(*) FROM customer_session WHERE token_hash=?",Integer.class,a.token()));
 }
 @Test void authenticationAndOwnerProjection()throws Exception {
  var a=account();mvc.perform(get("/api/customer/auth/me").cookie(session(a.token()))).andExpect(status().isOk())
   .andExpect(jsonPath("$.id").value(a.profile().id().toString())).andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.token").doesNotExist());
  mvc.perform(get("/api/customer/auth/me")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/customer/auth/me").cookie(session("invalid"))).andExpect(status().isUnauthorized());
 }
 @Test void staffAndCustomerRealmsCannotImpersonate()throws Exception {
  var a=account();mvc.perform(get("/api/auth/me").cookie(session(a.token()))).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/admin/events").cookie(session(a.token()))).andExpect(status().isUnauthorized());
  // A test-injected authenticated staff principal still lacks CUSTOMER authority.
  mvc.perform(get("/api/customer/auth/me").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("staff").authorities(()->"CATALOG_WRITE"))).andExpect(status().isForbidden());
 }
 @Test void databaseExpiryRevokesImmediately(){
  var a=account();db.update("UPDATE customer_session SET created_at=clock_timestamp()-interval '31 minutes',expires_at=clock_timestamp()-interval '2 minutes' WHERE token_hash=?",CustomerService.hash(a.token()));
  assertTrue(service.authenticate(a.token()).isEmpty());
 }
 @Test void disableReenableNeverRevivesOldSession(){
  var a=account();db.update("UPDATE customer_account SET enabled=false WHERE id=?",a.profile().id());assertTrue(service.authenticate(a.token()).isEmpty());
  assertEquals(401,assertThrows(Problem.class,()->service.login(a.profile().handle(),password)).status);
  db.update("UPDATE customer_account SET enabled=true WHERE id=?",a.profile().id());assertTrue(service.authenticate(a.token()).isEmpty());
  assertTrue(service.authenticate(service.login(a.profile().handle(),password).token()).isPresent());
 }
 @Test void passwordChangeRevokesPreviousTokens(){
  var a=account();var replacement=UUID.randomUUID().toString();db.update("UPDATE customer_account SET password_hash=? WHERE id=?",passwords.encode(replacement),a.profile().id());
  assertTrue(service.authenticate(a.token()).isEmpty());assertEquals(401,assertThrows(Problem.class,()->service.login(a.profile().handle(),password)).status);
  assertTrue(service.authenticate(service.login(a.profile().handle(),replacement).token()).isPresent());
 }
 @Test void boundedConcurrentSessions()throws Exception {
  var a=account();try(var pool=Executors.newFixedThreadPool(6)){
   var calls=new ArrayList<Callable<CustomerService.Issued>>();for(int i=0;i<6;i++)calls.add(()->service.login(a.profile().handle(),password));
   for(var r:pool.invokeAll(calls))assertNotNull(r.get().token());
  }
  assertEquals(5,db.queryForObject("SELECT count(*) FROM customer_session WHERE customer_id=?",Integer.class,a.profile().id()));
  assertTrue(service.authenticate(a.token()).isEmpty());
 }
 @Test void concurrentDuplicateRegistrationOnlyOneAccount()throws Exception {
  var h=handle();try(var pool=Executors.newFixedThreadPool(2)){
   Callable<Boolean> op=()->{try{service.register(h,"合成",password);return true;}catch(org.springframework.dao.DataIntegrityViolationException e){return false;}};
   var r=pool.invokeAll(List.of(op,op));assertNotEquals(r.get(0).get(),r.get(1).get());
  }
  assertEquals(1,db.queryForObject("SELECT count(*) FROM customer_account WHERE handle=?",Integer.class,h));
  assertEquals(1,db.queryForObject("SELECT count(*) FROM customer_session s JOIN customer_account a ON a.id=s.customer_id WHERE a.handle=?",Integer.class,h));
 }
 @Test void sessionFailureRollsBackRegistration(){
  var h=handle();db.execute("ALTER TABLE customer_session ADD CONSTRAINT m2_session_fault CHECK(false) NOT VALID");
  try{assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->service.register(h,"合成",password));assertEquals(0,db.queryForObject("SELECT count(*) FROM customer_account WHERE handle=?",Integer.class,h));}
  finally{db.execute("ALTER TABLE customer_session DROP CONSTRAINT m2_session_fault");}
  assertNotNull(service.register(h,"合成",password));
 }
 @Test void csrfRequiredForRegisterLoginAndLogout()throws Exception {
  for(var path:List.of("register","login","logout"))mvc.perform(post("/api/customer/auth/"+path).contentType("application/json").content("{}")).andExpect(status().isForbidden());
  var staff=mvc.perform(get("/api/auth/csrf")).andReturn();var t=json.readTree(staff.getResponse().getContentAsString());
  mvc.perform(post("/api/customer/auth/register").header(t.get("headerName").asText(),t.get("token").asText()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
 }
 @Test void realCustomerCsrfCookieRegistrationAndLogout()throws Exception {
  var h=handle();var r=mvc.perform(postCsrf("/api/customer/auth/register",Map.of("handle",h,"displayName","<script>not executable</script>","password",password))).andExpect(status().isOk()).andExpect(jsonPath("$.handle").value(h)).andReturn();
  var c=r.getResponse().getCookie(CustomerAuthenticationFilter.COOKIE);assertNotNull(c);assertTrue(c.isHttpOnly());assertEquals("/api/customer",c.getPath());assertEquals(1800,c.getMaxAge());
  assertTrue(r.getResponse().getHeaders("Set-Cookie").stream().anyMatch(s->s.contains("SameSite=Strict")));
  mvc.perform(postCsrf("/api/customer/auth/logout",Map.of()).cookie(c)).andExpect(status().isOk());
  mvc.perform(get("/api/customer/auth/me").cookie(c)).andExpect(status().isUnauthorized());
  mvc.perform(postCsrf("/api/customer/auth/logout",Map.of())).andExpect(status().isOk());
 }
 @Test void loginRotatesBrowserTokenAndCsrf()throws Exception {
  var a=account();var csrf=csrf();var result=mvc.perform(post("/api/customer/auth/login").cookie(csrf.cookie(),session(a.token())).header(csrf.header(),csrf.token()).contentType("application/json").content(json.writeValueAsString(Map.of("handle",a.profile().handle(),"password",password))))
   .andExpect(status().isOk()).andReturn();
  assertTrue(service.authenticate(a.token()).isEmpty());assertTrue(service.authenticate(result.getResponse().getCookie(CustomerAuthenticationFilter.COOKIE).getValue()).isPresent());
  assertEquals(0,result.getResponse().getCookie("TICKET_CUSTOMER_CSRF").getMaxAge());
 }
 @Test void unknownOwnerFieldsAndInvalidInputsRejected()throws Exception {
  var b=Map.of("handle",handle(),"displayName","合成","password",password,"id",UUID.randomUUID());
  mvc.perform(postCsrf("/api/customer/auth/register",b)).andExpect(status().isBadRequest());
  for(var p:List.of("short","密".repeat(25)))mvc.perform(postCsrf("/api/customer/auth/register",Map.of("handle",handle(),"displayName","合成","password",p))).andExpect(status().isBadRequest());
  mvc.perform(postCsrf("/api/customer/auth/register",Map.of("handle","Not Normalized","displayName","合成","password",password))).andExpect(status().isBadRequest());
 }
 @Test void wrongAndUnknownCredentialsSameResponse()throws Exception {
  var a=account();var wrong=UUID.randomUUID().toString();
  var known=mvc.perform(postCsrf("/api/customer/auth/login",Map.of("handle",a.profile().handle(),"password",wrong))).andExpect(status().isUnauthorized()).andReturn();
  var unknown=mvc.perform(postCsrf("/api/customer/auth/login",Map.of("handle",handle(),"password",wrong))).andExpect(status().isUnauthorized()).andReturn();
  assertEquals(known.getResponse().getContentAsString(),unknown.getResponse().getContentAsString());
 }
}
