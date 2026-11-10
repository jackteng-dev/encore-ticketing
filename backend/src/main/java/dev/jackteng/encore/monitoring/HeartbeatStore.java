package dev.jackteng.encore.monitoring;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class HeartbeatStore {
 private final JdbcTemplate db;
 public HeartbeatStore(JdbcTemplate db){this.db=db;}
 @Transactional public void pulse(UUID boot,String role,boolean consumerRunning){
  db.update("INSERT INTO runtime_heartbeat(boot_id,role,consumer_running) VALUES(?,?,?) ON CONFLICT(boot_id) DO UPDATE SET last_seen=clock_timestamp(),consumer_running=EXCLUDED.consumer_running WHERE runtime_heartbeat.role=EXCLUDED.role",boot,role,consumerRunning);
  // At most100 obsolete operational rows per pulse; never delete business/audit data.
  db.update("DELETE FROM runtime_heartbeat WHERE boot_id IN (SELECT boot_id FROM runtime_heartbeat WHERE last_seen<clock_timestamp()-interval '24 hours' ORDER BY last_seen LIMIT 100)");
 }
}
