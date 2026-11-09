package dev.elpsy.ticket;

import dev.elpsy.ticket.catalog.CatalogService;
import dev.elpsy.ticket.catalog.Models.*;
import dev.elpsy.ticket.customer.CustomerService;
import dev.elpsy.ticket.customer.CustomerService.Principal;
import dev.elpsy.ticket.orders.*;
import dev.elpsy.ticket.orders.OrderModels.*;
import dev.elpsy.ticket.orders.OrderModels.Order;
import dev.elpsy.ticket.orders.PaymentModels.*;
import dev.elpsy.ticket.tickets.*;
import dev.elpsy.ticket.tickets.AdmissionWindowModels.*;
import dev.elpsy.ticket.tickets.TicketModels.*;
import dev.elpsy.ticket.common.ApiErrors.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(properties={"ticket.delivery.enabled=false","ticket.orders.enabled=true","ticket.mock-payments.enabled=true","ticket.issuance.enabled=true","ticket.check-in.enabled=true","ticket.admission-window.enabled=true","ticket.admission.enabled=false","ticket.expiry.enabled=false","ticket.stock-return-recovery.enabled=false"})
@AutoConfigureMockMvc @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdmissionWindowIntegrationTest {
 @Autowired AdmissionWindowService windows;@Autowired TicketService tickets;
 @Autowired OrderService orders;@Autowired PaymentService payments;@Autowired CatalogService catalog;
 @Autowired CustomerService customers;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;
 @Autowired PlatformTransactionManager transactions;@Autowired MockMvc mvc;
 String manager="window-manager-"+UUID.randomUUID(),other="window-other-"+UUID.randomUUID(),viewer="window-viewer-"+UUID.randomUUID();
 record Fixture(Principal owner,String token,UUID session,Order order,List<CustomerTicket> units) {}
 @BeforeAll void seed() {assertEquals("ticket_demo",db.queryForObject("SELECT current_database()",String.class));for(String actor:List.of(manager,other,viewer))db.update("INSERT INTO staff_account VALUES(?,?,?,?,true)",UUID.randomUUID(),actor,"synthetic unused hash",actor.equals(viewer)?"VIEWER":"MANAGER");}
 Fixture fixture() {
  var c=customers.register("e4-"+UUID.randomUUID().toString().substring(0,30),"合成窗口",UUID.randomUUID().toString());var owner=customers.authenticate(c.token()).orElseThrow();var time=OffsetDateTime.now();
  var e=catalog.createEvent(manager,new EventInput(UUID.randomUUID(),0L,"e4-"+UUID.randomUUID(),"窗口活動","不收款","合成場地"));
  var s=catalog.createSession(manager,e.id(),new SessionInput(UUID.randomUUID(),0L,"窗口場次",time.plusDays(2),time.minusHours(1),time.plusHours(1)));
  var t=catalog.createTicket(manager,s.id(),new TicketInput(UUID.randomUUID(),0L,"一般票",1200,20,10));catalog.transition(manager,e.id(),new Transition(UUID.randomUUID(),1L),true);
  var o=orders.place(owner,new Place(UUID.randomUUID(),t.id(),2,1,2400));var p=payments.start(owner,o.id(),new Pay(UUID.randomUUID(),1,2400,"SUCCESS"));o=payments.reconcile(owner,o.id(),p.payment().id(),new Reconcile(UUID.randomUUID()));
  return new Fixture(owner,c.token(),s.id(),o,tickets.issue(owner,o.id(),new Issue(UUID.randomUUID(),3)).tickets());
 }
 String utc(Instant t) {return t.truncatedTo(ChronoUnit.SECONDS).toString();}
 Configure body(long version,long start,long end) {return new Configure(UUID.randomUUID(),version,utc(Instant.now().plusSeconds(start)),utc(Instant.now().plusSeconds(end)));}
 Recovery open(Fixture f) {return windows.configure(manager,f.session(),body(0,-3600,3600));}
 CheckIn check(Fixture f,int i) {return new CheckIn(UUID.randomUUID(),f.units().get(i).code(),f.session(),1);}
 int count(String table,UUID request) {return db.queryForObject("SELECT count(*) FROM "+table+" WHERE request_id=?",Integer.class,request);}
 String source(Fixture f) {return db.queryForObject("SELECT jsonb_build_array(to_jsonb(o),to_jsonb(t),(SELECT jsonb_agg(to_jsonb(x) ORDER BY phase) FROM order_inventory_event x WHERE order_id=o.id),(SELECT jsonb_agg(to_jsonb(x)) FROM payment_inventory_event x WHERE order_id=o.id),(SELECT jsonb_agg(to_jsonb(x) ORDER BY revision) FROM stock_revision x WHERE ticket_id=t.id))::text FROM ticket_order o JOIN ticket_type t ON t.id=o.ticket_id WHERE o.id=?",String.class,f.order().id());}
 void problem(String code,Runnable task) {assertEquals(code,assertThrows(Problem.class,task::run).code);}
 @Test void unconfiguredIsFailClosedButInspectReadAndRevocationRemain() {
  var f=fixture();assertEquals("UNCONFIGURED",windows.status(viewer,f.session()).state());assertTrue(tickets.inspect(manager,new Inspect(f.units().getFirst().code(),f.session())).eligible());
  var b=check(f,0);problem("ADMISSION_WINDOW_UNCONFIGURED",()->tickets.checkIn(manager,b));assertEquals(0,count("ticket_admission_command",b.requestId()));assertEquals("UNKNOWN",tickets.checkInReceipt(manager,b).state());
  var revoked=tickets.revoke(manager,new Revoke(UUID.randomUUID(),f.units().getFirst().id(),f.session(),1,"SECURITY"));assertEquals("REVOKED",revoked.ticket().state());assertNull(revoked.windowProof());
 }
 @Test void intervalUsesInclusiveOpenExclusiveCloseEvenAtExactBoundaries() {
  var start=OffsetDateTime.parse("2026-10-11T12:00:00Z");var end=start.plusHours(1);
  for(var entry:Map.of(start.minusNanos(1000),false,start,true,end.minusNanos(1000),true,end,false,end.plusNanos(1000),false).entrySet())
   assertEquals(entry.getValue(),db.queryForObject("SELECT ticket_window_contains(?,?,?)",Boolean.class,start,end,entry.getKey()));
 }
 @Test void futureAndClosedWindowsRefuseNewCommandsWithoutStockMutation() {
  var f=fixture();var before=source(f);windows.configure(manager,f.session(),body(0,3600,7200));var c=check(f,0);problem("ADMISSION_WINDOW_NOT_OPEN",()->tickets.checkIn(manager,c));windows.configure(manager,f.session(),body(1,-7200,-3600));problem("ADMISSION_WINDOW_CLOSED",()->tickets.checkIn(manager,c));assertEquals(0,count("ticket_admission_command",c.requestId()));assertEquals(before,source(f));
 }
 @Test void successfulAdmissionStoresExactCurrentPolicyClockAndAuditInOneTransaction() {
  var f=fixture();var before=source(f);var window=open(f);var b=check(f,0);var r=tickets.checkIn(manager,b);assertNotNull(r.windowProof());assertEquals(window.policy().version(),r.windowProof().policyVersion());assertEquals(f.session(),r.windowProof().sessionId());assertEquals(r.ticket().changedAt(),r.windowProof().checkedAt());assertEquals(1,count("ticket_window_proof",b.requestId()));assertEquals(1,count("ticket_admission_audit",b.requestId()));assertEquals(before,source(f));
 }
 @Test void closingOrReplacingPolicyNeverChangesOriginalReceiptAndRevokeWorksClosed() {
  var f=fixture();open(f);var c=check(f,0);var receipt=tickets.checkIn(manager,c);windows.configure(manager,f.session(),body(1,-7200,-3600));assertEquals(receipt,tickets.checkIn(manager,c));assertEquals(receipt,tickets.checkInReceipt(manager,c).receipt());assertEquals(receipt,tickets.stopCheckIn(manager,c).receipt());var revoke=tickets.revoke(manager,new Revoke(UUID.randomUUID(),f.units().get(1).id(),f.session(),1,"LOST_CODE"));assertNull(revoke.windowProof());assertEquals("REVOKED",revoke.ticket().state());
 }
 @Test void exactWindowReplayAndStaleVersionsCannotOverwriteOtherPolicy() {
  var f=fixture();var b=body(0,-3600,3600);var first=windows.configure(manager,f.session(),b);assertEquals(first,windows.configure(manager,f.session(),b));windows.configure(manager,f.session(),body(1,3600,7200));assertEquals(first,windows.configure(manager,f.session(),b));assertEquals(2,windows.status(viewer,f.session()).version());problem("STALE_VERSION",()->windows.configure(manager,f.session(),body(0,-3600,3600)));problem("REQUEST_CONFLICT",()->windows.receipt(manager,f.session(),new Configure(b.requestId(),0,b.opensAt(),utc(Instant.now().plusSeconds(8000)))));
 }
 @Test void windowUnknownLookupAndPermanentStopAreExactAndHaveNoPolicyEffect() {
  var f=fixture();var before=source(f);var b=body(99,3600,-3600);assertEquals("UNKNOWN",windows.receipt(manager,f.session(),b).state());assertEquals(0,count("ticket_window_command",b.requestId()));var stop=windows.stop(manager,f.session(),b);assertEquals("FENCED",stop.state());assertEquals(stop,windows.stop(manager,f.session(),b));assertEquals(stop,windows.receipt(manager,f.session(),b));problem("REQUEST_FENCED",()->windows.configure(manager,f.session(),b));assertEquals(0,windows.status(viewer,f.session()).version());assertEquals(1,count("ticket_window_audit",b.requestId()));assertEquals(before,source(f));
 }
 @Test void appliedStopReturnsWholeOriginalPolicyWithNoSecondWrite() {var f=fixture();var b=body(0,-3600,3600);var r=windows.configure(manager,f.session(),b);assertEquals(r,windows.stop(manager,f.session(),b));assertEquals(r,windows.receipt(manager,f.session(),b));assertEquals(1,count("ticket_window_audit",b.requestId()));assertEquals(1,windows.status(viewer,f.session()).version());}
 @Test void disabledFeatureStillAllowsReadOriginalReceiptAndStop() {
  var f=fixture();var b=body(0,-3600,3600);var applied=windows.configure(manager,f.session(),b);var tx=new TransactionTemplate(transactions);var off=new AdmissionWindowService(db,json,false);assertEquals(applied,tx.execute(s->off.configure(manager,f.session(),b)));assertEquals(applied,tx.execute(s->off.stop(manager,f.session(),b)));var unknown=body(45,-7200,-3600);assertEquals("FENCED",tx.execute(s->off.stop(manager,f.session(),unknown)).state());problem("WINDOW_MANAGEMENT_DISABLED",()->tx.execute(s->off.configure(manager,f.session(),body(1,-3600,3600))));
 }
 @Test void originalActorRoleAndScopeAreBoundAndViewerIsReadOnly() {
  var f=fixture();var b=body(0,-3600,3600);windows.configure(manager,f.session(),b);problem("NOT_FOUND",()->windows.receipt(other,f.session(),b));problem("FORBIDDEN",()->windows.stop(viewer,f.session(),b));problem("REQUEST_CONFLICT",()->windows.receipt(manager,fixture().session(),b));assertEquals(1,windows.audit(viewer,f.session(),1,20).total());
 }
 @Test void windowAuditFailureRollsBackPolicyAndCommandThenOriginalRetrySucceeds() {
  var f=fixture();var b=body(0,-3600,3600);db.execute("ALTER TABLE ticket_window_audit ADD CONSTRAINT e4_window_fault CHECK(request_id<>'"+b.requestId()+"'::uuid) NOT VALID");try {assertThrows(DataIntegrityViolationException.class,()->windows.configure(manager,f.session(),b));assertEquals("UNKNOWN",windows.receipt(manager,f.session(),b).state());assertEquals(0,windows.status(viewer,f.session()).version());}finally {db.execute("ALTER TABLE ticket_window_audit DROP CONSTRAINT e4_window_fault");}assertEquals("APPLIED",windows.configure(manager,f.session(),b).state());
 }
 @Test void fenceAuditFailureDoesNotPermanentlyStopTheRequest() {
  var f=fixture();var b=body(0,-3600,3600);db.execute("ALTER TABLE ticket_window_audit ADD CONSTRAINT e4_fence_fault CHECK(request_id<>'"+b.requestId()+"'::uuid) NOT VALID");try {assertThrows(DataIntegrityViolationException.class,()->windows.stop(manager,f.session(),b));assertEquals("UNKNOWN",windows.receipt(manager,f.session(),b).state());}finally {db.execute("ALTER TABLE ticket_window_audit DROP CONSTRAINT e4_fence_fault");}assertEquals("APPLIED",windows.configure(manager,f.session(),b).state());
 }
 @Test void admissionProofFailureRollsBackTicketCommandAndBothAudits() {
  var f=fixture();open(f);var b=check(f,0);db.execute("ALTER TABLE ticket_window_proof ADD CONSTRAINT e4_proof_fault CHECK(request_id<>'"+b.requestId()+"'::uuid) NOT VALID");try {assertThrows(DataIntegrityViolationException.class,()->tickets.checkIn(manager,b));assertEquals(0,count("ticket_admission_command",b.requestId()));assertEquals(0,count("ticket_admission_audit",b.requestId()));assertEquals("ACTIVE",tickets.inspect(manager,new Inspect(b.code(),f.session())).state());}finally {db.execute("ALTER TABLE ticket_window_proof DROP CONSTRAINT e4_proof_fault");}assertNotNull(tickets.checkIn(manager,b).windowProof());
 }
 @Test void immutableEvidenceAndLegacyClassificationCannotBeForged() {
  var f=fixture();var w=open(f);var c=check(f,0);tickets.checkIn(manager,c);for(String table:List.of("ticket_window_command","ticket_window_policy","ticket_window_audit","ticket_window_proof"))assertThrows(DataIntegrityViolationException.class,()->db.update("DELETE FROM "+table+" WHERE request_id=?",table.equals("ticket_window_proof")?c.requestId():w.requestId()));assertThrows(DataIntegrityViolationException.class,()->db.update("INSERT INTO ticket_window_legacy VALUES(?)",c.requestId()));assertThrows(DataIntegrityViolationException.class,()->db.update("UPDATE ticket_window_policy SET closes_at=closes_at+interval '1 hour' WHERE request_id=?",w.requestId()));
 }
 @Test void concurrentSameVersionSettingsAllowOnlyOneRevision()throws Exception {
  var f=fixture();try(var pool=Executors.newFixedThreadPool(4)) {var jobs=new ArrayList<Callable<Boolean>>();for(int i=0;i<4;i++)jobs.add(()->{try {windows.configure(manager,f.session(),body(0,-3600,3600));return true;}catch(Problem p){assertEquals("STALE_VERSION",p.code);return false;}});int ok=0;for(var r:pool.invokeAll(jobs))if(r.get())ok++;assertEquals(1,ok);}assertEquals(1,windows.status(viewer,f.session()).version());
 }
 @Test void sameUuidApplyStopRaceChoosesAppliedOrFencedWithOneAudit()throws Exception {
  var f=fixture();var b=body(0,-3600,3600);try(var pool=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);var apply=pool.submit(()->{start.await();try {windows.configure(manager,f.session(),b);return true;}catch(Problem p){assertEquals("REQUEST_FENCED",p.code);return false;}});var stop=pool.submit(()->{start.await();return windows.stop(manager,f.session(),b);});start.countDown();boolean ok=apply.get(15,TimeUnit.SECONDS);assertEquals(ok?"APPLIED":"FENCED",stop.get(15,TimeUnit.SECONDS).state());assertEquals(ok?1:0,windows.status(viewer,f.session()).version());}assertEquals(1,count("ticket_window_audit",b.requestId()));
 }
 void waiting(String fragment) {for(int i=0;i<100;i++){db.execute("SELECT pg_stat_clear_snapshot()");if(db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?",Integer.class,"%"+fragment+"%")>0)return;try {Thread.sleep(30);}catch(InterruptedException e){throw new AssertionError(e);}}fail("Actual SQL lock wait required");}
 @Test void ticketLockWaitCannotCarryInspectionPastRealDbClosingTime()throws Exception {
  var f=fixture();var close=Instant.now().plusSeconds(5).truncatedTo(ChronoUnit.SECONDS);windows.configure(manager,f.session(),new Configure(UUID.randomUUID(),0,utc(Instant.now().minusSeconds(30)),utc(close)));var b=check(f,0);try(var pool=Executors.newSingleThreadExecutor()) {
   var future=new TransactionTemplate(transactions).execute(s->{db.queryForList("SELECT id FROM issued_ticket WHERE id=? FOR UPDATE",f.units().getFirst().id());var job=pool.submit(()->tickets.checkIn(manager,b));waiting("issued_ticket WHERE id");while(!db.queryForObject("SELECT clock_timestamp()>=?",Boolean.class,close.atOffset(ZoneOffset.UTC)))try {Thread.sleep(30);}catch(InterruptedException e){throw new AssertionError(e);}return job;});
   var error=assertThrows(ExecutionException.class,()->future.get(10,TimeUnit.SECONDS));assertEquals("ADMISSION_WINDOW_CLOSED",((Problem)error.getCause()).code);
  }assertEquals(0,count("ticket_admission_command",b.requestId()));assertEquals("ACTIVE",tickets.inspect(manager,new Inspect(b.code(),f.session())).state());
 }
 @Test void waitingForPolicyWriterReadsCommittedReplacementAfterLock()throws Exception {
  var f=fixture();open(f);var b=check(f,0);try(var pool=Executors.newSingleThreadExecutor()) {
   var future=new TransactionTemplate(transactions).execute(s->{windows.configure(manager,f.session(),body(1,-7200,-3600));var job=pool.submit(()->tickets.checkIn(manager,b));waiting("event_session WHERE id");assertFalse(job.isDone());return job;});
   var error=assertThrows(ExecutionException.class,()->future.get(10,TimeUnit.SECONDS));assertEquals("ADMISSION_WINDOW_CLOSED",((Problem)error.getCause()).code);
  }assertEquals(0,count("ticket_window_proof",b.requestId()));
 }
 @Test void directSqlCannotBypassClosedWindowOrPreinsertTimeProof() {
  var f=fixture();windows.configure(manager,f.session(),body(0,-7200,-3600));var b=check(f,0);UUID actor=db.queryForObject("SELECT id FROM staff_account WHERE username=?",UUID.class,manager);
  assertThrows(DataIntegrityViolationException.class,()->new TransactionTemplate(transactions).execute(s->{db.update("INSERT INTO ticket_admission_command(request_id,actor_id,ticket_id,session_id,expected_version,action,fingerprint) VALUES(?,?,?,?,1,'CHECK_IN',?)",b.requestId(),actor,f.units().getFirst().id(),f.session(),"0".repeat(64));db.update("UPDATE issued_ticket SET state='CHECKED_IN',version=2,changed_at=clock_timestamp(),changed_request_id=? WHERE id=?",b.requestId(),f.units().getFirst().id());return null;}));assertEquals(0,count("ticket_admission_command",b.requestId()));
  windows.configure(manager,f.session(),body(1,-3600,3600));
  assertThrows(DataIntegrityViolationException.class,()->new TransactionTemplate(transactions).execute(s->{db.update("INSERT INTO ticket_admission_command(request_id,actor_id,ticket_id,session_id,expected_version,action,fingerprint) VALUES(?,?,?,?,1,'CHECK_IN',?)",b.requestId(),actor,f.units().getFirst().id(),f.session(),"0".repeat(64));db.update("INSERT INTO ticket_window_proof(request_id,ticket_id,session_id,policy_version,checked_at) VALUES(?,?,?,2,clock_timestamp())",b.requestId(),f.units().getFirst().id(),f.session());db.update("UPDATE issued_ticket SET state='CHECKED_IN',version=2,changed_at=clock_timestamp(),changed_request_id=? WHERE id=?",b.requestId(),f.units().getFirst().id());return null;}));assertEquals(0,count("ticket_window_proof",b.requestId()));
 }
 @Test void invalidTimeRangesAndIncompletePolicyCannotCommit() {
  var f=fixture();problem("INVALID_WINDOW",()->windows.configure(manager,f.session(),body(0,3600,-3600)));problem("INVALID_INPUT",()->windows.configure(manager,f.session(),new Configure(UUID.randomUUID(),0,"2026-02-30T00:00:00Z","2026-03-01T00:00:00Z")));var b=body(0,-3600,3600);UUID actor=db.queryForObject("SELECT id FROM staff_account WHERE username=?",UUID.class,manager);assertThrows(DataIntegrityViolationException.class,()->new TransactionTemplate(transactions).execute(s->{db.update("INSERT INTO ticket_window_command(request_id,actor_id,session_id,expected_version,opens_at_text,closes_at_text,fingerprint,state,policy_version) VALUES(?,?,?,0,?,? ,?,'APPLIED',1)",b.requestId(),actor,f.session(),b.opensAt(),b.closesAt(),"0".repeat(64));return null;}));assertEquals(0,count("ticket_window_command",b.requestId()));
 }
 @Test void httpRequiresCsrfExpectedActorStrictBodyAndOwnerWindowAccess()throws Exception {
  var f=fixture();var b=body(0,-3600,3600);String path="/api/admin/admissions/windows/"+f.session(),raw=json.writeValueAsString(b);var actor=user(manager).authorities(()->"CATALOG_READ",()->"CATALOG_WRITE");
  mvc.perform(post(path).with(actor).contentType("application/json").content(raw)).andExpect(status().isForbidden());
  mvc.perform(post(path).with(actor).with(csrf()).header("X-Expected-Staff-Username",other).contentType("application/json").content(raw)).andExpect(status().isUnauthorized());
  mvc.perform(post(path).with(actor).with(csrf()).contentType("application/json").content(raw.replace("}",",\"actor\":\"spoof\"}"))).andExpect(status().isBadRequest());
  mvc.perform(post(path).with(user(viewer).authorities(()->"CATALOG_READ")).with(csrf()).contentType("application/json").content(raw)).andExpect(status().isForbidden());
  mvc.perform(get("/api/customer/orders/"+f.order().id()+"/admission-window").cookie(new Cookie("TICKET_CUSTOMER_SESSION",f.token()))).andExpect(status().isOk());
  var another=fixture();problem("NOT_FOUND",()->windows.customerStatus(another.owner(),f.order().id()));
 }
}
