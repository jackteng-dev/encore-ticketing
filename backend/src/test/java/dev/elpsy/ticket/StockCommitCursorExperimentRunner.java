package dev.elpsy.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.elpsy.ticket.admission.AdmissionSettings;
import dev.elpsy.ticket.inventory.*;
import dev.elpsy.ticket.inventory.CommitCursorExperiment.Command;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Separate-process proof harness, never packaged in the production JAR. */
public final class StockCommitCursorExperimentRunner {
    public record Saved(UUID requestId, String digest, String ledgerSha, String receiptSha, String domainSha) {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private static Path privatePath(String argument) {
        Path path = Path.of(argument).toAbsolutePath().normalize();
        require(path.startsWith(Path.of("work").toAbsolutePath().normalize()), "Output must be in private work/");
        return path;
    }
    private static String ledgerSha(CommitCursorExperiment experiment, StringRedisTemplate redis, Command c) throws Exception {
        var ledger = experiment.cursorKey(c.ticketId(), c.generation()).replace(":commit-experiment", "");
        return CommitCursorExperiment.sha(new ObjectMapper().writeValueAsString(new TreeMap<>(redis.opsForHash().entries(ledger))));
    }
    private static String domainSha(JdbcTemplate db, Command c) {
        return CommitCursorExperiment.sha(db.queryForObject("SELECT jsonb_build_object("
                + "'intent',(SELECT to_jsonb(i) FROM stock_hold_intent i WHERE id=?),"
                + "'progress',(SELECT to_jsonb(p) FROM stock_hold_progress p WHERE intent_id=?),"
                + "'acceptance',(SELECT to_jsonb(a) FROM stock_hold_acceptance a WHERE intent_id=?),"
                + "'decision',(SELECT to_jsonb(d) FROM stock_hold_decision d WHERE intent_id=?),"
                + "'ack',(SELECT to_jsonb(a) FROM stock_hold_ack a WHERE intent_id=?),"
                + "'purchase',(SELECT to_jsonb(p) FROM purchase_request p WHERE id=(SELECT purchase_id FROM stock_hold_acceptance WHERE intent_id=?)),"
                + "'order',(SELECT to_jsonb(o) FROM ticket_order o WHERE id=?),"
                + "'event',(SELECT to_jsonb(e) FROM order_inventory_event e WHERE order_id=? AND phase='HOLD'),"
                + "'stock',(SELECT to_jsonb(t) FROM ticket_type t WHERE id=?),"
                + "'gate',(SELECT to_jsonb(p) FROM stock_projection p WHERE ticket_id=?),"
                + "'journal',(SELECT jsonb_agg(to_jsonb(r) ORDER BY revision) FROM stock_revision r WHERE ticket_id=?)"
                + ")::text", String.class, c.intentId(),c.intentId(),c.intentId(),c.intentId(),c.intentId(),c.intentId(),
                c.orderId(),c.orderId(),c.ticketId(),c.ticketId(),c.ticketId()));
    }

