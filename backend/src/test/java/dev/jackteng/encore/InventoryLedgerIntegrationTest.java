package dev.jackteng.encore;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.encore.admission.AdmissionSettings;
import dev.jackteng.encore.inventory.RedisTicketLedger;
import dev.jackteng.encore.inventory.RedisTicketLedger.*;
import io.lettuce.core.cluster.SlotHash;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"ticket.delivery.enabled=false","ticket.admission.enabled=false","ticket.orders.enabled=false","ticket.expiry.enabled=false"})
class InventoryLedgerIntegrationTest {
 @Autowired StringRedisTemplate redis;@Autowired ObjectMapper json;@Autowired AdmissionSettings settings;@Autowired JdbcTemplate db;
 RedisTicketLedger ledger;final Set<String> ownKeys=new HashSet<>();
 @BeforeEach void setup(){assertEquals("ticket_demo",db.queryForObject("SELECT current_database()",String.class));ledger=new RedisTicketLedger(redis,json,settings.namespace());}
 @AfterEach void cleanup(){redis.delete(ownKeys);}
 UUID ticket(int available,int limit){var t=UUID.randomUUID();ownKeys.add(ledger.key(t));assertEquals(Code.OK,ledger.initialize(t,1,available,limit).code());return t;}
 UUID opened(int available,int limit){var t=ticket(available,limit);assertEquals(Code.OK,ledger.open(t,1).code());return t;}
 Claim claim(){return claim(1,1,System.currentTimeMillis()+60000);}
 Claim claim(long generation,int qty,long deadline){return new Claim(generation,UUID.randomUUID(),UUID.randomUUID(),qty,deadline,"a".repeat(64));}
 Snapshot state(UUID t,long g){var r=ledger.snapshot(t,g);assertEquals(Code.OK,r.code());var s=r.snapshot();assertEquals(s.baseline(),s.available()+s.held()+s.committed());return s;}
 Map<Object,Object> bytes(UUID t){return new HashMap<>(redis.opsForHash().entries(ledger.key(t)));}
 void unchanged(UUID t,Map<Object,Object> before){assertEquals(before,bytes(t));}
 String raw(UUID t,String... args){var script=new DefaultRedisScript<String>();script.setLocation(new ClassPathResource("redis/ticket-ledger.lua"));script.setResultType(String.class);return redis.execute(script,List.of(ledger.key(t)),(Object[])args);}

 @Test void seedIsClosedAndReplayNeverResetsOrReopensStock(){var t=ticket(3,10);assertEquals(Phase.CLOSED,state(t,1).phase());var c=claim();assertEquals(Code.CLOSED,ledger.hold(t,c).code());ledger.open(t,1);ledger.hold(t,c);var before=bytes(t);assertEquals(Code.OK,ledger.initialize(t,1,3,10).code());unchanged(t,before);assertEquals(Code.CONFLICT,ledger.initialize(t,1,9,10).code());unchanged(t,before);assertEquals(2,state(t,1).available());}

 @Test void concurrentSameOriginalUuidOnlyDeductsOnce()throws Exception {var t=opened(2,10);var c=claim(1,2,System.currentTimeMillis()+60000);try(var pool=Executors.newFixedThreadPool(8)){var jobs=new ArrayList<Callable<Result>>();for(int i=0;i<8;i++)jobs.add(()->ledger.hold(t,c));for(var f:pool.invokeAll(jobs)){var r=f.get();assertEquals(Code.OK,r.code());assertEquals(Status.HELD,r.receipt().status());assertEquals(c.requestId(),r.receipt().requestId());}}var s=state(t,1);assertEquals(0,s.available());assertEquals(2,s.held());assertEquals(1,s.entries());}

