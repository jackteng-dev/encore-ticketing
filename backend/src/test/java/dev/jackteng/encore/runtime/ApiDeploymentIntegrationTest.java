package dev.jackteng.encore.runtime;

import dev.jackteng.encore.delivery.OutboxPublisher;
import dev.jackteng.encore.inventory.StockRecoveryWorker;
import dev.jackteng.encore.inventory.StockReturnRecoveryWorker;
import dev.jackteng.encore.inventory.StockReturnDiscoveryWorker;
import dev.jackteng.encore.orders.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"ticket.demo-bootstrap=false","ticket.orders.enabled=true","ticket.purchases.enabled=true","ticket.delivery.enabled=true","ticket.delivery.publisher-enabled=true","ticket.delivery.consumer-enabled=true","ticket.delivery.namespace=encore-api-role-test","ticket.stock-recovery.enabled=true","ticket.stock-return-recovery.enabled=true","ticket.stock-return-discovery.enabled=true","ticket.expiry.enabled=true","ticket.pre-debit.enabled=true"})
@ActiveProfiles("api")
@AutoConfigureMockMvc
class ApiDeploymentIntegrationTest {
 @Autowired ApplicationContext context;
 @Autowired MockMvc mvc;
 @Autowired PreDebitSettings preDebit;
 @Autowired SimpleMessageListenerContainer listener;
 @Test void apiNeverRegistersBackgroundJobsEvenWhenCapabilitiesEnabled() {
  for(var type:java.util.List.of(OutboxPublisher.class,OrderExpiry.class,PurchaseExpiry.class,StockRecoveryWorker.class,StockReturnRecoveryWorker.class,StockReturnDiscoveryWorker.class))assertTrue(context.getBeansOfType(type).isEmpty(),type.getSimpleName());
 }
 @Test void apiNeverStartsMqConsumption() {assertFalse(listener.isAutoStartup());assertFalse(listener.isRunning());}
 @Test void splitRoleDoesNotDisableConfiguredCheckoutCapability() {assertTrue(preDebit.enabled());}
 @Test void anonymousReadinessUsesRealPostgres()throws Exception {mvc.perform(get("/api/public/readiness")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.status").value("READY"));}
}
