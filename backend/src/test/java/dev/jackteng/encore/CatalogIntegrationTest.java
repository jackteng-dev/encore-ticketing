package dev.jackteng.encore;
import dev.jackteng.encore.catalog.*;
import dev.jackteng.encore.catalog.Models.*;
import dev.jackteng.encore.catalog.TaxonomyModels.*;
import dev.jackteng.encore.common.ApiErrors.Problem;
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
 @Autowired TaxonomyService taxonomy; @Autowired SeatMapService seatMaps;
 @Autowired dev.jackteng.encore.customer.CustomerService customers; @Autowired dev.jackteng.encore.customer.EngagementService engagement;
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
 @Test void realPostgresMigration(){assertEquals("PostgreSQL",db.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getDatabaseProductName()));assertEquals(27,db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success",Integer.class));}
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
 private Event published(String title,String venue,int startsInDays,boolean selling,int... prices){
  var e=service.createEvent(manager,new EventInput(UUID.randomUUID(),0L,"search-"+UUID.randomUUID(),title,"搜尋測試",venue));var now=OffsetDateTime.now();
  var s=service.createSession(manager,e.id(),new SessionInput(UUID.randomUUID(),0L,"場次",now.plusDays(startsInDays),selling?now.minusHours(1):now.plusDays(1),now.plusDays(startsInDays).minusHours(1)));
  for(int price:prices)service.createTicket(manager,s.id(),new TicketInput(UUID.randomUUID(),0L,"票"+price,price,10,2));
  return service.transition(manager,e.id(),new Transition(UUID.randomUUID(),1L),true);
 }
 private List<UUID> ids(Page<PublicEvent> p){return p.items().stream().map(PublicEvent::id).toList();}
 @Test void publicSearchMatchesTitleOrVenueAndHidesDrafts(){
  String token="tk"+UUID.randomUUID().toString().substring(0,8);
  var byTitle=published("搜尋"+token+"演唱會","台北小巨蛋",5,false,800,2400);var byVenue=published("其他活動","高雄"+token+"中心",6,false,1000);
  service.createEvent(manager,new EventInput(UUID.randomUUID(),0L,"draft-"+UUID.randomUUID(),"草稿"+token,"",token));
  var page=service.search(token.toUpperCase(),null,null,PublicSort.NEWEST,false,1,20);
  assertEquals(2,page.total());assertEquals(Set.of(byTitle.id(),byVenue.id()),Set.copyOf(ids(page)));
  var hit=page.items().stream().filter(x->x.id().equals(byTitle.id())).findFirst().orElseThrow();
  assertEquals(800,hit.minPriceTwd());assertEquals(2400,hit.maxPriceTwd());assertEquals(1,hit.sessionCount());assertNotNull(hit.firstStartsAt());assertFalse(hit.selling());
 }
 @Test void publicSearchTreatsWildcardsLiterally(){
  String token="wc"+UUID.randomUUID().toString().substring(0,8);var literal=published(token+"_50%","場地",5,false,500);published(token+"x50y","場地",5,false,500);
  assertEquals(List.of(literal.id()),ids(service.search(token+"_50%",null,null,PublicSort.NEWEST,false,1,20)));
  assertEquals(0,service.search(token+"%x",null,null,PublicSort.NEWEST,false,1,20).total());
 }
 @Test void publicSearchFiltersDatesSortsAndOnSale(){
  String token="ds"+UUID.randomUUID().toString().substring(0,8);
  var soon=published(token+"近","場地",3,true,1500);var later=published(token+"遠","場地",30,false,500);
  var day=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei"));
  assertEquals(List.of(soon.id()),ids(service.search(token,day,day.plusDays(10),PublicSort.NEWEST,false,1,20)));
  assertEquals(List.of(later.id()),ids(service.search(token,day.plusDays(20),null,PublicSort.NEWEST,false,1,20)));
  assertEquals(List.of(soon.id(),later.id()),ids(service.search(token,null,null,PublicSort.DATE,false,1,20)));
  assertEquals(List.of(later.id(),soon.id()),ids(service.search(token,null,null,PublicSort.PRICE,false,1,20)));
  var selling=service.search(token,null,null,PublicSort.NEWEST,true,1,20);assertEquals(List.of(soon.id()),ids(selling));assertTrue(selling.items().getFirst().selling());
  problem(400,()->service.search(token,day.plusDays(2),day,PublicSort.NEWEST,false,1,20));
  problem(400,()->service.search("x".repeat(61),null,null,PublicSort.NEWEST,false,1,20));
  problem(400,()->service.search(token,null,null,PublicSort.NEWEST,false,1,21));
 }
 @Test void publicSearchHttpContract() throws Exception {
  String token="ht"+UUID.randomUUID().toString().substring(0,8);published(token+"活動","場地",4,false,900);
  mvc.perform(get("/api/public/events").param("q",token).param("sort","price")).andExpect(status().isOk())
   .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].minPriceTwd").value(900)).andExpect(jsonPath("$.items[0].sessionCount").value(1));
  mvc.perform(get("/api/public/events").param("sort","random")).andExpect(status().isBadRequest());
  mvc.perform(get("/api/public/events").param("from","2026-13-01")).andExpect(status().isBadRequest());
 }
 private Venue venueOf(String token){return taxonomy.createVenue(manager,new VenueInput(UUID.randomUUID(),0L,"venue-"+token,"場館"+token,"台北","信義路","",null));}
 private Performer performerOf(String token){return taxonomy.createPerformer(manager,new PerformerInput(UUID.randomUUID(),0L,"artist-"+token+"-"+UUID.randomUUID().toString().substring(0,4),"歌手"+token,"",null));}
 private UUID categoryId(String slug){return taxonomy.categories().stream().filter(c->c.slug().equals(slug)).findFirst().orElseThrow().id();}
 @Test void categoriesAreSeededInOrder(){assertEquals(List.of("concert","theatre","sports","exhibition","family","other"),taxonomy.categories().stream().map(Category::slug).toList());}
 @Test void venueAndPerformerCommandsReplayAndRejectStale(){
  String token=UUID.randomUUID().toString().substring(0,8);var in=new VenueInput(UUID.randomUUID(),0L,"venue-"+token,"場館","台北","地址","",null);
  var v=taxonomy.createVenue(manager,in);assertEquals(v,taxonomy.createVenue(manager,in));
  problem(409,()->taxonomy.createVenue(manager,new VenueInput(in.requestId(),0L,in.slug(),"改名",in.city(),in.address(),"",null)));
  var updated=taxonomy.updateVenue(manager,v.id(),new VenueInput(UUID.randomUUID(),1L,v.slug(),"新場館","高雄","","","/images/v.png"));assertEquals(2,updated.version());assertEquals("/images/v.png",updated.imageUrl());
  problem(409,()->taxonomy.updateVenue(manager,v.id(),new VenueInput(UUID.randomUUID(),1L,v.slug(),"舊版本","高雄","","",null)));
  assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->taxonomy.createVenue(manager,new VenueInput(UUID.randomUUID(),0L,v.slug(),"重複","","","",null)));
  problem(403,()->taxonomy.createPerformer(viewer,new PerformerInput(UUID.randomUUID(),0L,"viewer-"+token,"不可寫","",null)));
  var p=performerOf(token);assertEquals(p,taxonomy.performer(p.slug()));assertEquals("新場館",taxonomy.venue(v.slug()).name());
 }
 @Test void eventProfileOrdersPerformersAndValidates(){
  String token="pf"+UUID.randomUUID().toString().substring(0,8);var e=published(token+"演唱會","場地",5,false,900);
  var empty=taxonomy.profile(e.id(),true);assertEquals(0,empty.version());assertNull(empty.category());assertTrue(empty.performers().isEmpty());
  var venue=venueOf(token);var a=performerOf(token);var b=performerOf(token);
  var in=new ProfileInput(UUID.randomUUID(),0L,categoryId("concert"),venue.id(),List.of(b.id(),a.id()),null);
  var profile=taxonomy.updateProfile(manager,e.id(),in);assertEquals(1,profile.version());assertEquals("concert",profile.category().slug());
  assertEquals(List.of(b.id(),a.id()),profile.performers().stream().map(Performer::id).toList());assertEquals(venue.id(),profile.venue().id());
  assertEquals(profile,taxonomy.updateProfile(manager,e.id(),in));assertEquals(profile,taxonomy.profile(e.id(),true));
  problem(409,()->taxonomy.updateProfile(manager,e.id(),new ProfileInput(UUID.randomUUID(),0L,null,null,List.of(),null)));
  problem(400,()->taxonomy.updateProfile(manager,e.id(),new ProfileInput(UUID.randomUUID(),1L,null,null,List.of(a.id(),a.id()),null)));
  problem(400,()->taxonomy.updateProfile(manager,e.id(),new ProfileInput(UUID.randomUUID(),1L,UUID.randomUUID(),null,List.of(),null)));
  problem(400,()->taxonomy.updateProfile(manager,e.id(),new ProfileInput(UUID.randomUUID(),1L,null,UUID.randomUUID(),List.of(),null)));
  var cleared=taxonomy.updateProfile(manager,e.id(),new ProfileInput(UUID.randomUUID(),1L,null,null,List.of(a.id()),""));assertEquals(2,cleared.version());assertNull(cleared.category());assertNull(cleared.imageUrl());assertEquals(1,cleared.performers().size());
  var draft=event();problem(404,()->taxonomy.profile(draft.id(),true));assertEquals(0,taxonomy.profile(draft.id(),false).version());
 }
 @Test void searchFiltersByCategoryVenueAndPerformer(){
  String token="tx"+UUID.randomUUID().toString().substring(0,8);var hit=published(token+"甲","場地",5,false,700);var other=published(token+"乙","場地",5,false,700);
  var venue=venueOf(token);var p=performerOf(token);
  taxonomy.updateProfile(manager,hit.id(),new ProfileInput(UUID.randomUUID(),0L,categoryId("theatre"),venue.id(),List.of(p.id()),"https://img.example/poster.jpg"));
  taxonomy.updateProfile(manager,other.id(),new ProfileInput(UUID.randomUUID(),0L,categoryId("sports"),null,List.of(),null));
  var byCategory=service.search(token,"theatre",null,null,null,null,PublicSort.NEWEST,false,1,20);assertEquals(List.of(hit.id()),ids(byCategory));
  assertEquals("音樂劇／戲劇",byCategory.items().getFirst().categoryName());assertEquals("https://img.example/poster.jpg",byCategory.items().getFirst().imageUrl());
  assertEquals(List.of(hit.id()),ids(service.search(null,null,venue.slug(),null,null,null,PublicSort.NEWEST,false,1,20)));
  assertEquals(List.of(hit.id()),ids(service.search(null,null,null,p.slug(),null,null,PublicSort.NEWEST,false,1,20)));
  assertEquals(0,service.search(token,"no-such",null,null,null,null,PublicSort.NEWEST,false,1,20).total());
  assertEquals(2,service.search(token,null,null,null,null,null,PublicSort.NEWEST,false,1,20).total());
 }
 @Test void taxonomyHttpContract() throws Exception {
  mvc.perform(get("/api/public/categories")).andExpect(status().isOk()).andExpect(jsonPath("$[0].slug").value("concert"));
  mvc.perform(get("/api/admin/venues")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/public/venues/no-such-venue")).andExpect(status().isNotFound());
  String bad=json.writeValueAsString(new VenueInput(UUID.randomUUID(),0L,"venue-js-"+UUID.randomUUID().toString().substring(0,6),"場館","","","","javascript:alert(1)"));
  mvc.perform(post("/api/admin/venues").with(user(manager,true)).with(SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json").content(bad)).andExpect(status().isBadRequest());
  String ok=json.writeValueAsString(new VenueInput(UUID.randomUUID(),0L,"venue-ok-"+UUID.randomUUID().toString().substring(0,6),"場館","","","",null));
  mvc.perform(post("/api/admin/venues").with(user(viewer,false)).with(SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json").content(ok)).andExpect(status().isForbidden());
  mvc.perform(post("/api/admin/venues").with(user(manager,true)).contentType("application/json").content(ok)).andExpect(status().isForbidden());
  mvc.perform(get("/api/public/events").param("category","concert")).andExpect(status().isOk());
 }
 private dev.jackteng.encore.customer.CustomerService.Principal member(){var issue=customers.register("m4-"+UUID.randomUUID().toString().substring(0,30),"合成會員",UUID.randomUUID().toString());return customers.authenticate(issue.token()).orElseThrow();}
 @Test void favoritesAreIdempotentPerMemberAndHideUnpublished(){
  var a=member();var b=member();var e=published("收藏"+UUID.randomUUID().toString().substring(0,6),"場地",5,true,600);
  assertTrue(engagement.setFavorite(a,e.id(),true).favorite());assertTrue(engagement.setFavorite(a,e.id(),true).favorite());
  assertEquals(1,engagement.favorites(a,1,12).total());assertEquals(0,engagement.favorites(b,1,12).total());assertFalse(engagement.flags(b,e.id()).favorite());
  var item=engagement.favorites(a,1,12).items().getFirst();assertEquals(600,item.minPriceTwd());assertTrue(item.selling());
  problem(404,()->engagement.setFavorite(a,event().id(),true));
  service.transition(manager,e.id(),new Transition(UUID.randomUUID(),2L),false);assertEquals(0,engagement.favorites(a,1,12).total());
  assertFalse(engagement.setFavorite(a,e.id(),false).favorite());
 }
 @Test void saleReminderBecomesSingleNotificationWhenSalesOpen(){
  var a=member();var e=published("提醒"+UUID.randomUUID().toString().substring(0,6),"場地",5,false,800);
  var flags=engagement.setReminder(a,e.id(),true);assertTrue(flags.reminder());assertTrue(flags.reminderAvailable());
  assertEquals(0,engagement.summary(a).unread());
  db.update("UPDATE event_session SET sales_start=now()-interval '1 hour' WHERE event_id=?",e.id());db.update("UPDATE sale_reminder SET due_at=now()-interval '1 hour' WHERE customer_id=?",a.id());
  assertEquals(1,engagement.summary(a).unread());assertEquals(1,engagement.summary(a).unread());
  var page=engagement.notifications(a,1,20);assertEquals(1,page.total());var n=page.items().getFirst();
  assertEquals("SALE_STARTED",n.kind());assertEquals("/events/"+e.id(),n.link());assertNull(n.readAt());
  assertFalse(engagement.flags(a,e.id()).reminder());problem(409,()->engagement.setReminder(a,e.id(),true));
  assertEquals(0,engagement.markRead(a,new dev.jackteng.encore.customer.EngagementService.MarkRead(List.of(n.id()),false)).unread());
  var other=member();assertEquals(0,engagement.markRead(other,new dev.jackteng.encore.customer.EngagementService.MarkRead(List.of(),true)).unread());
  assertNotNull(engagement.notifications(a,1,20).items().getFirst().readAt());assertEquals(0,engagement.notifications(other,1,20).total());
 }
 @Test void reminderWaitsForTheUpcomingOpeningAndSurvivesClosedSales(){
  var a=member();var e=published("雙場"+UUID.randomUUID().toString().substring(0,6),"場地",5,true,800);var now=OffsetDateTime.now();
  db.update("UPDATE event SET status='DRAFT' WHERE id=?",e.id());
  service.createSession(manager,e.id(),new SessionInput(UUID.randomUUID(),0L,"加場",now.plusDays(10),now.plusDays(2),now.plusDays(9)));
  db.update("UPDATE event SET status='PUBLISHED' WHERE id=?",e.id());
  assertTrue(engagement.setReminder(a,e.id(),true).reminder());assertEquals(0,engagement.summary(a).unread());
  var dueBefore=db.queryForObject("SELECT due_at FROM sale_reminder WHERE customer_id=? AND event_id=?",OffsetDateTime.class,a.id(),e.id());
  assertTrue(engagement.setReminder(a,e.id(),true).reminder());assertEquals(dueBefore,db.queryForObject("SELECT due_at FROM sale_reminder WHERE customer_id=? AND event_id=?",OffsetDateTime.class,a.id(),e.id()));
  db.update("UPDATE event_session SET sales_start=now()-interval '3 hour',sales_end=now()-interval '1 hour' WHERE event_id=? AND name='加場'",e.id());
  db.update("UPDATE sale_reminder SET due_at=now()-interval '3 hour' WHERE customer_id=? AND event_id=?",a.id(),e.id());
  assertEquals(1,engagement.summary(a).unread());assertFalse(engagement.flags(a,e.id()).reminder());
 }
 @Test void archivedFavoritesDoNotConsumeTheLimit(){
  var a=member();var keep=published("上限"+UUID.randomUUID().toString().substring(0,6),"場地",5,true,800);var archived=published("封存"+UUID.randomUUID().toString().substring(0,6),"場地",5,true,800);
  engagement.setFavorite(a,archived.id(),true);service.transition(manager,archived.id(),new Transition(UUID.randomUUID(),2L),false);
  db.update("INSERT INTO customer_favorite(customer_id,event_id) SELECT ?,id FROM event WHERE status='ARCHIVED' AND id<>? LIMIT ? ON CONFLICT DO NOTHING",a.id(),archived.id(),dev.jackteng.encore.customer.EngagementService.MAX_FAVORITES);
  assertTrue(engagement.setFavorite(a,keep.id(),true).favorite());assertEquals(1,engagement.favorites(a,1,12).total());
  assertFalse(engagement.setFavorite(a,archived.id(),false).favorite());
 }
 @Test void concurrentReadsCreateOneNotification() throws Exception {
  var a=member();var e=published("併發"+UUID.randomUUID().toString().substring(0,6),"場地",5,false,800);engagement.setReminder(a,e.id(),true);
  db.update("UPDATE event_session SET sales_start=now()-interval '1 hour' WHERE event_id=?",e.id());db.update("UPDATE sale_reminder SET due_at=now()-interval '1 hour' WHERE customer_id=?",a.id());
  try(var pool=Executors.newFixedThreadPool(4)){var tasks=new ArrayList<Callable<Long>>();for(int i=0;i<4;i++)tasks.add(()->engagement.summary(a).unread());for(var f:pool.invokeAll(tasks))assertEquals(1L,f.get());}
  assertEquals(1,db.queryForObject("SELECT count(*) FROM customer_notification WHERE customer_id=?",Long.class,a.id()));
 }
 @Test void engagementHttpRequiresMemberAndCsrf() throws Exception {
  var e=published("HTTP"+UUID.randomUUID().toString().substring(0,6),"場地",5,false,800);
  mvc.perform(get("/api/customer/notifications/summary")).andExpect(status().isUnauthorized());
  var issue=customers.register("m4h-"+UUID.randomUUID().toString().substring(0,28),"合成",UUID.randomUUID().toString());var cookie=new jakarta.servlet.http.Cookie(dev.jackteng.encore.customer.CustomerAuthenticationFilter.COOKIE,issue.token());
  mvc.perform(get("/api/customer/events/"+e.id()+"/engagement").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.reminderAvailable").value(true));
  mvc.perform(post("/api/customer/events/"+e.id()+"/favorite").cookie(cookie).contentType("application/json").content("{\"on\":true}")).andExpect(status().isForbidden());
  var csrf=mvc.perform(get("/api/customer/auth/csrf")).andExpect(status().isOk()).andReturn();var token=json.readTree(csrf.getResponse().getContentAsString());var csrfCookie=csrf.getResponse().getCookie("TICKET_CUSTOMER_CSRF");assertNotNull(csrfCookie);
  mvc.perform(post("/api/customer/events/"+e.id()+"/favorite").cookie(cookie,csrfCookie).header(token.get("headerName").asText(),token.get("token").asText()).contentType("application/json").content("{\"on\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.favorite").value(true));
  mvc.perform(post("/api/customer/events/"+e.id()+"/favorite").cookie(cookie,csrfCookie).header(token.get("headerName").asText(),token.get("token").asText()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
 }
 @Test void seatMapValidatesZonesAndReplays(){
  var e=published("座位"+UUID.randomUUID().toString().substring(0,6),"場地",5,true,800,1800);var d=service.detail(e.id(),true);var s=d.sessions().getFirst();
  var a=s.tickets().get(0).id();var b=s.tickets().get(1).id();
  assertTrue(seatMaps.forEvent(e.id()).isEmpty());assertEquals(0,seatMaps.forSession(s.id()).version());
  var in=new SeatMapService.SeatMapInput(UUID.randomUUID(),0L,"主舞台",List.of(new SeatMapService.Zone(a,"A 區",10,20,40,30),new SeatMapService.Zone(b,"B 區",50,20,40,30)));
  var map=seatMaps.update(manager,s.id(),in);assertEquals(1,map.version());assertEquals(2,map.zones().size());assertEquals(map,seatMaps.update(manager,s.id(),in));
  assertEquals(List.of(map),seatMaps.forEvent(e.id()));
  problem(409,()->seatMaps.update(manager,s.id(),new SeatMapService.SeatMapInput(UUID.randomUUID(),0L,"舞台",List.of())));
  problem(400,()->seatMaps.update(manager,s.id(),new SeatMapService.SeatMapInput(UUID.randomUUID(),1L,"舞台",List.of(new SeatMapService.Zone(a,"A",10,10,40,40),new SeatMapService.Zone(b,"B",30,30,40,40)))));
  problem(400,()->seatMaps.update(manager,s.id(),new SeatMapService.SeatMapInput(UUID.randomUUID(),1L,"舞台",List.of(new SeatMapService.Zone(a,"A",80,10,40,40)))));
  problem(400,()->seatMaps.update(manager,s.id(),new SeatMapService.SeatMapInput(UUID.randomUUID(),1L,"舞台",List.of(new SeatMapService.Zone(a,"A",0,0,10,10),new SeatMapService.Zone(a,"A2",50,50,10,10)))));
  var other=published("他場"+UUID.randomUUID().toString().substring(0,6),"場地",5,true,800);var foreign=service.detail(other.id(),true).sessions().getFirst().tickets().getFirst().id();
  problem(400,()->seatMaps.update(manager,s.id(),new SeatMapService.SeatMapInput(UUID.randomUUID(),1L,"舞台",List.of(new SeatMapService.Zone(foreign,"外",0,0,10,10)))));
  var cleared=seatMaps.update(manager,s.id(),new SeatMapService.SeatMapInput(UUID.randomUUID(),1L,"舞台",List.of(new SeatMapService.Zone(b,"搖滾區",0,60,100,40))));
  assertEquals(2,cleared.version());assertEquals(1,cleared.zones().size());
  var available=service.detail(e.id(),true).sessions().getFirst().tickets().stream().map(Ticket::available).toList();assertEquals(List.of(10,10),available);
  problem(403,()->seatMaps.update(viewer,s.id(),new SeatMapService.SeatMapInput(UUID.randomUUID(),2L,"舞台",List.of())));
  var draft=event();var ds=session(draft);problem(404,()->seatMaps.forEvent(draft.id()));assertEquals(0,seatMaps.forSession(ds.id()).version());
 }
 @Test void seatMapHttpContract() throws Exception {
  var e=published("座位HTTP"+UUID.randomUUID().toString().substring(0,6),"場地",5,true,800);
  mvc.perform(get("/api/public/events/"+e.id()+"/seat-maps")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
  var sid=service.detail(e.id(),true).sessions().getFirst().id();
  mvc.perform(get("/api/admin/sessions/"+sid+"/seat-map")).andExpect(status().isUnauthorized());
  String bad="{\"requestId\":\""+UUID.randomUUID()+"\",\"expectedVersion\":0,\"stageLabel\":\"舞台\",\"zones\":[{\"ticketTypeId\":\""+UUID.randomUUID()+"\",\"label\":\"A\",\"x\":0,\"y\":0,\"w\":0,\"h\":10}]}";
  mvc.perform(put("/api/admin/sessions/"+sid+"/seat-map").with(user(manager,true)).with(SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json").content(bad)).andExpect(status().isBadRequest());
 }
}