 @Test void concurrentLastTicketsCannotGoNegative()throws Exception {var t=opened(3,20);int won=0;try(var pool=Executors.newFixedThreadPool(12)){var jobs=new ArrayList<Callable<Result>>();for(int i=0;i<12;i++){var c=claim();jobs.add(()->ledger.hold(t,c));}for(var f:pool.invokeAll(jobs)){var code=f.get().code();if(code==Code.OK)won++;else assertEquals(Code.SOLD_OUT,code);}}assertEquals(3,won);var s=state(t,1);assertEquals(0,s.available());assertEquals(3,s.held());assertEquals(3,s.entries());}

 @Test void originalBindingCannotChangeOwnerQuoteQuantityOrDeadline(){var t=opened(5,10);var c=claim();ledger.hold(t,c);var before=bytes(t);var conflicts=List.of(new Claim(1,c.requestId(),UUID.randomUUID(),1,c.deadline(),c.fingerprint()),new Claim(1,c.requestId(),c.owner(),2,c.deadline(),c.fingerprint()),new Claim(1,c.requestId(),c.owner(),1,c.deadline()+1,c.fingerprint()),new Claim(1,c.requestId(),c.owner(),1,c.deadline(),"b".repeat(64)));for(var changed:conflicts){assertEquals(Code.CONFLICT,ledger.hold(t,changed).code());assertEquals(Code.CONFLICT,ledger.release(t,changed).code());unchanged(t,before);}}

 @Test void actualDeadlinePassesWithoutTtlReleaseOrRevival()throws Exception {var t=opened(2,10);var c=claim(1,1,System.currentTimeMillis()+500);assertEquals(Code.OK,ledger.hold(t,c).code());Thread.sleep(650);assertEquals(Code.OK,ledger.hold(t,c).code());assertEquals(Status.HELD,ledger.lookup(t,c).receipt().status());assertEquals(1,state(t,1).available());assertEquals(-1L,redis.getExpire(ledger.key(t)));assertEquals(Code.EXPIRED,ledger.hold(t,claim(1,1,c.deadline())).code());assertEquals(Code.OK,ledger.release(t,c).code());assertEquals(Status.RELEASED,ledger.hold(t,c).receipt().status());assertEquals(2,state(t,1).available());}

 @Test void frozenGenerationCannotBeReopenedByDelayedOperation(){var t=opened(3,10);var c=claim();ledger.hold(t,c);ledger.close(t,1);assertEquals(Phase.FROZEN,state(t,1).phase());assertEquals(Code.CLOSED,ledger.open(t,1).code());assertEquals(Code.CLOSED,ledger.hold(t,claim()).code());assertEquals(Code.OK,ledger.hold(t,c).code());assertEquals(Code.ACTIVE_HOLDS,ledger.rotate(t,1,3).code());assertEquals(Code.OK,ledger.release(t,c).code());assertEquals(Code.OK,ledger.rotate(t,1,3).code());assertEquals(Phase.CLOSED,state(t,2).phase());assertEquals(Code.FENCED,ledger.open(t,1).code());assertEquals(Code.FENCED,ledger.close(t,1).code());assertEquals(Code.FENCED,ledger.hold(t,c).code());assertEquals(Code.FENCED,ledger.commit(t,c).code());assertEquals(Code.FENCED,ledger.release(t,c).code());assertEquals(Code.FENCED,ledger.rotate(t,1,3).code());ledger.open(t,2);assertEquals(Code.CONFLICT,ledger.hold(t,new Claim(2,c.requestId(),c.owner(),1,c.deadline(),c.fingerprint())).code());assertEquals(Code.OK,ledger.hold(t,claim(2,1,System.currentTimeMillis()+60000)).code());}

 @Test void commitReleaseCompetitionHasOneTerminalDecision()throws Exception {var t=opened(2,10);var c=claim();ledger.hold(t,c);try(var pool=Executors.newFixedThreadPool(2)){var jobs=List.<Callable<Result>>of(()->ledger.commit(t,c),()->ledger.release(t,c));var outcomes=pool.invokeAll(jobs).stream().map(f->{try{return f.get().code();}catch(Exception e){throw new AssertionError(e);}}).toList();assertEquals(1,outcomes.stream().filter(v->v==Code.OK).count());assertEquals(1,outcomes.stream().filter(v->v==Code.TERMINAL).count());}var receipt=ledger.lookup(t,c).receipt();var s=state(t,1);assertEquals(0,s.held());if(receipt.status()==Status.COMMITTED){assertEquals(1,s.available());assertEquals(1,s.committed());}else{assertEquals(Status.RELEASED,receipt.status());assertEquals(2,s.available());}}

