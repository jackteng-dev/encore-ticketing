package dev.elpsy.ticket.inventory;

import dev.elpsy.ticket.common.ApiErrors.Problem;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal PG-backed recovery worker; no public mutation or auto-compensation. */
@Component
@org.springframework.context.annotation.Profile("!api")
public class StockRecoveryWorker {
 private final StockRecoveryStore store;private final StockHoldService holds;private final StockSettlementService settlement;
 private final boolean enabled;private final int seconds,batch;
 public StockRecoveryWorker(StockRecoveryStore store,StockHoldService holds,StockSettlementService settlement,
  @Value("${ticket.stock-recovery.enabled:false}") boolean enabled,
  @Value("${ticket.stock-recovery.lease-seconds:30}") int seconds,
  @Value("${ticket.stock-recovery.batch:10}") int batch){
  if(seconds<5||seconds>120||batch<1||batch>100)throw new IllegalArgumentException("Invalid bounded stock recovery settings");
  this.store=store;this.holds=holds;this.settlement=settlement;this.enabled=enabled;this.seconds=seconds;this.batch=batch;
 }
 /** Claimed task identity always supplies the durable intent; no wire claim. */
 public boolean run(StockRecoveryStore.Claim c){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Recovery requires independent committed phases");
  Runnable fence=()->store.fence(c);
  try{
   String failure;
   if(c.kind().equals("SETTLEMENT")){
    var r=settlement.reconcile(c.intentId(),fence);
    if(r.state().equals("ACKNOWLEDGED"))return store.complete(c,r.resolution());
    failure=r.failure();
   }else if(c.kind().equals("UNUSED")){
    var r=holds.recoverUnused(c.intentId(),fence);
    if(r.state().equals("ABORTED"))return store.complete(c,"ABORTED");
    if("ACCEPTED".equals(r.failure()))return store.complete(c,"ACCEPTED");
    failure=r.failure();
   }else throw new IllegalArgumentException("Unknown recovery job kind");
   // Only transient unknown IO or a still-live intent is retried. Corruption,
   // conflicting evidence and opposite terminal receipts go straight to review.
   return store.failed(c,failure,!"REDIS_UNKNOWN".equals(failure)&&!"NOT_DUE".equals(failure));
  }catch(StockRecoveryStore.LostLease e){return false;}
  catch(Problem e){return store.failed(c,"DOMAIN_CONFLICT",true);}
  catch(org.springframework.dao.DataAccessException e){return store.failed(c,"PG_FAILURE",false);}
  catch(RuntimeException e){return store.failed(c,"INTERNAL_FAILURE",false);}
 }
 @Scheduled(fixedDelayString="${ticket.stock-recovery.poll-ms:1000}",initialDelay=5000)
 public void sweep(){
  if(!enabled)return;
  try{for(int i=0;i<batch;i++){var c=store.claim(seconds);if(c.isEmpty())return;run(c.get());}}
  catch(RuntimeException e){LoggerFactory.getLogger(StockRecoveryWorker.class).warn("Stock recovery deferred; durable lease retained ({})",e.getClass().getSimpleName());}
 }
}
