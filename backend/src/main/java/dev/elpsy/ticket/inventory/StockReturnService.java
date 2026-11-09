package dev.elpsy.ticket.inventory;

import dev.elpsy.ticket.common.ApiErrors.Problem;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit internal handoff after a formal return. No endpoint, scheduler or auto consumer.
 * Uses the whole PG snapshot in a NEW epoch; never mutates a COMMITTED receipt or INCRs stock.
 * HTTP callers must add verified actor/permissions before exposing this primitive.
 */
@Service
public class StockReturnService {
    public record Command(UUID requestId, UUID ticketId, UUID orderId, UUID intentId,
                          long releaseRevision, long sourceGeneration, long generation, int available) {}
    public record Outcome(Command command, String state, String failure, boolean historicalReplay) {}
    private final JdbcTemplate db;
    private final StockProjectionService projections;
    private final TransactionTemplate write;

    public StockReturnService(JdbcTemplate db, StockProjectionService projections, PlatformTransactionManager transactions) {
        this.db=db; this.projections=projections; write=new TransactionTemplate(transactions); write.setTimeout(10);
    }
    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Return handoff requires independently committed phases");
    }
    private static Problem conflict(String code) {
        return new Problem(409,code,"返還需要核對原命令與新庫存世代");
    }
    private Command find(UUID request) {
        var rows=db.query("SELECT * FROM stock_return_command WHERE request_id=?",(r,n)->new Command(request,
            r.getObject("ticket_id",UUID.class),r.getObject("order_id",UUID.class),r.getObject("intent_id",UUID.class),
            r.getLong("release_revision"),r.getLong("source_generation"),r.getLong("generation"),r.getInt("available")),request);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Command inspect(UUID request) {
        var c=find(request);
        if (c==null) throw new Problem(404,"NOT_FOUND","找不到原返還命令");
        return c;
    }
    private void same(Command c,UUID order,long revision) {
        if (!c.orderId().equals(order)||c.releaseRevision()!=revision) throw conflict("RETURN_COMMAND_CONFLICT");
    }
    /** Commit command + fresh CLOSED generation + REGISTERED audit BEFORE Redis.
     * Caller supplies only original identity/revision, never quantity or a Redis balance.
     */
    public Command begin(UUID request,UUID order,long releaseRevision) {
        return begin(request,order,releaseRevision,()->{},c->{});
    }
    /** Permission/actor guard precedes the ticket lock; checkpoint shares the fresh snapshot transaction. */
    public Command begin(UUID request,UUID order,long releaseRevision,Runnable guard,java.util.function.Consumer<Command> committed) {
        outside();
        if (request==null||order==null||releaseRevision<1) throw new IllegalArgumentException("Return identity required");
        var prior=find(request);
        if (prior!=null) same(prior,order,releaseRevision);
        var rows=db.queryForList("SELECT o.ticket_id,o.intent_id,c.generation FROM stock_revision_origin o "
            +"JOIN stock_revision_context c USING(ticket_id,to_revision) WHERE o.order_id=? AND o.kind='ORDER_RELEASE' AND o.to_revision=?",order,releaseRevision);
        if (rows.isEmpty()||rows.getFirst().get("intent_id")==null) throw conflict("RETURN_ORIGIN_REQUIRED");
        var source=rows.getFirst(); var ticket=(UUID)source.get("ticket_id");
        long generation=((Number)source.get("generation")).longValue();
        projections.begin(ticket,request,releaseRevision,()->{
            guard.run();
            db.queryForObject("SELECT id FROM ticket_type WHERE id=? FOR UPDATE /* return handoff guard */",UUID.class,ticket);
            var old=find(request);
            if (old!=null) { same(old,order,releaseRevision); return; }
            if (db.queryForObject("SELECT count(*) FROM stock_rebuild_command WHERE request_id=?",Integer.class,request)>0
                ||db.queryForObject("SELECT count(*) FROM stock_return_command WHERE order_id=?",Integer.class,order)>0)
                throw conflict("RETURN_COMMAND_CONFLICT");
            if (db.queryForObject("SELECT count(*) FROM stock_hold_decision d JOIN stock_hold_ack a USING(intent_id) "
                +"WHERE d.intent_id=? AND d.order_id=? AND d.target='COMMIT' AND a.resolution='COMMITTED'",Integer.class,source.get("intent_id"),order)!=1)
                throw conflict("RETURN_COMMIT_NOT_ACKNOWLEDGED");
        },rebuild->{
            // Runs under the ORIGINAL projection ticket lock; source head was checked by begin.
            if (rebuild.generation()!=generation+1) throw conflict("RETURN_ALREADY_INCLUDED");
            db.update("INSERT INTO stock_return_command(request_id,ticket_id,order_id,intent_id,release_revision,source_generation,generation,available) VALUES(?,?,?,?,?,?,?,?)",
                request,ticket,order,source.get("intent_id"),releaseRevision,generation,rebuild.generation(),rebuild.available());
            committed.accept(inspect(request));
        });
        return inspect(request);
    }
    /** Historical APPLIED is PG-only. It does not certify current Redis health or READY.
     * A READY audit failure rolls back PG while Redis may already be OPEN; retry ORIGINAL UUID.
     */
    private Outcome complete(UUID request,Runnable guard,java.util.function.Consumer<Outcome> committed) {
        outside(); var c=inspect(request);
        var result=projections.complete(request,()->{
            guard.run();
            db.queryForObject("SELECT id FROM ticket_type WHERE id=? FOR UPDATE /* return checkpoint guard */",UUID.class,c.ticketId());
            if (db.queryForObject("SELECT count(*) FROM stock_return_ack WHERE request_id=?",Integer.class,request)>0)
                throw new HistoricalReplay();
        },prepared->{
            if ("READY".equals(prepared.state())) db.update("INSERT INTO stock_return_ack(request_id) VALUES(?)",request);
            committed.accept(new Outcome(c,"READY".equals(prepared.state())?"APPLIED":"PENDING",prepared.failure(),false));
        });
        return new Outcome(c,"READY".equals(result.state())?"APPLIED":"PENDING",result.failure(),false);
    }
    private static class HistoricalReplay extends RuntimeException {}
    /** Serialize the PG-only historical decision with concurrent completion using the same ticket lock. */
    public Outcome reconcile(UUID request) {
        return reconcile(request,()->{},o->{});
    }
    /** A historical checkpoint is also independently committed with the same actor guard, PG-only. */
    public Outcome reconcile(UUID request,Runnable guard,java.util.function.Consumer<Outcome> committed) {
        try { return complete(request,guard,committed); }
        catch (HistoricalReplay replay) {
            return write.execute(tx->{
                guard.run(); var c=inspect(request);
                db.queryForObject("SELECT id FROM ticket_type WHERE id=? FOR UPDATE /* historical return checkpoint */",UUID.class,c.ticketId());
                if (db.queryForObject("SELECT count(*) FROM stock_return_ack WHERE request_id=?",Integer.class,request)!=1)
                    throw conflict("RETURN_ACK_REQUIRED");
                var outcome=new Outcome(c,"APPLIED",null,true); committed.accept(outcome); return outcome;
            });
        }
    }
}