 @Test void committedReceiptCannotBeReleasedOrReplacedByRetry(){var t=opened(2,10);var c=claim();ledger.hold(t,c);ledger.commit(t,c);var before=bytes(t);assertEquals(Code.OK,ledger.commit(t,c).code());assertEquals(Code.TERMINAL,ledger.release(t,c).code());assertEquals(Status.COMMITTED,ledger.hold(t,c).receipt().status());unchanged(t,before);}

 @Test void explicitPgProvenCommitCanSettleAfterDeadlineAndFreeze()throws Exception {var t=opened(2,10);var c=claim(1,1,System.currentTimeMillis()+500);assertEquals(Code.OK,ledger.hold(t,c).code());ledger.close(t,1);Thread.sleep(650);assertEquals(Code.OK,ledger.commit(t,c).code());assertEquals(Status.COMMITTED,ledger.lookup(t,c).receipt().status());assertEquals(1,state(t,1).available());assertEquals(0,state(t,1).held());}

 @Test void boundedReceiptMemoryNeverEvictsIntoNewDebit(){var t=opened(3,2);var a=claim();var b=claim();ledger.hold(t,a);ledger.release(t,a);ledger.hold(t,b);ledger.commit(t,b);assertEquals(Code.FULL,ledger.hold(t,claim()).code());assertEquals(Status.RELEASED,ledger.hold(t,a).receipt().status());ledger.close(t,1);ledger.rotate(t,1,3);ledger.open(t,2);assertEquals(2,state(t,2).entries());assertEquals(Code.FULL,ledger.hold(t,claim(2,1,System.currentTimeMillis()+60000)).code());assertEquals(Code.CONFLICT,ledger.hold(t,new Claim(2,a.requestId(),a.owner(),1,a.deadline(),a.fingerprint())).code());}

 @Test void closeVersusHoldLeavesGateFrozenWithConsistentCounters()throws Exception {var t=opened(1,10);var c=claim();try(var pool=Executors.newFixedThreadPool(2)){var jobs=List.<Callable<Result>>of(()->ledger.hold(t,c),()->ledger.close(t,1));var values=pool.invokeAll(jobs);assertTrue(Set.of(Code.OK,Code.CLOSED).contains(values.get(0).get().code()));assertEquals(Code.OK,values.get(1).get().code());}assertEquals(Phase.FROZEN,state(t,1).phase());assertEquals(Code.CLOSED,ledger.open(t,1).code());assertEquals(Code.CLOSED,ledger.hold(t,claim()).code());}

 @Test void absentKeyNeverAutomaticallySeedsOrReleases()throws Exception {var t=opened(2,10);var c=claim();ledger.hold(t,c);redis.delete(ledger.key(t));for(var mode:List.of("HOLD","LOOKUP","COMMIT","RELEASE","OPEN","CLOSE","ROTATE")){String[] args=switch(mode){case "OPEN","CLOSE"->new String[]{mode,"1"};case "ROTATE"->new String[]{mode,"1","2"};default->new String[]{mode,"1",c.requestId().toString(),c.owner().toString(),"1",""+c.deadline(),c.fingerprint()};};assertEquals("MISSING",json.readTree(raw(t,args)).get("code").asText());assertFalse(redis.hasKey(ledger.key(t)));}}

