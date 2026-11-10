package dev.jackteng.encore.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.encore.TicketApplication;
import dev.jackteng.encore.delivery.ConfirmedSender;
import dev.jackteng.encore.delivery.DeliverySettings;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Offline restored-database operation. Replays original transport identities only;
 * never resets publication receipts, deadlines, commands or recovery budgets.
 * The Python restore tool verifies target receipt and stopped applications first.
 */
public final class PendingPurchaseReplay {
    public record Result(int requested, int confirmed, int skipped, int unknown) {}
    private final JdbcTemplate db;
    private final ConfirmedSender sender;
    private final DeliverySettings settings;
    private final ObjectMapper json;

    public PendingPurchaseReplay(JdbcTemplate db, ConfirmedSender sender, DeliverySettings settings, ObjectMapper json) {
        this.db = db;
        this.sender = sender;
        this.settings = settings;
        this.json = json;
    }

    public List<UUID> plan() {
        var events = db.query("SELECT o.id FROM purchase_outbox o JOIN purchase_request p ON p.id=o.purchase_id "
                + "JOIN outbox_publication d ON d.event_id=o.id WHERE p.status='PROCESSING' "
                + "AND p.deadline_at>clock_timestamp() AND d.published_at IS NOT NULL ORDER BY p.created_at,o.id LIMIT 101",
                (row, n) -> row.getObject(1, UUID.class));
        if (events.size() > 100) throw new IllegalStateException("Offline replay exceeds bounded100; use a reviewed batching procedure");
        return events;
    }

    public Result replay(List<UUID> events) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("No outer transaction during broker IO");
        if (events == null || events.size() > 100 || events.stream().anyMatch(java.util.Objects::isNull)
                || events.stream().distinct().count() != events.size()) throw new IllegalArgumentException("Bounded distinct original events required");
        if (!settings.enabled() || !"ticket_demo".equals(db.queryForObject("SELECT current_database()", String.class)))
            throw new IllegalStateException("Isolated ticket_demo and enabled delivery required");
        int confirmed = 0, skipped = 0, unknown = 0;
        for (UUID event : events) {
            var matches = db.query("SELECT p.id FROM purchase_outbox o JOIN purchase_request p ON p.id=o.purchase_id "
                    + "JOIN outbox_publication d ON d.event_id=o.id WHERE o.id=? AND o.event_type='PURCHASE_REQUESTED' "
                    + "AND o.schema_version=1 AND p.status='PROCESSING' AND p.deadline_at>clock_timestamp() "
                    + "AND d.published_at IS NOT NULL", (row, n) -> row.getObject(1, UUID.class), event);
            if (matches.isEmpty()) { skipped++; continue; }
            try {
                var props = new MessageProperties();
                props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                props.setContentEncoding("UTF-8");
                props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                props.setMessageId(event.toString());
                props.setHeader("ticket-retry", 0);
                var message = new Message(json.writeValueAsBytes(Map.of("eventId", event, "purchaseId", matches.getFirst(), "schemaVersion", 1)), props);
                if ("CONFIRMED".equals(sender.send(settings.exchange(), "purchase", message))) confirmed++;
                else unknown++;
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot encode original event", e);
            }
        }
        return new Result(events.size(), confirmed, skipped, unknown);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !"replay-pending".equals(args[0]) || !"true".equals(System.getenv("TICKET_OFFLINE_RECOVERY")))
            throw new IllegalArgumentException("Use the guarded restore tool on an offline restored target");
        try (var context = new SpringApplicationBuilder(TicketApplication.class).web(WebApplicationType.NONE).profiles("worker").run(
                "--spring.main.web-application-type=none", "--spring.flyway.enabled=false", "--ticket.demo-bootstrap=false",
                "--ticket.delivery.enabled=true", "--ticket.delivery.publisher-enabled=false", "--ticket.delivery.consumer-enabled=false",
                "--ticket.orders.enabled=false", "--ticket.purchases.enabled=false", "--ticket.pre-debit.enabled=false",
                "--ticket.expiry.enabled=false", "--ticket.stock-recovery.enabled=false", "--ticket.stock-return-recovery.enabled=false",
                "--ticket.stock-return-discovery.enabled=false", "--ticket.monitoring.enabled=false", "--ticket.admission.enabled=false")) {
            var operation = new PendingPurchaseReplay(context.getBean(JdbcTemplate.class), context.getBean(ConfirmedSender.class),
                    context.getBean(DeliverySettings.class), context.getBean(ObjectMapper.class));
            var result = operation.replay(operation.plan());
            System.out.println("RECOVERY_SUMMARY " + context.getBean(ObjectMapper.class).writeValueAsString(result));
            if (result.unknown() != 0) throw new IllegalStateException("Broker result unknown; retain original identities and reconcile");
        }
    }
}
