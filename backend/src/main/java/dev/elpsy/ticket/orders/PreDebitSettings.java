package dev.elpsy.ticket.orders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/** Explicit opt-in; publishing without expiry and recovery must not open entry. */
@Component
public record PreDebitSettings(boolean enabled) {
 @org.springframework.beans.factory.annotation.Autowired
 public PreDebitSettings(@Value("${ticket.pre-debit.enabled:false}") boolean requested,
  @Value("${ticket.orders.enabled:false}") boolean orders,@Value("${ticket.purchases.enabled:false}") boolean purchases,
  @Value("${ticket.delivery.enabled:false}") boolean delivery,@Value("${ticket.delivery.publisher-enabled:false}") boolean publisher,
  @Value("${ticket.stock-recovery.enabled:false}") boolean recovery,@Value("${ticket.expiry.enabled:true}") boolean expiry){
  this(requested&&orders&&purchases&&delivery&&publisher&&recovery&&expiry);
 }
}