 @Test void wrongTypeAndExpiringStateFailClosedBeforeMutation(){var t=opened(2,10);redis.delete(ledger.key(t));redis.opsForValue().set(ledger.key(t),"synthetic wrong type");assertEquals(Code.CORRUPT,ledger.hold(t,claim()).code());assertEquals("synthetic wrong type",redis.opsForValue().get(ledger.key(t)));redis.delete(ledger.key(t));ledger.initialize(t,1,2,10);ledger.open(t,1);redis.expire(ledger.key(t),java.time.Duration.ofMinutes(1));var before=bytes(t);assertEquals(Code.CORRUPT,ledger.hold(t,claim()).code());assertEquals(Code.CORRUPT,ledger.initialize(t,1,2,10).code());unchanged(t,before);}

 @Test void corruptCountersOrMissingReceiptDetectedBeforeAnyWrite(){var t=opened(2,10);var c=claim();ledger.hold(t,c);redis.opsForHash().put(ledger.key(t),"_available","2");var before=bytes(t);assertEquals(Code.CORRUPT,ledger.release(t,c).code());unchanged(t,before);redis.opsForHash().put(ledger.key(t),"_available","1");redis.opsForHash().delete(ledger.key(t),"r:"+c.requestId());before=bytes(t);assertEquals(Code.CORRUPT,ledger.hold(t,claim()).code());unchanged(t,before);}

 @Test void malformedReceiptCannotPartiallyChangeCounters(){var t=opened(2,10);var c=claim();ledger.hold(t,c);for(String corrupt:List.of("{", "[]", "{\"generation\":1}", "x".repeat(513))){redis.opsForHash().put(ledger.key(t),"r:"+c.requestId(),corrupt);var before=bytes(t);assertEquals(Code.CORRUPT,ledger.release(t,c).code());unchanged(t,before);}}

 @Test void invalidLuaArgumentsAreRejectedEvenWhenJavaValidationBypassed()throws Exception {var t=opened(2,10);var c=claim();var before=bytes(t);var variants=List.of(new String[]{"HOLD","1",c.requestId().toString(),c.owner().toString(),"-1",""+c.deadline(),c.fingerprint()},new String[]{"HOLD","1",c.requestId().toString(),c.owner().toString(),"1.5",""+c.deadline(),c.fingerprint()},new String[]{"HOLD","1","bad",c.owner().toString(),"1",""+c.deadline(),c.fingerprint()},new String[]{"HOLD","1",c.requestId().toString(),c.owner().toString(),"1","0",c.fingerprint()},new String[]{"INIT","1","-1","10"},new String[]{"READ","0"},new String[]{"UNKNOWN","1"},new String[]{"OPEN","1","extra"});for(var a:variants){assertEquals("INVALID",json.readTree(raw(t,a)).get("code").asText());unchanged(t,before);}assertEquals(Code.EXPIRED,ledger.hold(t,claim(1,1,System.currentTimeMillis()+300000)).code());unchanged(t,before);}

 @Test void lostAcknowledgementReplaysOriginalReceiptWithoutExtraDebit(){var t=opened(2,10);var c=claim();ledger.hold(t,c); // actual Redis result deliberately discarded, not a PG acceptance claim
 var replay=ledger.hold(t,c);assertEquals(Code.OK,replay.code());assertEquals(1,replay.snapshot().available());assertEquals(1,replay.snapshot().held());assertEquals(1,replay.snapshot().entries());}

 @Test void oneHashTagAndJavaBoundsPreventCrossSlotOrOversizedCommands(){var t=opened(0,10);assertEquals(SlotHash.getSlot(t.toString()),SlotHash.getSlot(ledger.key(t)));assertEquals(Code.SOLD_OUT,ledger.hold(t,claim()).code());assertThrows(IllegalArgumentException.class,()->new RedisTicketLedger(redis,json,"unsafe:{tag}"));assertThrows(IllegalArgumentException.class,()->ledger.initialize(t,1,1000001,10));assertThrows(IllegalArgumentException.class,()->ledger.rotate(t,0,1));assertThrows(IllegalArgumentException.class,()->new Claim(1,UUID.randomUUID(),UUID.randomUUID(),11,1,"a".repeat(64)));}
}
