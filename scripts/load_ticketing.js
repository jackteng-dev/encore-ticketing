// Bounded local-only workload. Credentials and detailed outputs remain in private work/.
import http from 'k6/http';
import { check, sleep } from 'k6';
import execution from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const fixture = JSON.parse(open(__ENV.TICKET_LOAD_FIXTURE));
if (!/^http:\/\/127\.0\.0\.1:\d+\/api$/.test(fixture.base)) throw new Error('Loopback only');
if (!fixture.bases?.every(base => /^http:\/\/127\.0\.0\.1:\d+\/api$/.test(base)) || fixture.bases.length !== 2) throw new Error('Two local APIs required');
const mode = __ENV.TICKET_LOAD_MODE;
if (!['read', 'sync', 'async', 'predebit', 'waiting'].includes(mode)) throw new Error('Unknown mode');
const buyers = fixture.actors.length;
const vus = Number(__ENV.TICKET_LOAD_VUS || 8);
if (buyers < 1 || buyers > 100 || vus < 1 || vus > 16) throw new Error('Bound exceeded');
export const options = {
  scenarios: { bounded: mode === 'read'
    ? { executor: 'constant-arrival-rate', rate: 20, timeUnit: '1s', duration: '10s', preAllocatedVUs: vus, maxVUs: 16 }
    : { executor: 'shared-iterations', vus, iterations: buyers, maxDuration: '40s' } },
  // k6 constant-arrival-rate may schedule both t=0 and the final boundary:200 or201.
  // No dropped arrivals are permitted. Transactional cases remain exact-count.
  thresholds: { checks: ['rate==1'], iterations: mode === 'read' ? ['count>=200', 'count<=201'] : [`count==${buyers}`],
    ...(mode === 'read' ? { dropped_iterations: ['count==0'] } : {}) },
  discardResponseBodies: false,
  maxRedirects: 0,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};
const outcomes = new Counter('business_outcomes');
const statuses = new Counter('response_statuses');
const primary = new Trend('primary_ms', true);
const replay = new Trend('replay_ms', true);
const endToEnd = new Trend('reservation_observed_ms', true);
const polls = new Counter('result_polls');
const acceptedSubmissions = new Counter('submit_accepted');
const rejectedSubmissions = new Counter('submit_rejected');
const status200 = new Counter('http_200');
const status202 = new Counter('http_202');
const status409 = new Counter('http_409');
const statusOther = new Counter('http_other');
http.setResponseCallback(http.expectedStatuses(200, 202, 409, 429, 503));

function call(actor, method, path, body, kind) {
  const base = fixture.bases[(execution.scenario.iterationInTest + (kind.includes('replay') ? 1 : 0)) % 2];
  const params = { headers: { 'Content-Type': 'application/json' }, tags: { name: kind }, timeout: '10s' };
  if (actor) {
    http.cookieJar().set(base + '/customer', 'TICKET_CUSTOMER_SESSION', actor.token, { path: '/api/customer' });
    http.cookieJar().set(base + '/customer', actor.csrfCookie.name, actor.csrfCookie.value, { path: actor.csrfCookie.path });
    params.headers[actor.csrf.headerName] = actor.csrf.token;
  }
  const response = http.request(method, base + path, body ? JSON.stringify(body) : null, params);
  statuses.add(1, { kind, status: String(response.status) });
  if (response.status === 200) status200.add(1);
  else if (response.status === 202) status202.add(1);
  else if (response.status === 409) status409.add(1);
  else statusOther.add(1);
  return response;
}
function parsed(response) { try { return response.json(); } catch (_) { return {}; } }
function valid(ok, name) { return check(ok, { [name]: value => value === true }); }
function purchase(value) { return mode === 'predebit' ? value.purchase : value; }