    public static void main(String[] args) throws Exception {
        require(args.length==3, "Use prepare|offline|verify, private fixture path, private result path");
        require(System.getenv("COMPOSE_PROJECT_NAME") != null
                && System.getenv("COMPOSE_PROJECT_NAME").matches("ticket-increment-[a-z0-9-]{1,32}"), "Own experiment scope required");
        require(java.util.List.of("prepare","offline","verify").contains(args[0]), "Unknown phase");
        var fixturePath=privatePath(args[1]); var output=privatePath(args[2]);
        try(var context=SpringApplication.run(TicketApplication.class,
                "--server.port=0", "--ticket.orders.enabled=true", "--ticket.purchases.enabled=true",
                "--ticket.delivery.enabled=false", "--ticket.delivery.publisher-enabled=false",
                "--ticket.delivery.consumer-enabled=false", "--ticket.expiry.enabled=false",
                "--ticket.stock-recovery.enabled=false", "--ticket.stock-maintenance.enabled=false",
                "--ticket.stock-review.enabled=false", "--ticket.pre-debit.enabled=false",
                "--ticket.admission.enabled=false", "--ticket.demo-bootstrap=false")) {
            var db=context.getBean(JdbcTemplate.class); var redis=context.getBean(StringRedisTemplate.class);
            var json=context.getBean(ObjectMapper.class); var namespace=context.getBean(AdmissionSettings.class).namespace();
            var experiment=new CommitCursorExperiment(db,redis,json,namespace,context.getBean(PlatformTransactionManager.class));
            experiment.createSchema(); var checks=new ArrayList<String>();
            Saved saved; Command c;
            if(args[0].equals("prepare")) {
                require(!Files.exists(fixturePath),"Existing original fixture preserved");
                var fixtures=new CommitCursorFixture(context); var f=fixtures.accepted(false);
                c=fixtures.processAndRegister(f,UUID.randomUUID());
                checks.add("same-transaction original worker COMMIT and provenance committed");
                require("COMMITTED".equals(context.getBean(StockSettlementService.class).reconcile(f.intent()).resolution()),"Original not committed");
                saved=new Saved(c.requestId(),experiment.digest(c),ledgerSha(experiment,redis,c),
                        CommitCursorExperiment.sha(experiment.receipt(c)),domainSha(db,c));
                db.execute("ALTER TABLE stock_commit_experiment.audit ADD CONSTRAINT runtime_cursor_fault "
                        + "CHECK(request_id<>'"+c.requestId()+"'::uuid OR phase<>'APPLIED') NOT VALID");
                boolean failed=false;
                try { experiment.consume(c.requestId(),saved.digest()); }
                catch(org.springframework.dao.DataAccessException e) { failed=true; }
                finally { db.execute("ALTER TABLE stock_commit_experiment.audit DROP CONSTRAINT runtime_cursor_fault"); }
                require(failed,"Audit fault must roll back PG"); checks.add("real PG audit CHECK failure after Redis checkpoint success");
                require(experiment.revision(c)==1 && experiment.audits(c)==1,"PG checkpoint changed");
                checks.add("PG original cursor and REGISTERED audit retained");
                require("2".equals(redis.opsForHash().get(experiment.cursorKey(c.ticketId(),1),"_revision")),"Redis cursor did not commit");
                checks.add("Redis cursor advanced once with original command tombstone");
                json.writeValue(fixturePath.toFile(),saved); Files.setPosixFilePermissions(fixturePath,PosixFilePermissions.fromString("rw-------"));
            } else {
                saved=json.readValue(fixturePath.toFile(),Saved.class); c=experiment.command(saved.requestId());
                require(saved.digest().equals(experiment.digest(c)),"Original command changed");
                checks.add("original UUID and immutable PG body survive process restart");
                if(args[0].equals("offline")) {
                    require("UNKNOWN".equals(experiment.consume(c.requestId(),saved.digest())),"Outage must stay unknown");
                    checks.add("actual Redis outage stays UNKNOWN without reseed or new UUID");
                    require(experiment.revision(c)==1 && experiment.audits(c)==1,"Unknown altered PG checkpoint");
                    checks.add("PG-only original command cursor and audit remain readable");
                } else {
                    require("REDIS_REPLAY".equals(experiment.consume(c.requestId(),saved.digest())),"Must recover original Redis marker");
                    checks.add("same AOF original command replay commits the missing PG checkpoint");
                    for(int n=0;n<6;n++) require("PG_REPLAY".equals(experiment.consume(c.requestId(),saved.digest())),"Replay changed stock");
                    checks.add("six later original retries use one acknowledged checkpoint");
                    require(experiment.revision(c)==2 && experiment.audits(c)==2,"Cursor or audit duplicated");
                    checks.add("exact next revision and two immutable phase audits");
                    require(saved.ledgerSha().equals(ledgerSha(experiment,redis,c)) && saved.receiptSha().equals(CommitCursorExperiment.sha(experiment.receipt(c))),"Original Redis data changed");
                    checks.add("whole original ledger and raw COMMITTED receipt bytes unchanged");
                }
                require(saved.domainSha().equals(domainSha(db,c)),"Formal PG original data changed");
                checks.add("original PG intent decision ack order event stock gate and journal unchanged");
            }
            require("DIRTY".equals(context.getBean(StockProjectionService.class).inspect(c.ticketId()).state()),"Do not open production gate");
            checks.add("production gate remains DIRTY without READY publication");
            json.writeValue(output.toFile(),Map.of("phase",args[0],"passed",checks.size(),"checks",checks,
                    "testClasspathOnly",true,"sourceSchemaVersion",db.queryForObject("SELECT max(version::integer) FROM flyway_schema_history WHERE success",Integer.class),"pgCursor",experiment.revision(c),"phaseAudits",experiment.audits(c)));
            Files.setPosixFilePermissions(output,PosixFilePermissions.fromString("rw-------"));
            System.out.println("Experiment phase "+args[0]+": "+checks.size()+" checks passed; no private data printed.");
        }
    }
}
