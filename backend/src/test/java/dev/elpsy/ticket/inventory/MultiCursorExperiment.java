package dev.elpsy.ticket.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** C3 bounded fixed-head evidence experiment; no bean, production writer or READY publication. */
public final class MultiCursorExperiment {
    public record Command(UUID request, UUID ticket, long generation, long from, long to, String plan, String digest) {}
    private final JdbcTemplate db;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final RedisTicketLedger ledger;
    private final TransactionTemplate tx;
    private final DefaultRedisScript<String> script = new DefaultRedisScript<>();

    public MultiCursorExperiment(JdbcTemplate db, StringRedisTemplate redis, ObjectMapper json,
                                 String namespace, PlatformTransactionManager manager) {
        if (!"ticket_demo".equals(db.queryForObject("SELECT current_database()", String.class))
                || !namespace.matches("encore-demo-increment-[a-z0-9-]{1,28}"))
            throw new IllegalArgumentException("Requires isolated incremental experiment");
        this.db=db; this.redis=redis; this.json=json;
        ledger=RedisTicketLedger.epochs(redis,json,namespace);
        tx=new TransactionTemplate(manager); tx.setTimeout(10);
        script.setLocation(new ClassPathResource("experiments/multi-cursor.lua")); script.setResultType(String.class);
    }
    public void createSchema() {
        outside();
        if (Boolean.TRUE.equals(db.queryForObject("SELECT to_regnamespace('stock_multi_experiment') IS NOT NULL",Boolean.class))) return;
        try { var sql=new ClassPathResource("experiments/multi-cursor-schema.sql").getContentAsString(StandardCharsets.UTF_8);
            tx.executeWithoutResult(s->db.execute(sql));
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    public String key(UUID ticket,long generation) { return ledger.key(ticket,generation)+":multi-experiment"; }
    private void lock(UUID ticket) { db.queryForObject("SELECT id FROM ticket_type WHERE id=? FOR UPDATE /* C3 test only */",UUID.class,ticket); }
    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Independent committed phase required");
    }
    private JsonNode parsed(Command c) {
        try { return json.readTree(c.plan()); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    /** Synthetic initialization before ALL intents, never a recovery/reseed action. */
    public void seed(UUID ticket) {
        outside();
        tx.executeWithoutResult(s->{
            lock(ticket);
            var p=db.queryForMap("SELECT * FROM stock_projection WHERE ticket_id=?",ticket);
            long g=((Number)p.get("generation")).longValue(),r=((Number)p.get("source_revision")).longValue();
            var snapshot=ledger.snapshot(ticket,g);
            if (!"READY".equals(p.get("state")) || snapshot.code()!=RedisTicketLedger.Code.OK
                || snapshot.snapshot().phase()!=RedisTicketLedger.Phase.OPEN || snapshot.snapshot().entries()!=0
                || snapshot.snapshot().held()!=0 || snapshot.snapshot().committed()!=0 || Boolean.TRUE.equals(redis.hasKey(key(ticket,g))))
                throw new IllegalStateException("Pristine seed required");
            int baseline=snapshot.snapshot().available();
            db.update("INSERT INTO stock_multi_experiment.cursor VALUES(?,?,?,?,?,?,NULL)",ticket,g,r,baseline,r,baseline);
            redis.opsForHash().putAll(key(ticket,g),Map.of("_generation",""+g,"_revision",""+r,
                "_available",""+baseline,"_last_request","NONE","_count","0"));
        });
    }
    /** C2 already recorded committed provenance. Registration never invents an origin. */
    public Command register(UUID request,UUID ticket,long from) {
        outside();
        return tx.execute(s->{
            lock(ticket);
            long g=db.queryForObject("SELECT generation FROM stock_projection WHERE ticket_id=?",Long.class,ticket);
            String plan=db.queryForObject("SELECT stock_multi_experiment.plan(?,?,?)::text",String.class,ticket,g,from);
            String digest=CommitCursorExperiment.sha(request+":"+plan);
            var old=db.queryForList("SELECT request_id FROM stock_multi_experiment.command WHERE request_id=?",request);
            if (!old.isEmpty()) {
                var c=command(request);
                if (!c.ticket().equals(ticket) || c.from()!=from || !c.digest().equals(digest)) throw new IllegalStateException("COMMAND_CONFLICT");
                return c;
            }
            db.update("INSERT INTO stock_multi_experiment.command VALUES(?,?,?,?,?,?::jsonb,?)",request,ticket,g,from,from+1,plan,digest);
            return command(request);
        });
    }
    public Command command(UUID id) {
        return db.queryForObject("SELECT *,plan::text AS body FROM stock_multi_experiment.command WHERE request_id=?",
            (r,n)->new Command(id,r.getObject("ticket_id",UUID.class),r.getLong("generation"),r.getLong("from_revision"),
                r.getLong("to_revision"),r.getString("body"),r.getString("digest")),id);
    }
    public String consume(UUID request,String expectedDigest) {
        outside(); var c=command(request);
        if (!c.digest().equals(expectedDigest) || !c.digest().equals(CommitCursorExperiment.sha(request+":"+c.plan()))) return "COMMAND_CONFLICT";
        return tx.execute(s->{
            lock(c.ticket());
            var x=db.queryForMap("SELECT * FROM stock_multi_experiment.cursor WHERE ticket_id=? AND generation=? FOR UPDATE",c.ticket(),c.generation());
            if (db.queryForObject("SELECT count(*) FROM stock_multi_experiment.ack WHERE request_id=?",Integer.class,request)==1) return "PG_REPLAY";
            if (((Number)x.get("revision")).longValue()!=c.from()) return "CURSOR_GAP";
            var p=db.queryForMap("SELECT * FROM stock_projection WHERE ticket_id=?",c.ticket());
            if (((Number)p.get("generation")).longValue()!=c.generation()) return "FENCED";
            if (((Number)p.get("source_revision")).longValue()!=parsed(c).get("head").asLong()) return "SOURCE_CHANGED";
            String proof=db.queryForObject("SELECT stock_multi_experiment.plan(?,?,?)::text",String.class,c.ticket(),c.generation(),c.from());
            if (!proof.equals(c.plan())) return "PROOF_CHANGED";
            var prior=db.queryForList("SELECT c.request_id::text AS request,c.digest FROM stock_multi_experiment.command c "
                +"JOIN stock_multi_experiment.ack a USING(request_id) WHERE c.ticket_id=? AND c.generation=? ORDER BY c.to_revision",c.ticket(),c.generation());
            if (prior.size()!=c.from()-((Number)x.get("root_revision")).longValue()) return "CHECKPOINT_CONFLICT";
            String result;
            try { result=redis.execute(script,List.of(ledger.key(c.ticket(),c.generation()),key(c.ticket(),c.generation())),
                c.plan(),request.toString(),c.digest(),json.writeValueAsString(prior));
            } catch (org.springframework.dao.DataAccessException e) { return "UNKNOWN"; }
              catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
            if (result==null) return "UNKNOWN";
            if (!List.of("APPLIED","REPLAY").contains(result)) return result;
            db.update("INSERT INTO stock_multi_experiment.ack VALUES(?,?)",request,c.digest());
            db.update("UPDATE stock_multi_experiment.cursor SET revision=?,available=?,last_request=? WHERE ticket_id=? AND generation=?",
                c.to(),parsed(c).get("afterAvailable").asInt(),request,c.ticket(),c.generation());
            return result.equals("REPLAY") ? "REDIS_REPLAY" : result;
        });
    }
}
