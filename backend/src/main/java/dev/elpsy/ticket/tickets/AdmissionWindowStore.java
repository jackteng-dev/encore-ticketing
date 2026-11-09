package dev.elpsy.ticket.tickets;

import dev.elpsy.ticket.tickets.AdmissionWindowModels.*;
import dev.elpsy.ticket.common.ApiErrors.Problem;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Transaction-bound reads. No application clock or mutable policy head. */
final class AdmissionWindowStore {
 private AdmissionWindowStore() {}
 static void session(JdbcTemplate db,UUID id,boolean update) {
  if(db.queryForList("SELECT id FROM event_session WHERE id=? FOR "+(update?"UPDATE":"SHARE"),id).isEmpty())
   throw new Problem(404,"NOT_FOUND","找不到入場場次");
 }
 static Policy policy(JdbcTemplate db,UUID id) {
  var rows=db.query("SELECT * FROM ticket_window_policy WHERE session_id=? ORDER BY version DESC LIMIT 1",
   (r,n)->new Policy(id,r.getLong("version"),r.getObject("request_id",UUID.class),r.getObject("opens_at",OffsetDateTime.class),r.getObject("closes_at",OffsetDateTime.class),r.getObject("created_at",OffsetDateTime.class)),id);
  return rows.isEmpty()?null:rows.getFirst();
 }
 static Status status(JdbcTemplate db,UUID id) {
  session(db,id,false);
  var p=policy(db,id);
  var time=db.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class);
  String state=p==null?"UNCONFIGURED":time.isBefore(p.opensAt())?"NOT_OPEN":!time.isBefore(p.closesAt())?"CLOSED":"OPEN";
  return new Status(id,p==null?0:p.version(),p==null?null:p.requestId(),p==null?null:p.opensAt(),p==null?null:p.closesAt(),state,time);
 }
 static Proof proof(JdbcTemplate db,UUID request) {
  var rows=db.query("SELECT p.*,w.opens_at,w.closes_at FROM ticket_window_proof p JOIN ticket_window_policy w ON w.session_id=p.session_id AND w.version=p.policy_version WHERE p.request_id=?",
   (r,n)->new Proof(request,r.getObject("session_id",UUID.class),r.getLong("policy_version"),r.getObject("opens_at",OffsetDateTime.class),r.getObject("closes_at",OffsetDateTime.class),r.getObject("checked_at",OffsetDateTime.class)),request);
  return rows.isEmpty()?null:rows.getFirst();
 }
}
