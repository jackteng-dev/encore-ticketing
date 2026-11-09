package dev.elpsy.ticket;
import dev.elpsy.ticket.catalog.*;
import dev.elpsy.ticket.catalog.Models.*;
import dev.elpsy.ticket.common.ApiErrors.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
@SpringBootTest(properties={"ticket.delivery.enabled=false","ticket.orders.enabled=false","ticket.admission.enabled=false","ticket.expiry.enabled=false","ticket.mock-payments.enabled=false"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogIntegrationTest {
 @Autowired CatalogService service; @Autowired JdbcTemplate db; @Autowired MockMvc mvc;
 @Autowired ObjectMapper json; @Autowired PasswordEncoder passwords;
 @Test void asyncCheckoutAvailabilityRequiresPublishedOrderingPurchaseDeliveryAndPublisher(){
  var e=event();var s=session(e);ticket(s);service.transition(manager,e.id(),new Transition(UUID.randomUUID(),1L),true);
  for(int flags=0;flags<16;flags++){
   var projection=new CatalogService(db,json,(flags&1)!=0);
   org.springframework.test.util.ReflectionTestUtils.setField(projection,"purchasesEnabled",(flags&2)!=0);
   org.springframework.test.util.ReflectionTestUtils.setField(projection,"deliveryEnabled",(flags&4)!=0);
   org.springframework.test.util.ReflectionTestUtils.setField(projection,"publisherEnabled",(flags&8)!=0);
   assertEquals(flags==15,projection.detail(e.id(),true).purchaseRequestsEnabled());
  }
  assertFalse(service.detail(e.id(),true).purchaseRequestsEnabled());
  var draft=event();var open=new CatalogService(db,json,true);for(var f:List.of("purchasesEnabled","deliveryEnabled","publisherEnabled"))org.springframework.test.util.ReflectionTestUtils.setField(open,f,true);assertFalse(open.detail(draft.id(),false).purchaseRequestsEnabled());
 }
 String manager="m1-manager-"+UUID.randomUUID(),viewer="m1-viewer-"+UUID.randomUUID(),disabled="m1-disabled-"+UUID.randomUUID();
 @BeforeAll void seed(){
  assertEquals("ticket_demo",db.queryForObject("SELECT current_database()",String.class),"Only isolated ticket_demo");
  for(String u:List.of(manager,viewer,disabled)) db.update("INSERT INTO staff_account VALUES(?,?,?,?,?)",UUID.randomUUID(),u,passwords.encode(UUID.randomUUID().toString()),u.equals(viewer)?"VIEWER":"MANAGER",!u.equals(disabled));
 }
 private EventInput input(UUID command,long version,String title){return new EventInput(command,version,"test-"+UUID.randomUUID(),title,"<script>not executable</script>","測試場地");}
 private Event event(){return service.createEvent(manager,input(UUID.randomUUID(),0,"合成活動"));}
 private Session session(Event e){var now=OffsetDateTime.now();return service.createSession(manager,e.id(),new SessionInput(UUID.randomUUID(),0L,"晚場",now.plusDays(4),now.plusDays(1),now.plusDays(3)));}
 private Ticket ticket(Session s){return service.createTicket(manager,s.id(),new TicketInput(UUID.randomUUID(),0L,"一般票",1200,10,2));}
 private void problem(int code,Runnable op){assertEquals(code,assertThrows(Problem.class,op::run).status);}
 private org.springframework.test.web.servlet.request.RequestPostProcessor user(String u,boolean write){
  return SecurityMockMvcRequestPostProcessors.user(u).authorities(()->"CATALOG_READ",()->write?"CATALOG_WRITE":"CATALOG_VIEW");
 }
 @Test void realPostgresMigration(){assertEquals("PostgreSQL",db.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getDatabaseProductName()));assertEquals(24,db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success",Integer.class));}
 @Test void draftAndArchivedHidden(){var e=event();problem(404,()->service.detail(e.id(),true));service.transition(manager,e.id(),new Transition(UUID.randomUUID(),1L),false);problem(404,()->service.detail(e.id(),true));}
 @Test void publishCompleteProjection(){var e=event();var s=session(e);ticket(s);service.transition(manager,e.id(),new Transition(UUID.randomUUID(),1L),true);var d=service.detail(e.id(),true);assertFalse(d.orderingEnabled());assertEquals(1,d.sessions().size());assertEquals(1200,d.sessions().getFirst().tickets().getFirst().priceTwd());}
 @Test void incompleteCannotPublish(){var e=event();problem(409,()->service.transition(manager,e.id(),new Transition(UUID.randomUUID(),1L),true));session(e);problem(409,()->service.transition(manager,e.id(),new Transition(UUID.randomUUID(),1L),true));}
 @Test void rejectStaleEvent(){var e=event();service.updateEvent(manager,e.id(),input(UUID.randomUUID(),1,"新名稱"));problem(409,()->service.updateEvent(manager,e.id(),input(UUID.randomUUID(),1,"舊版本")));assertEquals("新名稱",service.detail(e.id(),false).event().title());}
 @Test void createReplayAndFingerprint(){var in=input(UUID.randomUUID(),0,"重送");var e=service.createEvent(manager,in);assertEquals(e,service.createEvent(manager,in));problem(409,()->service.createEvent(manager,new EventInput(in.requestId(),0L,in.slug(),"改內容",in.description(),in.venue())));}
 @Test void replayReturnsOriginalSnapshot(){var in=input(UUID.randomUUID(),0,"原名称");var e=service.createEvent(manager,in);service.updateEvent(manager,e.id(),input(UUID.randomUUID(),1,"改名"));assertEquals(e,service.createEvent(manager,in));assertEquals("改名",service.detail(e.id(),false).event().title());}
 @Test void closedTermsNotMutable(){var e=event();var s=session(e);var t=ticket(s);service.transition(manager,e.id(),new Transition(UUID.randomUUID(),1L),true);problem(409,()->service.updateEvent(manager,e.id(),input(UUID.randomUUID(),2,"修改")));problem(409,()->service.updateTicket(manager,t.id(),new TicketInput(UUID.randomUUID(),1L,"改票價",1,1,1)));problem(409,()->session(e));}
 @Test void invalidTimesAndLimits(){var e=event();var now=OffsetDateTime.now();problem(400,()->service.createSession(manager,e.id(),new SessionInput(UUID.randomUUID(),0L,"錯誤",now,now.plusDays(1),now.plusDays(2))));var s=session(e);problem(400,()->service.createTicket(manager,s.id(),new TicketInput(UUID.randomUUID(),0L,"錯誤",100,1,2)));}
 @Test void staleSessionAndTicket(){var e=event();var s=session(e);var t=ticket(s);service.updateSession(manager,s.id(),new SessionInput(UUID.randomUUID(),1L,"早場",s.startsAt(),s.salesStart(),s.salesEnd()));problem(409,()->service.updateSession(manager,s.id(),new SessionInput(UUID.randomUUID(),1L,"衝突",s.startsAt(),s.salesStart(),s.salesEnd())));service.updateTicket(manager,t.id(),new TicketInput(UUID.randomUUID(),1L,"修改",2000,20,3));problem(409,()->service.updateTicket(manager,t.id(),new TicketInput(UUID.randomUUID(),1L,"衝突",500,10,2)));}
 @Test void managerOnlyAndDisabled(){problem(403,()->service.createEvent(viewer,input(UUID.randomUUID(),0,"不可寫")));problem(403,()->service.createEvent(disabled,input(UUID.randomUUID(),0,"停用")));}
 @Test void boundedPage(){problem(400,()->service.list(true,0,12));problem(400,()->service.list(false,1,21));problem(400,()->service.audit(1,21));}
 @Test void concurrentSameRequestCreatesOnce() throws Exception {
  var in=input(UUID.randomUUID(),0,"併發重送");try(var pool=Executors.newFixedThreadPool(6)){
   var calls=new ArrayList<Callable<Event>>();for(int i=0;i<6;i++)calls.add(()->service.createEvent(manager,in));
   var result=pool.invokeAll(calls);var id=result.getFirst().get().id();for(var r:result)assertEquals(id,r.get().id());
   assertEquals(1,db.queryForObject("SELECT count(*) FROM event WHERE slug=?",Integer.class,in.slug()));assertEquals(1,db.queryForObject("SELECT count(*) FROM catalog_command WHERE request_id=?",Integer.class,in.requestId()));
  }
 }
 @Test void concurrentVersionOnlyOneWins() throws Exception {
  var e=event();try(var pool=Executors.newFixedThreadPool(2)){
   Callable<Boolean> op=()->{try{service.updateEvent(manager,e.id(),input(UUID.randomUUID(),1,"競爭"));return true;}catch(Problem p){assertEquals(409,p.status);return false;}};
   var r=pool.invokeAll(List.of(op,op));assertNotEquals(r.get(0).get(),r.get(1).get());assertEquals(2,service.detail(e.id(),false).event().version());
  }
 }
 @Test void auditFailureRollsBackAndRetryWorks(){
  var in=input(UUID.randomUUID(),0,"故障");db.execute("ALTER TABLE catalog_command ADD CONSTRAINT m1_audit_fault CHECK(request_id <> '"+in.requestId()+"'::uuid)");
  try{assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->service.createEvent(manager,in));assertEquals(0,db.queryForObject("SELECT count(*) FROM event WHERE slug=?",Integer.class,in.slug()));}
  finally{db.execute("ALTER TABLE catalog_command DROP CONSTRAINT m1_audit_fault");}
  assertNotNull(service.createEvent(manager,in).id());
 }
 @Test void csrfRequired() throws Exception {mvc.perform(post("/api/admin/events").with(user(manager,true)).contentType("application/json").content(json.writeValueAsString(input(UUID.randomUUID(),0,"CSRF")))).andExpect(status().isForbidden());}
 @Test void anonymousAndViewerPermissions() throws Exception {
  mvc.perform(get("/api/admin/events")).andExpect(status().isUnauthorized());mvc.perform(get("/api/public/events")).andExpect(status().isOk());
  mvc.perform(get("/api/admin/events").with(user(viewer,false))).andExpect(status().isOk());
  mvc.perform(post("/api/admin/events").with(user(viewer,false)).with(SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json").content(json.writeValueAsString(input(UUID.randomUUID(),0,"不可寫")))).andExpect(status().isForbidden());
 }
 @Test void inputAndOwnershipSpoofRejected() throws Exception {
  String body=json.writeValueAsString(input(UUID.randomUUID(),0,"偽造"));body=body.substring(0,body.length()-1)+",\"actorId\":\"fake\"}";
  mvc.perform(post("/api/admin/events").with(user(manager,true)).with(SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
  mvc.perform(get("/api/public/events/not-a-uuid")).andExpect(status().isBadRequest());
 }
 @Test void sessionAuthorizationChangesRevoke() throws Exception {
  mvc.perform(get("/api/auth/me").with(user(disabled,true))).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/auth/me").with(user(viewer,true))).andExpect(status().isUnauthorized());
 }
 @Test void createRequiresZeroVersion(){problem(400,()->service.createEvent(manager,input(UUID.randomUUID(),1,"新增版本")));}
 @Test void databaseConstraints(){var e=event();var s=session(e);assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("INSERT INTO ticket_type(id,session_id,name,price_twd,capacity,max_per_customer) VALUES(?,?,?,0,1,1)",UUID.randomUUID(),s.id(),"無效價格"));}
}
