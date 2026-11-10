package dev.jackteng.encore.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Executable design proof, TEST CLASSPATH ONLY. No bean, API, scheduler or READY writer. */
public final class CommitCursorExperiment {
    public record Command(UUID requestId, UUID ticketId, long generation, long fromRevision,
                          long toRevision, UUID intentId, UUID orderId, UUID ownerId,
                          int quantity, long deadlineMs, String fingerprint, int baseline) {}
    public record RedisEvidence(String code, String receipt) {}

    private final JdbcTemplate db;
    private final StringRedisTemplate redis;
    private final RedisTicketLedger ledger;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final DefaultRedisScript<String> script = new DefaultRedisScript<>();

    public CommitCursorExperiment(JdbcTemplate db, StringRedisTemplate redis, ObjectMapper json,
                                  String namespace, PlatformTransactionManager manager) {
        if (!"ticket_demo".equals(db.queryForObject("SELECT current_database()", String.class))
                || !namespace.matches("encore-demo-increment-[a-z0-9-]{1,28}")) {
            throw new IllegalArgumentException("Requires an isolated incremental experiment scope");
        }
        this.db = db;
        this.redis = redis;
        this.json = json;
        this.ledger = RedisTicketLedger.epochs(redis, json, namespace);
        this.tx = new TransactionTemplate(manager);
        tx.setTimeout(10);
        script.setLocation(new ClassPathResource("experiments/commit-cursor.lua"));
        script.setResultType(String.class);
    }