export default function () {
  const index = execution.scenario.iterationInTest;
  const actor = fixture.actors[index];
  const ticket = fixture.cases[mode];
  if (mode === 'read') {
    const r = call(null, 'GET', '/public/events/' + ticket.eventId, null, 'public_detail');
    primary.add(r.timings.duration);
    valid(r.status === 200 && parsed(r).event?.id === ticket.eventId, 'public detail is real');
    return;
  }
  if (mode === 'waiting') {
    const body = { requestId: actor.commands.waiting };
    const path = '/customer/waiting-room/' + ticket.id + '/join';
    const a = call(actor, 'POST', path, body, 'join');
    primary.add(a.timings.duration);
    const state = parsed(a);
    valid(a.status === 200 && ['ADMITTED', 'WAITING'].includes(state.state), 'bounded room accepts identity');
    outcomes.add(1, { state: state.state || 'UNKNOWN' });
    const b = call(actor, 'POST', path, body, 'join_replay');
    replay.add(b.timings.duration);
    valid(b.status === 200 && parsed(b).requestId === body.requestId, 'queue original identity preserved');
    return;
  }
  const body = { requestId: actor.commands[mode], ticketId: ticket.id, quantity: 1,
    expectedTicketVersion: ticket.version, expectedTotalTwd: ticket.priceTwd };
  const path = mode === 'sync' ? '/customer/orders' : mode === 'async' ? '/customer/purchases' : '/customer/purchase-attempts';
  const started = Date.now();
  const first = call(actor, 'POST', path, body, 'submit');
  primary.add(first.timings.duration);
  const value = parsed(first);
  const expectedRejection = first.status === 409 && ['SOLD_OUT', 'STOCK_GATE_CLOSED', 'STOCK_CHANGED', 'STOCK_NOT_READY'].includes(value.code);
  const accepted = [200, 202].includes(first.status) && value.requestId === body.requestId;
  valid(accepted || expectedRejection, 'submit has explicit business outcome');
  if (accepted) acceptedSubmissions.add(1);
  else rejectedSubmissions.add(1);
  outcomes.add(1, { state: accepted ? (value.status || value.state) : (value.code || 'UNEXPECTED') });
  const again = call(actor, 'POST', path, body, 'original_replay');
  replay.add(again.timings.duration);
  const replayed = parsed(again);
  if (accepted) valid([200, 202].includes(again.status) && replayed.requestId === body.requestId &&
    (mode === 'predebit' || replayed.id === value.id), 'replay keeps original identity');
  else valid(again.status === 409 && parsed(again).code === value.code, 'confirmed rejection stays rejected');
  if (!accepted) return;
  if (mode === 'sync') {
    valid(first.status === 200 && value.status === 'PENDING_PAYMENT', 'sync acknowledgement is a held order');
    endToEnd.add(Date.now() - started);
    return;
  }
  let current = purchase(replayed);
  let observed = false;
  for (let attempt = 0; attempt < 24; attempt++) {
    if (current && current.status !== 'PROCESSING') { observed = true; break; }
    // A pending pre-debit intent is not a durable purchase or a held order.
    if (!current && ['ABORTED', 'REVIEW'].includes(replayed.state)) break;
    sleep(0.25);
    const target = mode === 'predebit' ? path + '/' + body.requestId : path + '/' + value.id;
    const result = call(actor, 'GET', target, null, 'result_poll');
    polls.add(1);
    valid(result.status === 200 && parsed(result).requestId === body.requestId, 'result belongs to original request');
    current = purchase(parsed(result));
  }
  outcomes.add(1, { state: observed ? current.status : 'NOT_TERMINAL_IN_OBSERVATION_WINDOW' });
  if (observed && current.status === 'RESERVED') endToEnd.add(Date.now() - started);
  // Final PostgreSQL drain/reconciliation is done separately by the orchestrator.
}

export function handleSummary(data) {
  return { [__ENV.TICKET_LOAD_SUMMARY]: JSON.stringify(data, null, 2) + '\n' };
}
