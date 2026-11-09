package dev.elpsy.ticket;

import dev.elpsy.ticket.inventory.*;
import dev.elpsy.ticket.inventory.MultiCursorExperiment.Command;
import dev.elpsy.ticket.orders.*;
import dev.elpsy.ticket.orders.OrderModels.*;
import dev.elpsy.ticket.orders.PaymentModels.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"ticket.delivery.enabled=false","ticket.admission.enabled=false",
 "ticket.orders.enabled=true","ticket.purchases.enabled=true","ticket.mock-payments.enabled=true",
 "ticket.expiry.enabled=false","ticket.admission.namespace=encore-demo-increment-multi"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockMultiCursorExperimentTest {
 @Autowired ApplicationContext context; @Autowired JdbcTemplate db; @Autowired StringRedisTemplate redis;
 @Autowired StockHoldService holds; @Autowired PurchaseService purchases; @Autowired StockSettlementService settlement;
 @Autowired StockProjectionService projections; @Autowired OrderService orders; @Autowired PaymentService payments;
 @Autowired PlatformTransactionManager transactions; @Autowired ObjectMapper json;
 CommitCursorFixture fixtures; MultiCursorExperiment experiment; Set<String> keys=new HashSet<>();
 record Pair(CommitCursorFixture.Fixture first,UUID secondIntent,UUID secondEvent) {}
 @BeforeAll void setup(){fixtures=new CommitCursorFixture(context);experiment=new MultiCursorExperiment(db,redis,json,"encore-demo-increment-multi",transactions);experiment.createSchema();}
 @AfterEach void cleanup(){redis.delete(keys);keys.clear();}
 CommitCursorFixture.Fixture accepted(boolean active){var f=fixtures.accepted(active,experiment::seed);keys.add(fixtures.ledger.key(f.ticket(),1));keys.add(experiment.key(f.ticket(),1));keys.add(fixtures.experiment.cursorKey(f.ticket(),1));return f;}
 Pair pair(){var f=accepted(false);var i=holds.prepare(f.owner(),new Place(UUID.randomUUID(),f.ticket(),1,1,1200));assertEquals("HELD",holds.apply(i.id()).state());var p=purchases.acceptHold(f.owner(),i.id());var e=db.queryForObject("SELECT id FROM purchase_outbox WHERE purchase_id=?",UUID.class,p.id());assertTrue(purchases.process(f.event()));assertTrue(purchases.process(e));assertEquals("COMMITTED",settlement.reconcile(f.intent()).resolution());assertEquals("COMMITTED",settlement.reconcile(i.id()).resolution());return new Pair(f,i.id(),e);}
 UUID order(UUID intent){return db.queryForObject("SELECT order_id FROM stock_hold_decision WHERE intent_id=?",UUID.class,intent);}
 Command register(UUID ticket,long from){return experiment.register(UUID.randomUUID(),ticket,from);}
 String consume(Command c){return experiment.consume(c.request(),c.digest());}
 String ledger(Command c){return fixtures.ledger.key(c.ticket(),c.generation());}
 String sidecar(Command c){return experiment.key(c.ticket(),c.generation());}
 long cursor(Command c){return db.queryForObject("SELECT revision FROM stock_multi_experiment.cursor WHERE ticket_id=? AND generation=?",Long.class,c.ticket(),c.generation());}
 int count(Command c,String table){return db.queryForObject("SELECT count(*) FROM stock_multi_experiment."+table+" WHERE request_id=?",Integer.class,c.request());}
 void fresh(Command c){assertEquals(0,count(c,"ack"));assertEquals(1,count(c,"audit"));}
 void fault(Command c){db.execute("ALTER TABLE stock_multi_experiment.audit ADD CONSTRAINT multi_fault CHECK(request_id<>'"+c.request()+"'::uuid OR phase<>'APPLIED') NOT VALID");}
 void unfault(){db.execute("ALTER TABLE stock_multi_experiment.audit DROP CONSTRAINT multi_fault");}
 List<Command> sequence(Pair pair){var f=pair.first();orders.cancel(f.owner(),order(f.intent()),new Cancel(UUID.randomUUID(),1));var o=orders.detail(f.owner(),order(pair.secondIntent()));var p=payments.start(f.owner(),o.id(),new Pay(UUID.randomUUID(),o.version(),o.totalTwd(),"SUCCESS"));payments.reconcile(f.owner(),o.id(),p.payment().id(),new Reconcile(UUID.randomUUID()));var list=new ArrayList<Command>();for(long r=1;r<5;r++)list.add(register(f.ticket(),r));return list;}

 @Test void twoCommitsReleaseAndPaymentConsumeContiguouslyWithoutWritingOriginalLedger(){var pair=pair();var cs=sequence(pair);var original=redis.opsForHash().entries(ledger(cs.getFirst()));int[] expected={3,2,3,3};for(int n=0;n<cs.size();n++){var c=cs.get(n);assertEquals("APPLIED",consume(c));assertEquals(c.to(),cursor(c));assertEquals(""+expected[n],redis.opsForHash().get(sidecar(c),"_available"));assertEquals(2,count(c,"audit"));assertEquals(original,redis.opsForHash().entries(ledger(c)));}assertEquals("2",original.get("_available"));assertEquals("2",original.get("_committed"));assertEquals(3,db.queryForObject("SELECT capacity-held-sold FROM ticket_type WHERE id=?",Integer.class,pair.first().ticket()));assertEquals("DIRTY",projections.inspect(pair.first().ticket()).state());assertTrue(original.get("r:"+pair.first().intent()).toString().contains("COMMITTED"));}
 @Test void oneRevisionCannotSkipItsOriginalPredecessor(){var p=pair();var first=register(p.first().ticket(),1);var second=register(p.first().ticket(),2);var before=redis.opsForHash().entries(sidecar(first));assertEquals("CURSOR_GAP",consume(second));fresh(second);assertEquals(before,redis.opsForHash().entries(sidecar(first)));assertEquals("APPLIED",consume(first));assertEquals("APPLIED",consume(second));}
 @Test void sixConcurrentOriginalRetriesProduceExactlyOneCheckpointAndAudit()throws Exception{var p=pair();var c=register(p.first().ticket(),1);try(var pool=Executors.newFixedThreadPool(6)){var jobs=new ArrayList<Callable<String>>();for(int i=0;i<6;i++)jobs.add(()->consume(c));int changed=0;for(var future:pool.invokeAll(jobs)){var r=future.get();assertTrue(List.of("APPLIED","PG_REPLAY").contains(r));if(r.equals("APPLIED"))changed++;}assertEquals(1,changed);}assertEquals(1,count(c,"ack"));assertEquals(2,count(c,"audit"));}
 @Test void rollbackOnSecondCheckpointRecoversSameUuidDigestAndOriginalReceipts(){var cs=sequence(pair());assertEquals("APPLIED",consume(cs.get(0)));var c=cs.get(1);var original=redis.opsForHash().entries(ledger(c));fault(c);try{assertThrows(DataAccessException.class,()->consume(c));}finally{unfault();}assertEquals(c.from(),cursor(c));fresh(c);assertEquals("3",redis.opsForHash().get(sidecar(c),"_revision"));assertEquals("REDIS_REPLAY",consume(c));assertEquals("PG_REPLAY",consume(c));for(int i=2;i<cs.size();i++)assertEquals("APPLIED",consume(cs.get(i)));assertEquals(original,redis.opsForHash().entries(ledger(c)));}
 @Test void replayOfOldPgAckAfterLaterCursorIsHistoricalOnly(){var cs=sequence(pair());for(var c:cs)assertEquals("APPLIED",consume(c));redis.delete(ledger(cs.getFirst()));assertEquals("PG_REPLAY",consume(cs.getFirst()));assertEquals(5,cursor(cs.getFirst()));assertEquals("DIRTY",projections.inspect(cs.getFirst().ticket()).state());}
 @Test void processingOrActiveClaimsCannotRegisterACommand(){var f=accepted(true);assertTrue(purchases.process(f.event()));settlement.reconcile(f.intent());var before=redis.opsForHash().entries(experiment.key(f.ticket(),1));assertThrows(DataAccessException.class,()->register(f.ticket(),1));assertEquals(before,redis.opsForHash().entries(experiment.key(f.ticket(),1)));}
 @Test void absentAndUnacknowledgedFormalCommitsCannotRegister(){for(boolean absent:List.of(false,true)){var f=accepted(false);purchases.process(f.event());if(absent){redis.delete(fixtures.ledger.key(f.ticket(),1));assertEquals("KEY_MISSING",settlement.reconcile(f.intent()).resolution());}assertThrows(DataAccessException.class,()->register(f.ticket(),1));}}
 @Test void directSynchronousHoldIsExplicitlyUnsupportedEvenWithRealOrigin(){var p=pair();orders.place(p.first().owner(),new Place(UUID.randomUUID(),p.first().ticket(),1,1,1200));assertEquals("ORDER_HOLD",db.queryForObject("SELECT kind FROM stock_revision_origin WHERE ticket_id=? AND to_revision=4",String.class,p.first().ticket()));assertTrue(assertThrows(DataAccessException.class,()->register(p.first().ticket(),1)).getMostSpecificCause().getMessage().contains("MISSING_OR_UNSUPPORTED_ORIGIN"));}
 @Test void unattributedOrQuoteRevisionFencesEntireRangeInsteadOfSkipping(){var p=pair();db.update("UPDATE ticket_type SET version=version+1 WHERE id=?",p.first().ticket());assertEquals("UNATTRIBUTED",db.queryForObject("SELECT kind FROM stock_revision_origin WHERE ticket_id=? AND to_revision=4",String.class,p.first().ticket()));assertThrows(DataAccessException.class,()->register(p.first().ticket(),1));}
 @Test void appendAfterRegistrationFencesExistingOriginalCommand(){var p=pair();var c=register(p.first().ticket(),1);orders.cancel(p.first().owner(),order(p.first().intent()),new Cancel(UUID.randomUUID(),1));assertEquals("SOURCE_CHANGED",consume(c));fresh(c);assertEquals(1,cursor(c));}
 @Test void generationChangeCannotAdoptOldUnconsumedCommand(){var p=pair();var c=register(p.first().ticket(),1);var r=projections.begin(c.ticket(),UUID.randomUUID(),3);keys.add(fixtures.ledger.key(c.ticket(),r.generation()));assertEquals("READY",projections.complete(r.requestId()).state());assertEquals("FENCED",consume(c));fresh(c);}
 @Test void missingSidecarOrLedgerNeverReseeds(){for(boolean side:List.of(false,true)){var p=pair();var c=register(p.first().ticket(),1);var k=side?sidecar(c):ledger(c);redis.delete(k);assertEquals("MISSING",consume(c));fresh(c);assertFalse(redis.hasKey(k));}}
 @Test void ttlWrongTypeAndCorruptCounterCannotConsume(){for(int mode=0;mode<3;mode++){var p=pair();var c=register(p.first().ticket(),1);if(mode==0)redis.expire(sidecar(c),Duration.ofSeconds(30));if(mode==1){redis.delete(ledger(c));redis.opsForValue().set(ledger(c),"wrong");}if(mode==2)redis.opsForHash().put(ledger(c),"_available","99");assertEquals(mode==2?"STOCK_CONFLICT":"CORRUPT",consume(c));fresh(c);}}
 @Test void everyOriginalReceiptIsCheckedIncludingOtherClaimOwnerAndDecision(){for(boolean owner:List.of(false,true)){var p=pair();var c=register(p.first().ticket(),1);var field="r:"+p.secondIntent();var raw=redis.opsForHash().get(ledger(c),field).toString();redis.opsForHash().put(ledger(c),field,owner?raw.replace(p.first().owner().id().toString(),UUID.randomUUID().toString()):raw.replace("COMMITTED","RELEASED"));assertEquals("CLAIM_CONFLICT",consume(c));fresh(c);}}
 @Test void previousMarkerTamperingPreventsTheNextApply(){var p=pair();var c=register(p.first().ticket(),1);var next=register(c.ticket(),2);assertEquals("APPLIED",consume(c));redis.opsForHash().put(sidecar(c),"a:"+c.request(),"forged");assertEquals("COMMAND_CONFLICT",consume(next));fresh(next);assertEquals(2,cursor(c));}
 @Test void bodyConflictAndNewUuidCannotAdoptSameRevision(){var p=pair();var c=register(p.first().ticket(),1);assertEquals(c,experiment.register(c.request(),c.ticket(),c.from()));assertEquals("COMMAND_CONFLICT",experiment.consume(c.request(),"0".repeat(64)));assertThrows(DataAccessException.class,()->register(c.ticket(),1));fresh(c);}
 @Test void immutableEvidenceAndAckRequireExactAtomicCheckpoint(){var p=pair();var c=register(p.first().ticket(),1);assertThrows(DataAccessException.class,()->db.update("INSERT INTO stock_multi_experiment.ack VALUES(?,?)",c.request(),c.digest()));fresh(c);assertEquals("APPLIED",consume(c));for(String table:List.of("command","ack","audit")){assertThrows(DataAccessException.class,()->db.update("DELETE FROM stock_multi_experiment."+table+" WHERE request_id=?",c.request()));}assertThrows(DataAccessException.class,()->db.update("UPDATE stock_multi_experiment.cursor SET revision=revision+1 WHERE ticket_id=?",c.ticket()));}
 @Test void seventeenRevisionsExceedBoundBeforeAnyApply(){var p=pair();for(int n=0;n<15;n++)db.update("UPDATE ticket_type SET version=version+1 WHERE id=?",p.first().ticket());assertTrue(assertThrows(DataAccessException.class,()->register(p.first().ticket(),1)).getMostSpecificCause().getMessage().contains("BOUNDED_RANGE"));}
 @Test void forgedPlanCannotRegisterEvenWithRealSourceAndNewUuid(){var p=pair();var c=register(p.first().ticket(),1);assertThrows(DataAccessException.class,()->db.update("INSERT INTO stock_multi_experiment.command VALUES(?,?,?,?,?,?::jsonb,?)",UUID.randomUUID(),c.ticket(),c.generation(),2L,3L,c.plan(),c.digest()));}
 @Test void independentPhasesAndPristineSeedRequired(){var p=pair();var c=register(p.first().ticket(),1);new TransactionTemplate(transactions).executeWithoutResult(s->assertThrows(IllegalStateException.class,()->consume(c)));assertThrows(IllegalStateException.class,()->experiment.seed(c.ticket()));}
 @Test void expiredAcceptedOrderHasSeparateReleaseOriginAndOriginalCommitStaysCommitted(){var p=pair();var f=p.first();var id=order(f.intent());db.update("WITH stamp AS MATERIALIZED(SELECT clock_timestamp() clock) UPDATE ticket_order SET created_at=clock-interval '11 minutes',expires_at=clock-interval '1 minute' FROM stamp WHERE id=?",id);assertTrue(orders.expire(id));assertFalse(orders.expire(id));for(long r=1;r<4;r++)assertEquals("APPLIED",consume(register(f.ticket(),r)));assertEquals("COMMIT",db.queryForObject("SELECT target FROM stock_hold_decision WHERE intent_id=?",String.class,f.intent()));assertTrue(redis.opsForHash().get(fixtures.ledger.key(f.ticket(),1),"r:"+f.intent()).toString().contains("COMMITTED"));assertEquals("3",redis.opsForHash().get(experiment.key(f.ticket(),1),"_available"));}
}