    public void createSchema() {
        if (Boolean.TRUE.equals(db.queryForObject(
                "SELECT to_regnamespace('stock_commit_experiment') IS NOT NULL", Boolean.class))) return;
        try {
            var sql = new ClassPathResource("experiments/commit-cursor-schema.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            tx.executeWithoutResult(status -> db.execute(sql));
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    public String cursorKey(UUID ticket, long generation) {
        return ledger.key(ticket, generation) + ":commit-experiment";
    }

    /** Synthetic setup ONLY, before any intent. Not a recovery/reseed implementation. */
    public void seed(UUID ticket) {
        outside();
        tx.executeWithoutResult(status -> {
            lockTicket(ticket);
            var gate = db.queryForMap("SELECT * FROM stock_projection WHERE ticket_id=?", ticket);
            long g = ((Number) gate.get("generation")).longValue();
            long revision = ((Number) gate.get("source_revision")).longValue();
            var original = ledger.snapshot(ticket, g);
            if (!"READY".equals(gate.get("state")) || original.code() != RedisTicketLedger.Code.OK
                    || original.snapshot().phase() != RedisTicketLedger.Phase.OPEN
                    || original.snapshot().entries() != 0) throw new IllegalStateException("Seed not pristine");
            String key = cursorKey(ticket, g);
            if (Boolean.TRUE.equals(redis.hasKey(key))) throw new IllegalStateException("Seed exists");
            db.update("INSERT INTO stock_commit_experiment.cursor VALUES(?,?,?,NULL)", ticket, g, revision);
            redis.opsForHash().putAll(key, Map.of("_generation", "" + g,
                    "_revision", "" + revision, "_last_request", "NONE"));
        });
    }

    /** Must run inside the real worker's original PG transaction after its COMMIT.
     * The SQL trigger verifies xmin, exact journal images and the original HOLD event.
     */
    public Command register(UUID request, UUID intent, long fromRevision) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Provenance requires the formal worker transaction");
        }
        var i = db.queryForMap("SELECT i.*,d.order_id FROM stock_hold_intent i "
                + "JOIN stock_hold_decision d ON d.intent_id=i.id AND d.target='COMMIT' WHERE i.id=?", intent);
        UUID ticket = (UUID) i.get("ticket_id");
        long g = ((Number) i.get("generation")).longValue();
        int baseline = db.queryForObject("SELECT capacity FROM stock_revision WHERE ticket_id=? AND revision=?",
                Integer.class, ticket, fromRevision);
        long deadline = db.queryForObject("SELECT floor(extract(epoch FROM deadline_at)*1000)::bigint "
                + "FROM stock_hold_intent WHERE id=?", Long.class, intent);
        db.update("INSERT INTO stock_commit_experiment.command VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                request, ticket, g, fromRevision, fromRevision + 1, intent, i.get("order_id"),
                i.get("customer_id"), i.get("quantity"), deadline, i.get("claim_fingerprint"), baseline);
        return command(request);
    }

    public Command command(UUID request) {
        return db.queryForObject("SELECT * FROM stock_commit_experiment.command WHERE request_id=?",
                (r, n) -> new Command(request, r.getObject("ticket_id", UUID.class), r.getLong("generation"),
                        r.getLong("from_revision"), r.getLong("to_revision"), r.getObject("intent_id", UUID.class),
                        r.getObject("order_id", UUID.class), r.getObject("owner_id", UUID.class),
                        r.getInt("quantity"), r.getLong("deadline_ms"), r.getString("fingerprint"),
                        r.getInt("baseline")), request);
    }

    public static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public String digest(Command c) {
        try { return sha(json.writeValueAsString(c)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public String receipt(Command c) {
        return (String) redis.opsForHash().get(ledger.key(c.ticketId(), c.generation()), "r:" + c.intentId());
    }

    public long revision(Command c) {
        return db.queryForObject("SELECT revision FROM stock_commit_experiment.cursor WHERE ticket_id=? AND generation=?",
                Long.class, c.ticketId(), c.generation());
    }

    public int audits(Command c) {
        return db.queryForObject("SELECT count(*) FROM stock_commit_experiment.audit WHERE request_id=?",
                Integer.class, c.requestId());
    }

    private void lockTicket(UUID ticket) {
        db.queryForObject("SELECT id FROM ticket_type WHERE id=? FOR UPDATE /* experiment only */", UUID.class, ticket);
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Consumption requires an independently committed original command");
        }
    }

    public String consume(UUID request, String expectedDigest) {
        outside();
        Command c = command(request); // PG original is the body, never a Redis/message payload.
        if (!digest(c).equals(expectedDigest)) return "COMMAND_CONFLICT";
        return tx.execute(status -> {
            lockTicket(c.ticketId());
            var cursor = db.queryForMap("SELECT * FROM stock_commit_experiment.cursor "
                    + "WHERE ticket_id=? AND generation=? FOR UPDATE", c.ticketId(), c.generation());
            if (db.queryForObject("SELECT count(*) FROM stock_commit_experiment.ack WHERE request_id=?",
                    Integer.class, request) == 1) return "PG_REPLAY"; // Not proof that the current Redis gate is usable.
            if (((Number) cursor.get("revision")).longValue() != c.fromRevision()) return "CURSOR_GAP";
            var gate = db.queryForMap("SELECT * FROM stock_projection WHERE ticket_id=?", c.ticketId());
            if (((Number) gate.get("generation")).longValue() != c.generation()) return "FENCED";
            if (((Number) gate.get("source_revision")).longValue() != c.toRevision()
                    || !Long.valueOf(c.fromRevision()).equals(gate.get("prepared_revision"))
                    || !"DIRTY".equals(gate.get("state"))) return "SOURCE_CHANGED";
            if (db.queryForObject("SELECT count(*) FROM stock_hold_decision d JOIN stock_hold_ack a USING(intent_id) "
                    + "WHERE d.intent_id=? AND d.target='COMMIT' AND d.order_id=? AND a.resolution='COMMITTED'",
                    Integer.class, c.intentId(), c.orderId()) != 1) return "DECISION_NOT_ACKNOWLEDGED";
            String result;
            String original;
            try {
                result = redis.execute(script, List.of(ledger.key(c.ticketId(), c.generation()),
                                cursorKey(c.ticketId(), c.generation())), "" + c.generation(),
                        "" + c.fromRevision(), "" + c.toRevision(), request.toString(), c.intentId().toString(),
                        c.ownerId().toString(), "" + c.quantity(), "" + c.deadlineMs(), c.fingerprint(),
                        "" + c.baseline(), expectedDigest);
                if (result == null) return "UNKNOWN";
            } catch (org.springframework.dao.DataAccessException e) { return "UNKNOWN"; }
            if (!result.startsWith("{")) return result;
            try {
                var evidence = json.readValue(result, RedisEvidence.class);
                result = evidence.code();
                original = evidence.receipt();
                if (!List.of("APPLIED", "REPLAY").contains(result) || original == null) return "UNKNOWN";
            } catch (Exception e) { return "UNKNOWN"; }
            db.update("INSERT INTO stock_commit_experiment.ack VALUES(?,?)", request, sha(original));
            db.update("UPDATE stock_commit_experiment.cursor SET revision=?,last_request=? "
                    + "WHERE ticket_id=? AND generation=?", c.toRevision(), request, c.ticketId(), c.generation());
            return result.equals("REPLAY") ? "REDIS_REPLAY" : "APPLIED";
        });
    }
}
