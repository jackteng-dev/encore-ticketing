package dev.jackteng.encore.delivery;
import dev.jackteng.encore.catalog.*;
import dev.jackteng.encore.catalog.Models.*;
import dev.jackteng.encore.customer.CustomerService;
import dev.jackteng.encore.customer.CustomerService.Principal;
import dev.jackteng.encore.orders.*;
import dev.jackteng.encore.orders.OrderModels.*;
import dev.jackteng.encore.orders.PurchaseModels.Purchase;
import com.rabbitmq.client.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.core.*;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"ticket.delivery.enabled=true","ticket.delivery.publisher-enabled=false","ticket.delivery.consumer-enabled=false","ticket.delivery.namespace=encore-m4b-tests-v2","ticket.delivery.retry-delay-ms=200","ticket.delivery.confirm-timeout-ms=1000","ticket.delivery.lease-seconds=10","ticket.orders.enabled=true","ticket.purchases.enabled=true","ticket.purchases.deadline-seconds=120","ticket.admission.enabled=false","ticket.expiry.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeliveryIntegrationTest {
 @Autowired JdbcTemplate db;@Autowired PurchaseService purchases;@Autowired CatalogService catalog;@Autowired CustomerService customers;
 @Autowired OutboxStore store;@Autowired OutboxPublisher publisher;@Autowired PurchaseConsumer consumer;@Autowired ConfirmedSender sender;@Autowired DeliverySettings settings;
 @Autowired Environment env;@Autowired ObjectMapper json;@Autowired PlatformTransactionManager transactions;@Autowired RabbitAdmin admin;
 @Autowired dev.jackteng.encore.inventory.StockHoldService stockHolds;@Autowired dev.jackteng.encore.inventory.StockProjectionService stockProjections;@Autowired dev.jackteng.encore.inventory.StockSettlementService stockSettlements;
 @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;@Autowired dev.jackteng.encore.admission.AdmissionSettings admissionSettings;
 final Set<String> stockKeys=new HashSet<>();
 String manager="delivery-manager-"+UUID.randomUUID();Connection nativeConnection;
 record Fixture(Principal owner,Ticket ticket,Place body,Purchase purchase,UUID event) {}
 @BeforeAll void seed()throws Exception {
  assertEquals("ticket_demo",db.queryForObject("SELECT current_database()",String.class));
  db.update("INSERT INTO staff_account VALUES(?,?,?,?,true)",UUID.randomUUID(),manager,"synthetic unused hash","MANAGER");
  admin.initialize();
  var f=new com.rabbitmq.client.ConnectionFactory();f.setHost(env.getProperty("spring.rabbitmq.host"));f.setPort(Integer.parseInt(env.getProperty("spring.rabbitmq.port")));f.setUsername(env.getProperty("spring.rabbitmq.username"));f.setPassword(env.getProperty("spring.rabbitmq.password"));f.setVirtualHost(env.getProperty("spring.rabbitmq.virtual-host"));f.setAutomaticRecoveryEnabled(false);nativeConnection=f.newConnection("ticket-m4b-real-tests");
 }
 @AfterAll void close()throws Exception {if(nativeConnection!=null)nativeConnection.close();}
 @BeforeEach void isolate()throws Exception {
  // All rows in this disposable ticket_demo are synthetic. Keep earlier test backlog away from focused claims.
  db.update("UPDATE outbox_publication SET next_attempt_at=clock_timestamp()+interval '1 day' WHERE published_at IS NULL");
  try(var ch=nativeConnection.createChannel()){for(var q:List.of(settings.queue(),settings.retryQueue(),settings.deadQueue()))ch.queuePurge(q);}
 }
 @AfterEach void cleanupStockKeys(){redis.delete(stockKeys);stockKeys.clear();}
 Fixture fixture(){return fixture(false);}
 Fixture fixture(boolean stock){
  var issue=customers.register("mq-"+UUID.randomUUID().toString().substring(0,30),"合成 MQ",UUID.randomUUID().toString());var owner=customers.authenticate(issue.token()).orElseThrow();
  var e=catalog.createEvent(manager,new EventInput(UUID.randomUUID(),0L,"delivery-"+UUID.randomUUID(),"傳遞演出","合成","隔離"));var end=OffsetDateTime.now().plusHours(1);
  var s=catalog.createSession(manager,e.id(),new SessionInput(UUID.randomUUID(),0L,"合成",end.plusDays(1),OffsetDateTime.now().minusHours(1),end));var t=catalog.createTicket(manager,s.id(),new TicketInput(UUID.randomUUID(),0L,"一般票",1200,2,2));catalog.transition(manager,e.id(),new Transition(UUID.randomUUID(),1L),true);
  var b=new Place(UUID.randomUUID(),t.id(),1,1,1200);Purchase p;
  if(stock){var rebuild=stockProjections.begin(t.id(),UUID.randomUUID(),1);stockKeys.add(dev.jackteng.encore.inventory.RedisTicketLedger.epochs(redis,json,admissionSettings.namespace()).key(t.id(),rebuild.generation()));assertEquals("READY",stockProjections.complete(rebuild.requestId()).state());var intent=stockHolds.prepare(owner,b);assertEquals("HELD",stockHolds.apply(intent.id()).state());p=purchases.acceptHold(owner,intent.id());}
  else p=purchases.submit(owner,b);
  var event=db.queryForObject("SELECT id FROM purchase_outbox WHERE purchase_id=?",UUID.class,p.id());return new Fixture(owner,t,b,p,event);
 }
 OutboxStore.Claim claim(Fixture f){var c=store.claim(10).orElseThrow();assertEquals(f.event(),c.eventId());return c;}
 Message get(Channel ch,String queue)throws Exception {
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  while(System.nanoTime()<deadline){var r=ch.basicGet(queue,false);if(r!=null){var p=new MessageProperties();p.setDeliveryTag(r.getEnvelope().getDeliveryTag());p.setRedelivered(r.getEnvelope().isRedeliver());p.setContentType(r.getProps().getContentType());p.setMessageId(r.getProps().getMessageId());if(r.getProps().getHeaders()!=null)p.getHeaders().putAll(r.getProps().getHeaders());return new Message(r.getBody(),p);}Thread.sleep(30);}
  throw new AssertionError("Real broker delivery did not arrive in "+queue);
 }
 void send(Message m){assertEquals("CONFIRMED",sender.send(settings.exchange(),"purchase",m));}
 void oneHold(Fixture f){assertEquals("RESERVED",purchases.detail(f.owner(),f.purchase().id()).status());assertEquals(1,db.queryForObject("SELECT held FROM ticket_type WHERE id=?",Integer.class,f.ticket().id()));assertEquals(1,db.queryForObject("SELECT count(*) FROM ticket_order WHERE customer_id=?",Integer.class,f.owner().id()));assertEquals(1,db.queryForObject("SELECT count(*) FROM order_inventory_event WHERE ticket_id=? AND phase='HOLD'",Integer.class,f.ticket().id()));}
 @Test void persistentPublishConfirmMarksOnlyDeliveryReceiptAndConsumerReserves()throws Exception {
  var f=fixture();var c=claim(f);assertEquals("CONFIRMED",publisher.publish(c));assertNotNull(db.queryForObject("SELECT published_at FROM outbox_publication WHERE event_id=?",OffsetDateTime.class,f.event()));assertEquals("PROCESSING",purchases.detail(f.owner(),f.purchase().id()).status());
  try(var ch=nativeConnection.createChannel()){var m=get(ch,settings.queue());assertEquals(f.event().toString(),m.getMessageProperties().getMessageId());consumer.onMessage(m,ch);assertEquals(0,ch.queueDeclarePassive(settings.queue()).getMessageCount());}oneHold(f);
 }
 @Test void offlineRestoreReplayAfterBrokerLossKeepsOriginalReceiptAndOneOrder()throws Exception {
  var f=fixture();assertEquals("CONFIRMED",publisher.publish(claim(f)));
  String original=db.queryForObject("SELECT row_to_json(d)::text FROM outbox_publication d WHERE event_id=?",String.class,f.event());
  try(var ch=nativeConnection.createChannel()){ch.queuePurge(settings.queue());}
  var replay=new dev.jackteng.encore.operations.PendingPurchaseReplay(db,sender,settings,json);
  for(int i=0;i<2;i++){var result=replay.replay(List.of(f.event()));assertEquals(1,result.confirmed());assertEquals(0,result.unknown());}
  assertEquals(original,db.queryForObject("SELECT row_to_json(d)::text FROM outbox_publication d WHERE event_id=?",String.class,f.event()));
  try(var ch=nativeConnection.createChannel()){
   for(int i=0;i<2;i++){var message=get(ch,settings.queue());assertEquals(f.event().toString(),message.getMessageProperties().getMessageId());consumer.onMessage(message,ch);}
  }
  oneHold(f);assertEquals(0,replay.replay(List.of(f.event())).confirmed());
 }
 @Test void offlineReplaySkipsUnpublishedCancelledAndUnrelatedEvents()throws Exception {
  var done=fixture();assertEquals("CONFIRMED",publisher.publish(claim(done)));
  purchases.cancel(done.owner(),done.purchase().id(),new Cancel(UUID.randomUUID(),1));var unpublished=fixture();
  var replay=new dev.jackteng.encore.operations.PendingPurchaseReplay(db,sender,settings,json);
  int before;try(var ch=nativeConnection.createChannel()){before=ch.queueDeclarePassive(settings.queue()).getMessageCount();}
  var result=replay.replay(List.of(done.event(),unpublished.event(),UUID.randomUUID()));
  assertEquals(3,result.skipped());assertEquals(0,result.confirmed());assertEquals(0,result.unknown());
  try(var ch=nativeConnection.createChannel()){assertEquals(before,ch.queueDeclarePassive(settings.queue()).getMessageCount());}
  assertEquals(0,db.queryForObject("SELECT count(*) FROM ticket_order WHERE customer_id IN (?,?)",Integer.class,done.owner().id(),unpublished.owner().id()));
 }
 @Test void realBrokerWorkerCommitsLinkedStockDecisionBeforeAck()throws Exception {
  var f=fixture(true);assertEquals("CONFIRMED",publisher.publish(claim(f)));
  try(var ch=nativeConnection.createChannel()){consumer.onMessage(get(ch,settings.queue()),ch);}oneHold(f);
  var id=db.queryForObject("SELECT intent_id FROM stock_hold_acceptance WHERE purchase_id=?",UUID.class,f.purchase().id());
  assertEquals("COMMIT",db.queryForObject("SELECT target FROM stock_hold_decision WHERE intent_id=?",String.class,id));
  assertEquals("WAITING",db.queryForObject("SELECT state FROM stock_recovery_job WHERE intent_id=? AND kind='SETTLEMENT'",String.class,id));
  assertEquals("COMMITTED",stockSettlements.reconcile(id).resolution());
 }
 @Test void linkedStockDecisionSurvivesRealCommitBeforeAckRedelivery()throws Exception {
  var f=fixture(true);send(publisher.message(claim(f)));var ch=nativeConnection.createChannel();var m=get(ch,settings.queue());m.getMessageProperties().setDeliveryTag(m.getMessageProperties().getDeliveryTag()+1000);consumer.onMessage(m,ch);
  for(int i=0;i<100&&ch.isOpen();i++)Thread.sleep(10);assertFalse(ch.isOpen());oneHold(f);
  try(var recovered=nativeConnection.createChannel()){var again=get(recovered,settings.queue());assertTrue(again.getMessageProperties().isRedelivered());consumer.onMessage(again,recovered);}oneHold(f);
  var id=db.queryForObject("SELECT intent_id FROM stock_hold_acceptance WHERE purchase_id=?",UUID.class,f.purchase().id());assertEquals(1,db.queryForObject("SELECT count(*) FROM stock_hold_decision WHERE intent_id=?",Integer.class,id));assertEquals(1,db.queryForObject("SELECT count(*) FROM stock_recovery_job WHERE intent_id=? AND kind='SETTLEMENT'",Integer.class,id));assertEquals("COMMITTED",stockSettlements.reconcile(id).resolution());
 }
 @Test void skipLockedConcurrentClaimsHaveDistinctFencingTokens()throws Exception {
  fixture();fixture();var second=new OutboxStore(db);var start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(6)){var calls=new ArrayList<Callable<Optional<OutboxStore.Claim>>>();for(int i=0;i<6;i++){boolean other=i%2==1;calls.add(()->{start.await();return other?new TransactionTemplate(transactions).execute(tx->second.claim(10)):store.claim(10);});}var futures=new ArrayList<Future<Optional<OutboxStore.Claim>>>();for(var call:calls)futures.add(pool.submit(call));start.countDown();var events=new HashSet<UUID>();var tokens=new HashSet<UUID>();int n=0;for(var future:futures){var c=future.get();if(c.isPresent()){n++;assertTrue(events.add(c.get().eventId()));assertTrue(tokens.add(c.get().token()));}}assertEquals(2,n);}
 }
 @Test void unroutableReturnIsNotPublishedAndGetsBoundedBackoff(){var f=fixture();var c=claim(f);assertEquals("RETURNED",sender.send(settings.exchange(),"missing-route",publisher.message(c)));assertTrue(store.failed(c,"RETURNED"));assertNull(db.queryForObject("SELECT published_at FROM outbox_publication WHERE event_id=?",OffsetDateTime.class,f.event()));assertEquals("RETURNED",db.queryForObject("SELECT last_error FROM outbox_publication WHERE event_id=?",String.class,f.event()));assertTrue(store.claim(10).isEmpty());assertFalse(store.confirmed(c));}
 @Test void nonexistentExchangeHasUnknownOutcomeAndRetainsOriginalEvent(){var f=fixture();var c=claim(f);assertEquals("UNKNOWN",sender.send(settings.exchange()+".absent","purchase",publisher.message(c)));assertTrue(store.failed(c,"UNKNOWN"));assertEquals(1L,db.queryForObject("SELECT attempts FROM outbox_publication WHERE event_id=?",Long.class,f.event()));assertEquals("PROCESSING",purchases.detail(f.owner(),f.purchase().id()).status());}
 @Test void sendCannotWaitForConfirmInsidePostgresTransaction(){var f=fixture();var c=claim(f);assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(s->sender.send(settings.exchange(),"purchase",publisher.message(c))));}
 @Test void realBrokerCapacityNackDoesNotMarkPublicationSuccessful()throws Exception {
  var f=fixture();var c=claim(f);String queue=settings.namespace()+".nack-test.q";
  try(var ch=nativeConnection.createChannel()){
   ch.queueDeclare(queue,true,false,false,Map.of("x-queue-type","quorum","x-max-length",1,"x-overflow","reject-publish"));
   try{String outcome="CONFIRMED";for(int i=0;i<10&&outcome.equals("CONFIRMED");i++){outcome=sender.send("",queue,publisher.message(c));Thread.sleep(30);}assertEquals("NACK",outcome);assertTrue(store.failed(c,outcome));assertNull(db.queryForObject("SELECT published_at FROM outbox_publication WHERE event_id=?",OffsetDateTime.class,f.event()));}
   finally{ch.queueDelete(queue);}
  }
 }
 @Test void actualLeaseExpiryAfterLostPublicationReceiptResendsSameIdAndRejectsOldToken()throws Exception {
  var f=fixture();var old=claim(f);send(publisher.message(old)); // Real broker accepted; simulate loss before PG receipt update.
  assertNull(db.queryForObject("SELECT published_at FROM outbox_publication WHERE event_id=?",OffsetDateTime.class,f.event()));assertTrue(store.claim(10).isEmpty());
  Thread.sleep(10100);var fresh=claim(f);assertEquals(2,fresh.attempts());assertNotEquals(old.token(),fresh.token());assertFalse(store.confirmed(old));assertFalse(store.failed(old,"UNKNOWN"));assertEquals("CONFIRMED",publisher.publish(fresh));
  try(var ch=nativeConnection.createChannel()){consumer.onMessage(get(ch,settings.queue()),ch);consumer.onMessage(get(ch,settings.queue()),ch);}oneHold(f);
 }
 @Test void realRedeliveryAfterCommitBeforeAckAcrossIndependentConsumersDoesNotResell()throws Exception {
  var f=fixture();send(publisher.message(claim(f)));
  var first=new PurchaseConsumer(purchases,json,sender,settings);var second=new PurchaseConsumer(purchases,json,sender,settings);
  var ch=nativeConnection.createChannel();var m=get(ch,settings.queue());
  // A real invalid delivery tag closes the channel at the ACK boundary, after the service proxy committed.
  m.getMessageProperties().setDeliveryTag(m.getMessageProperties().getDeliveryTag()+1000);first.onMessage(m,ch);
  for(int i=0;i<100&&ch.isOpen();i++)Thread.sleep(10);assertFalse(ch.isOpen());oneHold(f);
  try(var recovered=nativeConnection.createChannel()){var again=get(recovered,settings.queue());assertTrue(again.getMessageProperties().isRedelivered());second.onMessage(again,recovered);}oneHold(f);
 }
 @Test void duplicateConcurrentBrokerMessagesCreateOneOrder()throws Exception {
  var f=fixture();var m=publisher.message(claim(f));for(int i=0;i<6;i++)send(m);
  try(var pool=Executors.newFixedThreadPool(6)){var calls=new ArrayList<Callable<Void>>();for(int i=0;i<6;i++)calls.add(()->{try(var ch=nativeConnection.createChannel()){consumer.onMessage(get(ch,settings.queue()),ch);}return null;});for(var r:pool.invokeAll(calls))r.get();}oneHold(f);
 }
 @Test void malformedUnknownAndMismatchedIdentityDeadLetterWithoutHold()throws Exception {
  var f=fixture();var original=publisher.message(claim(f));
  var bodies=List.of("{}",json.writeValueAsString(Map.of("eventId",UUID.randomUUID(),"purchaseId",f.purchase().id(),"schemaVersion",1)),json.writeValueAsString(Map.of("eventId",f.event(),"purchaseId",UUID.randomUUID(),"schemaVersion",1)),json.writeValueAsString(Map.of("eventId",f.event(),"purchaseId",f.purchase().id(),"schemaVersion",2)),"{\"eventId\":\""+f.event()+"\",\"eventId\":\""+f.event()+"\",\"purchaseId\":\""+f.purchase().id()+"\",\"schemaVersion\":1}");
  try(var ch=nativeConnection.createChannel()){for(var body:bodies){send(new Message(body.getBytes(java.nio.charset.StandardCharsets.UTF_8),original.getMessageProperties()));consumer.onMessage(get(ch,settings.queue()),ch);var dead=get(ch,settings.deadQueue());ch.basicAck(dead.getMessageProperties().getDeliveryTag(),false);}}assertEquals("PROCESSING",purchases.detail(f.owner(),f.purchase().id()).status());assertEquals(0,db.queryForObject("SELECT held FROM ticket_type WHERE id=?",Integer.class,f.ticket().id()));
 }
 @Test void dbFailureRollsBackThenDurableDelayedRetryCommits()throws Exception {
  var f=fixture();send(publisher.message(claim(f)));db.execute("ALTER TABLE order_inventory_event ADD CONSTRAINT m4b_retry_fault CHECK(ticket_id<>'"+f.ticket().id()+"'::uuid) NOT VALID");
  try(var ch=nativeConnection.createChannel()){
   try{consumer.onMessage(get(ch,settings.queue()),ch);assertEquals("PROCESSING",purchases.detail(f.owner(),f.purchase().id()).status());assertEquals(0,db.queryForObject("SELECT held FROM ticket_type WHERE id=?",Integer.class,f.ticket().id()));assertEquals(0,db.queryForObject("SELECT count(*) FROM ticket_order WHERE customer_id=?",Integer.class,f.owner().id()));}
   finally{db.execute("ALTER TABLE order_inventory_event DROP CONSTRAINT m4b_retry_fault");}
   var retry=get(ch,settings.queue());assertEquals(1,retry.getMessageProperties().getHeaders().get("ticket-retry"));consumer.onMessage(retry,ch);
  }oneHold(f);
 }
 @Test void persistentDbFailureExhaustsRetriesToDeadLetterWithoutRejectingBusinessRequest()throws Exception {
  var f=fixture();send(publisher.message(claim(f)));db.execute("ALTER TABLE order_inventory_event ADD CONSTRAINT m4b_dead_fault CHECK(ticket_id<>'"+f.ticket().id()+"'::uuid) NOT VALID");
  try(var ch=nativeConnection.createChannel()){
   try{for(int i=0;i<=settings.maxRetries();i++)consumer.onMessage(get(ch,settings.queue()),ch);var dead=get(ch,settings.deadQueue());assertEquals(f.event().toString(),dead.getMessageProperties().getMessageId());assertEquals(settings.maxRetries(),dead.getMessageProperties().getHeaders().get("ticket-retry"));ch.basicAck(dead.getMessageProperties().getDeliveryTag(),false);assertEquals("PROCESSING",purchases.detail(f.owner(),f.purchase().id()).status());}
   finally{db.execute("ALTER TABLE order_inventory_event DROP CONSTRAINT m4b_dead_fault");}
  }assertEquals(0,db.queryForObject("SELECT held FROM ticket_type WHERE id=?",Integer.class,f.ticket().id()));
 }
 @Test void cancelledAndExpiredBacklogAckWithoutCreatingOrder()throws Exception {
  var cancelled=fixture();var a=claim(cancelled);purchases.cancel(cancelled.owner(),cancelled.purchase().id(),new Cancel(UUID.randomUUID(),1));send(publisher.message(a));
  var expired=fixture();var b=claim(expired);db.update("WITH stamp AS MATERIALIZED(SELECT clock_timestamp() AS clock) UPDATE purchase_request SET created_at=clock-interval '70 seconds',deadline_at=clock-interval '10 seconds' FROM stamp WHERE id=?",expired.purchase().id());send(publisher.message(b));
  try(var ch=nativeConnection.createChannel()){consumer.onMessage(get(ch,settings.queue()),ch);consumer.onMessage(get(ch,settings.queue()),ch);}assertEquals("CANCELLED",purchases.detail(cancelled.owner(),cancelled.purchase().id()).status());assertEquals("EXPIRED",purchases.detail(expired.owner(),expired.purchase().id()).status());assertEquals(0,db.queryForObject("SELECT count(*) FROM ticket_order WHERE customer_id IN (?,?)",Integer.class,cancelled.owner().id(),expired.owner().id()));
 }
 @Test void invalidRetryHeaderGoesToDeadLetterRatherThanUnboundedLoop()throws Exception {var f=fixture();var m=publisher.message(claim(f));m.getMessageProperties().setHeader("ticket-retry",-1);send(m);try(var ch=nativeConnection.createChannel()){consumer.onMessage(get(ch,settings.queue()),ch);var dead=get(ch,settings.deadQueue());ch.basicAck(dead.getMessageProperties().getDeliveryTag(),false);}assertEquals("PROCESSING",purchases.detail(f.owner(),f.purchase().id()).status());}
 @Test void unknownRetryPublicationLeavesRealOriginalUnackedForRecovery()throws Exception {
  var f=fixture();send(publisher.message(claim(f)));
  var missing=new DeliverySettings(true,false,false,"encore-m4b-missing",1000,10,200,3,4);var isolated=new PurchaseConsumer(purchases,json,sender,missing);
  db.execute("ALTER TABLE order_inventory_event ADD CONSTRAINT m4b_unknown_retry_fault CHECK(ticket_id<>'"+f.ticket().id()+"'::uuid) NOT VALID");
  var original=nativeConnection.createChannel();
  try{var m=get(original,settings.queue());assertThrows(IllegalStateException.class,()->isolated.onMessage(m,original));assertFalse(original.isOpen());assertEquals("PROCESSING",purchases.detail(f.owner(),f.purchase().id()).status());}
  finally{db.execute("ALTER TABLE order_inventory_event DROP CONSTRAINT m4b_unknown_retry_fault");}
  try(var recovered=nativeConnection.createChannel()){var again=get(recovered,settings.queue());assertTrue(again.getMessageProperties().isRedelivered());consumer.onMessage(again,recovered);}oneHold(f);
 }
 @Test void confirmedRetryThenLostOriginalAckMayDuplicateButNeverResell()throws Exception {
  var f=fixture();send(publisher.message(claim(f)));db.execute("ALTER TABLE order_inventory_event ADD CONSTRAINT m4b_retry_ack_fault CHECK(ticket_id<>'"+f.ticket().id()+"'::uuid) NOT VALID");
  var original=nativeConnection.createChannel();
  try{var m=get(original,settings.queue());m.getMessageProperties().setDeliveryTag(m.getMessageProperties().getDeliveryTag()+1000);consumer.onMessage(m,original);for(int i=0;i<100&&original.isOpen();i++)Thread.sleep(10);assertFalse(original.isOpen());assertEquals("PROCESSING",purchases.detail(f.owner(),f.purchase().id()).status());}
  finally{db.execute("ALTER TABLE order_inventory_event DROP CONSTRAINT m4b_retry_ack_fault");}
  try(var recovered=nativeConnection.createChannel()){consumer.onMessage(get(recovered,settings.queue()),recovered);consumer.onMessage(get(recovered,settings.queue()),recovered);}oneHold(f);
 }
 @Test void triggerFailureRollsBackAcceptanceAndKeepsV5IntentImmutable(){var f=fixture();assertEquals(1,db.queryForObject("SELECT count(*) FROM outbox_publication WHERE event_id=?",Integer.class,f.event()));db.execute("ALTER TABLE outbox_publication ADD CONSTRAINT m4b_accept_fault CHECK(false) NOT VALID");try{var b=new Place(UUID.randomUUID(),f.ticket().id(),1,1,1200);assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->purchases.submit(f.owner(),b));assertEquals(1,db.queryForObject("SELECT count(*) FROM purchase_request WHERE customer_id=?",Integer.class,f.owner().id()));}finally{db.execute("ALTER TABLE outbox_publication DROP CONSTRAINT m4b_accept_fault");}}
}
