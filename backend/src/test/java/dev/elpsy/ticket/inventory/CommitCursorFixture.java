package dev.elpsy.ticket.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.elpsy.ticket.admission.AdmissionSettings;
import dev.elpsy.ticket.catalog.CatalogService;
import dev.elpsy.ticket.catalog.Models.*;
import dev.elpsy.ticket.customer.CustomerService;
import dev.elpsy.ticket.customer.CustomerService.Principal;
import dev.elpsy.ticket.orders.OrderModels.Place;
import dev.elpsy.ticket.orders.PurchaseService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Synthetic real-domain fixtures shared by JUnit and a separate-process experiment. */
public final class CommitCursorFixture {
    public record Fixture(Principal owner, UUID ticket, UUID intent, UUID purchase, UUID event, UUID extraIntent) {}
    public final CommitCursorExperiment experiment;
    public final RedisTicketLedger ledger;
    public final JdbcTemplate db;
    private final StockProjectionService projections;
    private final StockHoldService holds;
    private final PurchaseService purchases;
    private final CustomerService customers;
    private final CatalogService catalog;
    private final TransactionTemplate tx;
    private final String manager = "cursor-" + UUID.randomUUID();

    public CommitCursorFixture(ApplicationContext context) {
        db = context.getBean(JdbcTemplate.class);
        var redis = context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
        var json = context.getBean(ObjectMapper.class);
        var settings = context.getBean(AdmissionSettings.class);
        var transactions = context.getBean(PlatformTransactionManager.class);
        experiment = new CommitCursorExperiment(db, redis, json, settings.namespace(), transactions);
        ledger = RedisTicketLedger.epochs(redis, json, settings.namespace());
        projections = context.getBean(StockProjectionService.class);
        holds = context.getBean(StockHoldService.class);
        purchases = context.getBean(PurchaseService.class);
        customers = context.getBean(CustomerService.class);
        catalog = context.getBean(CatalogService.class);
        tx = new TransactionTemplate(transactions);
        experiment.createSchema();
        db.update("INSERT INTO staff_account VALUES(?,?,?,?,true)", UUID.randomUUID(), manager,
                "unused synthetic hash", "MANAGER");
    }

    private Principal customer() {
        var registered = customers.register("cc-" + UUID.randomUUID().toString().substring(0, 30),
                "隔離實驗", UUID.randomUUID().toString());
        return customers.authenticate(registered.token()).orElseThrow();
    }

    public Fixture accepted(boolean extraHold) {
        return accepted(extraHold, ticket -> {});
    }

    /** Additional test-only checkpoint setup before any intent. */
    public Fixture accepted(boolean extraHold, java.util.function.Consumer<UUID> seed) {
        var event = catalog.createEvent(manager, new EventInput(UUID.randomUUID(), 0L,
                "cursor-" + UUID.randomUUID(), "實驗演出", "不售票", "隔離"));
        var now = OffsetDateTime.now();
        var session = catalog.createSession(manager, event.id(), new SessionInput(UUID.randomUUID(), 0L,
                "合成", now.plusDays(2), now.minusHours(1), now.plusHours(2)));
        var ticket = catalog.createTicket(manager, session.id(), new TicketInput(UUID.randomUUID(), 0L,
                "一般", 1200, 4, 4));
        catalog.transition(manager, event.id(), new Transition(UUID.randomUUID(), 1L), true);
        var rebuild = projections.begin(ticket.id(), UUID.randomUUID(), 1);
        if (!"READY".equals(projections.complete(rebuild.requestId()).state())) throw new IllegalStateException("Not READY");
        experiment.seed(ticket.id());
        seed.accept(ticket.id());
        var owner = customer();
        var intent = holds.prepare(owner, new Place(UUID.randomUUID(), ticket.id(), 1, 1, 1200));
        if (!"HELD".equals(holds.apply(intent.id()).state())) throw new IllegalStateException("Not HELD");
        UUID extra = null;
        if (extraHold) {
            var another = holds.prepare(customer(), new Place(UUID.randomUUID(), ticket.id(), 1, 1, 1200));
            if (!"HELD".equals(holds.apply(another.id()).state())) throw new IllegalStateException("Extra not HELD");
            extra = another.id();
        }
        var purchase = purchases.acceptHold(owner, intent.id());
        var outbox = db.queryForObject("SELECT id FROM purchase_outbox WHERE purchase_id=?", UUID.class, purchase.id());
        return new Fixture(owner, ticket.id(), intent.id(), purchase.id(), outbox, extra);
    }

    public CommitCursorExperiment.Command processAndRegister(Fixture f, UUID request) {
        return tx.execute(status -> {
            long before = db.queryForObject("SELECT source_revision FROM stock_projection WHERE ticket_id=?",
                    Long.class, f.ticket());
            if (!purchases.process(f.event())) throw new IllegalStateException("Original worker did not process");
            return experiment.register(request, f.intent(), before);
        });
    }
}
