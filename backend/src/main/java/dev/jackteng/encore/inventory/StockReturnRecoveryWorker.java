package dev.jackteng.encore.inventory;
import dev.jackteng.encore.common.ApiErrors.Problem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.LoggerFactory;
@Component
@org.springframework.context.annotation.Profile("!api")
public class StockReturnRecoveryWorker {
 private final StockReturnRecoveryStore store;private final StockReturnStaffService staff;private final boolean enabled;private final int seconds,batch;
 public StockReturnRecoveryWorker(StockReturnRecoveryStore store,StockReturnStaffService staff,
  @Value("${ticket.stock-return-recovery.enabled:false}")boolean enabled,
  @Value("${ticket.stock-return-recovery.lease-seconds:30}")int seconds,
  @Value("${ticket.stock-return-recovery.batch:4}")int batch){if(seconds<5||seconds>120||batch<1||batch>20)throw new IllegalArgumentException("Bounded return recovery settings required");this.store=store;this.staff=staff;this.enabled=enabled;this.seconds=seconds;this.batch=batch;}
 public boolean run(StockReturnRecoveryStore.Claim c){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Independent return phases required");
  try{var r=staff.submit(c.actor(),c.ticketId(),c.orderId(),new StockReturnStaffService.Begin(c.requestId(),c.releaseRevision()),()->store.fence(c));
   return r.state().equals("APPLIED")?store.complete(c):store.failed(c,r.state().equals("BLOCKED")?"RETURN_BLOCKED":r.canResume()?"REDIS_UNKNOWN":"RETURN_DISABLED",r.state().equals("BLOCKED")||!r.canResume());
  }catch(StockReturnRecoveryStore.LostLease e){return false;}
  catch(Problem e){return store.failed(c,e.status==403?"ACTOR_FORBIDDEN":"DOMAIN_CONFLICT",true);}
  catch(org.springframework.dao.DataAccessException e){return store.failed(c,"PG_FAILURE",false);}
  catch(RuntimeException e){return store.failed(c,"INTERNAL_FAILURE",false);}
 }
 @Scheduled(fixedDelayString="${ticket.stock-return-recovery.poll-ms:1000}",initialDelayString="${ticket.stock-return-recovery.initial-delay-ms:5000}")
 public void sweep(){if(!enabled)return;try{for(int i=0;i<batch;i++){var c=store.claim(seconds);if(c.isEmpty())return;run(c.get());}}catch(RuntimeException e){LoggerFactory.getLogger(StockReturnRecoveryWorker.class).warn("Return recovery deferred; durable lease retained ({})",e.getClass().getSimpleName());}}
}
