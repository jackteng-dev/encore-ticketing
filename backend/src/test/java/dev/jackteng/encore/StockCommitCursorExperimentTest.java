package dev.jackteng.encore;

import dev.jackteng.encore.inventory.*;
import dev.jackteng.encore.inventory.CommitCursorExperiment.Command;
import dev.jackteng.encore.orders.OrderModels.Cancel;
import dev.jackteng.encore.orders.OrderService;
import dev.jackteng.encore.orders.PurchaseService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"ticket.delivery.enabled=false", "ticket.admission.enabled=false",
        "ticket.orders.enabled=true", "ticket.purchases.enabled=true", "ticket.expiry.enabled=false",
        "ticket.admission.namespace=encore-demo-increment-test"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockCommitCursorExperimentTest {
    @Autowired ApplicationContext context;
    @Autowired StockSettlementService settlement;
    @Autowired StockProjectionService projections;
    @Autowired StringRedisTemplate redis;
    @Autowired PurchaseService purchases;
    @Autowired OrderService orders;
    @Autowired PlatformTransactionManager transactions;
    CommitCursorFixture fixtures;
    CommitCursorExperiment experiment;

    @BeforeAll void setup() { fixtures = new CommitCursorFixture(context); experiment = fixtures.experiment; }
    Command committed() {
        var f = fixtures.accepted(false);
        var c = fixtures.processAndRegister(f, UUID.randomUUID());
        assertEquals("COMMITTED", settlement.reconcile(f.intent()).resolution());
        return c;
    }
    String consume(Command c) { return experiment.consume(c.requestId(), experiment.digest(c)); }
    String ledgerKey(Command c) { return fixtures.ledger.key(c.ticketId(), c.generation()); }
    String cursorKey(Command c) { return experiment.cursorKey(c.ticketId(), c.generation()); }
    int ack(Command c) {
        return fixtures.db.queryForObject("SELECT count(*) FROM stock_commit_experiment.ack WHERE request_id=?",
                Integer.class, c.requestId());
    }
    void fault(Command c) {
        fixtures.db.execute("ALTER TABLE stock_commit_experiment.audit ADD CONSTRAINT cursor_ack_fault "
                + "CHECK(request_id<>'" + c.requestId() + "'::uuid OR phase<>'APPLIED') NOT VALID");
    }
    void unfault() { fixtures.db.execute("ALTER TABLE stock_commit_experiment.audit DROP CONSTRAINT cursor_ack_fault"); }
    void notConsumed(Command c) { assertEquals(c.fromRevision(), experiment.revision(c)); assertEquals(0, ack(c)); assertEquals(1, experiment.audits(c)); }

    @Test void commitAbsorbsTheJournalWithoutSubtractingAvailableTwiceOrReopening() {
        var c = committed(); var original = redis.opsForHash().entries(ledgerKey(c));
        assertEquals("APPLIED", consume(c));
        assertEquals(original, redis.opsForHash().entries(ledgerKey(c)));
        assertEquals("3", original.get("_available")); assertEquals("1", original.get("_committed"));
        assertEquals(c.toRevision(), experiment.revision(c)); assertEquals(2, experiment.audits(c));
        assertEquals("DIRTY", projections.inspect(c.ticketId()).state());
        assertEquals(1L, projections.inspect(c.ticketId()).preparedRevision());
        assertEquals(RedisTicketLedger.Phase.FROZEN, fixtures.ledger.snapshot(c.ticketId(), 1).snapshot().phase());
        assertEquals(3, fixtures.db.queryForObject("SELECT capacity-held-sold FROM ticket_type WHERE id=?", Integer.class, c.ticketId()));
    }

    @Test void sixConcurrentOriginalRetriesAdvanceOneCursorWithOneAck() throws Exception {
        var c = committed();
        try (var pool = Executors.newFixedThreadPool(6)) {
            var jobs = new ArrayList<Callable<String>>(); for (int n=0;n<6;n++) jobs.add(() -> consume(c));
            int changed = 0;
            for (var future : pool.invokeAll(jobs)) {
                var result = future.get(); assertTrue(List.of("APPLIED", "PG_REPLAY").contains(result));
                if (result.equals("APPLIED")) changed++;
            }
            assertEquals(1, changed);
        }
        assertEquals(1, ack(c)); assertEquals(2, experiment.audits(c));
    }

    @Test void redisCursorSuccessThenPgAuditRollbackRecoversTheExactCommand() {
        var c = committed(); var original = experiment.receipt(c); fault(c);
        try { assertThrows(DataAccessException.class, () -> consume(c)); }
        finally { unfault(); }
        notConsumed(c);
        assertEquals("2", redis.opsForHash().get(cursorKey(c), "_revision"));
        assertEquals(original, experiment.receipt(c));
        assertEquals("REDIS_REPLAY", consume(c)); assertEquals("PG_REPLAY", consume(c));
        assertEquals(original, experiment.receipt(c)); assertEquals(2, experiment.audits(c));
    }

    @Test void provenanceFailureRollsBackTheOriginalWorkerOrderStockAndDecision() {
        var f = fixtures.accepted(false); var id = UUID.randomUUID();
        fixtures.db.execute("ALTER TABLE stock_commit_experiment.command ADD CONSTRAINT cursor_register_fault "
                + "CHECK(request_id<>'"+id+"'::uuid) NOT VALID");
        try { assertThrows(DataAccessException.class, () -> fixtures.processAndRegister(f, id)); }
        finally { fixtures.db.execute("ALTER TABLE stock_commit_experiment.command DROP CONSTRAINT cursor_register_fault"); }
        assertEquals("PROCESSING", fixtures.db.queryForObject("SELECT status FROM purchase_request WHERE id=?", String.class, f.purchase()));
        assertEquals(0, fixtures.db.queryForObject("SELECT count(*) FROM ticket_order WHERE ticket_id=?", Integer.class, f.ticket()));
        assertEquals(0, fixtures.db.queryForObject("SELECT count(*) FROM stock_hold_decision WHERE intent_id=?", Integer.class, f.intent()));
        assertEquals(1, projections.inspect(f.ticket()).sourceRevision());
        assertTrue(redis.opsForHash().get(fixtures.ledger.key(f.ticket(),1),"r:"+f.intent()).toString().contains("HELD"));
        var c = fixtures.processAndRegister(f, id); assertEquals("COMMITTED", settlement.reconcile(f.intent()).resolution());
        assertEquals("APPLIED", consume(c));
    }

    @Test void retroactiveAttributionToAnAlreadyCommittedJournalIsRejected() {
        var f = fixtures.accepted(false); assertTrue(purchases.process(f.event()));
        assertThrows(DataAccessException.class, () -> new TransactionTemplate(transactions).execute(status ->
                experiment.register(UUID.randomUUID(), f.intent(), 1)));
        assertEquals(0, fixtures.db.queryForObject("SELECT count(*) FROM stock_commit_experiment.command WHERE intent_id=?", Integer.class, f.intent()));
    }

    @Test void aProcessingPurchaseCannotSupplyAFormalCommitCommand() {
        var f = fixtures.accepted(false);
        assertThrows(DataAccessException.class, () -> new TransactionTemplate(transactions).execute(status ->
                experiment.register(UUID.randomUUID(), f.intent(), 1)));
    }

    @Test void activeOriginalHoldsBlockConsumptionWithoutModifyingEitherLedger() {
        var f = fixtures.accepted(true); var c = fixtures.processAndRegister(f, UUID.randomUUID());
        settlement.reconcile(f.intent()); var original = redis.opsForHash().entries(ledgerKey(c));
        assertEquals("ACTIVE_HOLDS", consume(c)); notConsumed(c);
        assertEquals(original, redis.opsForHash().entries(ledgerKey(c)));
    }

    @Test void heldOrUnacknowledgedReceiptCannotAdvanceEvidenceCursor() {
        var f = fixtures.accepted(false); var c = fixtures.processAndRegister(f, UUID.randomUUID());
        assertEquals("DECISION_NOT_ACKNOWLEDGED", consume(c)); notConsumed(c);
    }

    @Test void absentAcknowledgementCannotBeTreatedAsACommittedReceipt() {
        var f = fixtures.accepted(false); var c = fixtures.processAndRegister(f, UUID.randomUUID());
        redis.opsForHash().delete(ledgerKey(c), "r:"+c.intentId());
        redis.opsForHash().putAll(ledgerKey(c), java.util.Map.of("_entries","0","_available","4","_held","0"));
        assertEquals("ABSENT", settlement.reconcile(f.intent()).resolution());
        assertEquals("DECISION_NOT_ACKNOWLEDGED", consume(c)); notConsumed(c);
    }

    @Test void oppositeTerminalReceiptCannotReverseTheFormalDecision() {
        var f = fixtures.accepted(false); var c = fixtures.processAndRegister(f, UUID.randomUUID());
        var claim = new RedisTicketLedger.Claim(1,c.intentId(),c.ownerId(),c.quantity(),c.deadlineMs(),c.fingerprint());
        fixtures.ledger.release(c.ticketId(), claim);
        assertEquals("REDIS_DECISION_CONFLICT", settlement.reconcile(c.intentId()).failure());
        assertEquals("DECISION_NOT_ACKNOWLEDGED", consume(c)); notConsumed(c);
    }

    @Test void lostOriginalOrSidecarKeyDoesNotInitializeOrSeed() {
        for (boolean sidecar : List.of(false,true)) {
            var c = committed(); String key = sidecar ? cursorKey(c) : ledgerKey(c); redis.delete(key);
            assertEquals("MISSING", consume(c)); notConsumed(c); assertFalse(redis.hasKey(key));
        }
    }

    @Test void originalLedgerTtlWrongTypeOrInvalidCountersAreBlocked() {
        var ttl = committed(); redis.expire(ledgerKey(ttl),Duration.ofMinutes(5));
        assertEquals("CORRUPT",consume(ttl)); notConsumed(ttl);
        var wrong = committed(); redis.delete(ledgerKey(wrong)); redis.opsForValue().set(ledgerKey(wrong),"wrong-type");
        assertEquals("CORRUPT",consume(wrong)); assertEquals("wrong-type",redis.opsForValue().get(ledgerKey(wrong))); notConsumed(wrong);
        var bad = committed(); redis.opsForHash().put(ledgerKey(bad),"_available","99");
        assertEquals("CORRUPT",consume(bad)); notConsumed(bad);
    }

    @Test void sidecarTtlOrForgedMarkerDoesNotAcknowledge() {
        var c = committed(); redis.expire(cursorKey(c),Duration.ofMinutes(5));
        assertEquals("CORRUPT",consume(c)); notConsumed(c);
        var other = committed(); redis.opsForHash().put(cursorKey(other),"a:"+other.requestId(),"forged");
        assertEquals("COMMAND_CONFLICT",consume(other)); notConsumed(other);
    }

    @Test void anExistingPgAckDoesNotAuthorizeAChangedOriginalRedisReceipt() {
        for (boolean changedOwner : List.of(false,true)) {
            var c=committed(); var original=experiment.receipt(c);
            var changed=changedOwner ? original.replace(c.ownerId().toString(),UUID.randomUUID().toString())
                    : original.replace("COMMITTED","RELEASED");
            assertNotEquals(original,changed);
            redis.opsForHash().put(ledgerKey(c),"r:"+c.intentId(),changed);
            assertEquals(changedOwner ? "CLAIM_CONFLICT" : "DECISION_CONFLICT",consume(c));
            notConsumed(c); assertEquals(changed,experiment.receipt(c));
        }
    }

    @Test void aLaterCancellationJournalCannotBeSkipped() {
        var f = fixtures.accepted(false); var current = fixtures.processAndRegister(f, UUID.randomUUID());
        settlement.reconcile(f.intent()); orders.cancel(f.owner(),current.orderId(),new Cancel(UUID.randomUUID(),1));
        assertEquals("SOURCE_CHANGED",consume(current)); notConsumed(current);
        assertEquals("COMMIT",fixtures.db.queryForObject("SELECT target FROM stock_hold_decision WHERE intent_id=?",String.class,current.intentId()));
    }

    @Test void aNewOfficialGenerationFencesTheUnconsumedOldCommand() {
        var c = committed(); var next = projections.begin(c.ticketId(), UUID.randomUUID(), projections.inspect(c.ticketId()).sourceRevision());
        assertEquals("READY",projections.complete(next.requestId()).state());
        assertEquals("FENCED",consume(c)); notConsumed(c);
        assertEquals(3,fixtures.ledger.snapshot(c.ticketId(),2).snapshot().available());
    }

    @Test void commandAckAuditAndCursorEvidenceCannotBeRewritten() {
        var c=committed(); assertEquals("APPLIED",consume(c));
        for(String table:List.of("command","ack","audit")) {
            assertThrows(DataAccessException.class,()->fixtures.db.update("DELETE FROM stock_commit_experiment."+table+" WHERE request_id=?",c.requestId()));
            assertThrows(DataAccessException.class,()->fixtures.db.update("UPDATE stock_commit_experiment."+table+" SET request_id=request_id WHERE request_id=?",c.requestId()));
        }
        assertThrows(DataAccessException.class,()->fixtures.db.update("UPDATE stock_commit_experiment.cursor SET revision=revision+1 WHERE ticket_id=?",c.ticketId()));
    }

    @Test void changedBodyOrNewUuidCannotAdoptTheOriginalOperation() {
        var c=committed(); assertEquals("COMMAND_CONFLICT",experiment.consume(c.requestId(),"0".repeat(64))); notConsumed(c);
        assertThrows(DataAccessException.class,()->new TransactionTemplate(transactions).execute(status->experiment.register(UUID.randomUUID(),c.intentId(),1)));
    }

    @Test void anAckCannotCommitWithoutTheExactFormalEvidenceAndAtomicCursor() {
        var f=fixtures.accepted(false); var c=fixtures.processAndRegister(f,UUID.randomUUID());
        assertThrows(DataAccessException.class,()->fixtures.db.update("INSERT INTO stock_commit_experiment.ack VALUES(?,?)",c.requestId(),"0".repeat(64)));
        notConsumed(c); settlement.reconcile(f.intent());
        assertThrows(DataAccessException.class,()->fixtures.db.update("INSERT INTO stock_commit_experiment.ack VALUES(?,?)",c.requestId(),"0".repeat(64)));
        notConsumed(c); assertEquals("APPLIED",consume(c));
    }

    @Test void independentPhasesAndAnUnusedSeedAreRequired() {
        var c=committed();
        new TransactionTemplate(transactions).executeWithoutResult(status -> assertThrows(IllegalStateException.class,()->consume(c)));
        assertThrows(IllegalStateException.class,()->experiment.register(UUID.randomUUID(),c.intentId(),1));
        assertThrows(IllegalStateException.class,()->experiment.seed(c.ticketId()));
        notConsumed(c);
    }
}
