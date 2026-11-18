#!/usr/bin/env python3
"""建立 Encore 展示資料（虛構活動、場館、演出者、海報、區域座位圖）。

只透過員工 API 寫入，遵守既有的原命令重送、版本與稽核規則；不直接寫資料庫。
- 原命令編號由 slug 與「錨定日期」推導：同一錨定日期重跑只會重送原命令，不會重複建立。
- 已存在相同 slug 的場館、演出者、活動會跳過。
- 預設只允許連線 127.0.0.1；對其他主機需明確加 --allow-remote。
- 員工帳密從 work/ 下的私密 env 讀取，不印出。

用法：
  python scripts/seed_showcase.py --env work/showcase-clean/runtime.env
  python scripts/seed_showcase.py --env ... --anchor 2026-11-18 --base http://127.0.0.1:15180
"""
from __future__ import annotations
import argparse, datetime as dt, http.cookiejar, json, sys, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'scripts' / 'showcase' / 'showcase-data.json'
NAMESPACE = uuid.UUID('5f2b6a64-1c1e-4c43-9a6e-3c4b8d7e0a11')
TAIPEI = dt.timezone(dt.timedelta(hours=8))


def rid(*parts: str) -> str:
    return str(uuid.uuid5(NAMESPACE, ':'.join(parts)))


class Api:
    def __init__(self, base: str):
        self.base = base.rstrip('/')
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def _send(self, method: str, path: str, body=None, form=False):
        headers = {}
        data = None
        if method != 'GET':
            token = self._send('GET', '/api/auth/csrf')
            headers[token['headerName']] = token['token']
            if form:
                data = urllib.parse.urlencode(body).encode()
                headers['Content-Type'] = 'application/x-www-form-urlencoded'
            else:
                data = json.dumps(body, ensure_ascii=False).encode()
                headers['Content-Type'] = 'application/json'
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with self.opener.open(req, timeout=30) as r:
                raw = r.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            detail = e.read().decode('utf-8', 'replace')[:300]
            raise SystemExit(f'{method} {path} -> {e.code} {detail}') from None

    def get(self, path):
        return self._send('GET', path)

    def post(self, path, body):
        return self._send('POST', path, body)

    def put(self, path, body):
        return self._send('PUT', path, body)

    def exists(self, path) -> bool:
        try:
            with self.opener.open(urllib.request.Request(self.base + path), timeout=30):
                return True
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return False
            raise


def read_env(path: Path) -> dict:
    data = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.strip().rstrip('\r')
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            data[k] = v
    return data


def iso(t: dt.datetime) -> str:
    return t.isoformat()


def session_times(anchor: dt.date, s: dict):
    """由錨定日期推導固定時間：同一錨定日期重跑時請求內容完全相同。"""
    start_day = anchor + dt.timedelta(days=s['startsInDays'])
    starts = dt.datetime.combine(start_day, dt.time(s['startHour'], 0), TAIPEI)
    if s['salesOpen']:
        sales_start = dt.datetime.combine(anchor - dt.timedelta(days=1), dt.time(12, 0), TAIPEI)
    else:
        sales_start = dt.datetime.combine(anchor + dt.timedelta(days=max(2, s['startsInDays'] // 3)), dt.time(12, 0), TAIPEI)
    sales_end = starts - dt.timedelta(hours=2)
    return starts, sales_start, sales_end


def seat_zones(tickets: list[dict]) -> list[dict]:
    """票價高者靠近舞台；3 區以內每排一區，之後每排 2–3 區（與後台「自動排列」一致）。"""
    chosen = sorted(tickets, key=lambda t: -t['priceTwd'])
    per_row = 1 if len(chosen) <= 3 else 2 if len(chosen) <= 6 else 3
    lines = -(-len(chosen) // per_row)
    h = min(30, 90 // lines)
    zones = []
    for i, t in enumerate(chosen):
        row, col = divmod(i, per_row)
        in_row = min(per_row, len(chosen) - row * per_row)
        w = 96 // in_row
        zones.append({'ticketTypeId': t['id'], 'label': t['name'][:40], 'x': 2 + col * w, 'y': 5 + row * h, 'w': w - 2, 'h': h - 3})
    return zones


def admin_slugs(api: Api) -> dict:
    found, page = {}, 1
    while page <= 500:
        p = api.get(f'/api/admin/events?page={page}&size=20')
        for e in p['items']:
            found[e['slug']] = e
        if page * p['size'] >= p['total']:
            break
        page += 1
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--env', required=True, help='work/ 下含 TICKET_STAFF_USERNAME／PASSWORD 的私密 env')
    ap.add_argument('--base', default='http://127.0.0.1:15180')
    ap.add_argument('--anchor', default=dt.datetime.now(TAIPEI).date().isoformat(), help='錨定日期（台北），決定場次與開賣時間')
    ap.add_argument('--allow-remote', action='store_true')
    args = ap.parse_args()

    host = urllib.parse.urlparse(args.base).hostname
    if host not in ('127.0.0.1', 'localhost') and not args.allow_remote:
        raise SystemExit('只允許本機；對其他主機請明確加 --allow-remote')
    env_path = (ROOT / args.env).resolve()
    if not env_path.is_relative_to((ROOT / 'work').resolve()):
        raise SystemExit('私密 env 必須位於 repository 的 work/ 內')
    env = read_env(env_path)
    anchor = dt.date.fromisoformat(args.anchor)
    data = json.loads(DATA.read_text(encoding='utf-8'))
    api = Api(args.base)
    api._send('POST', '/api/auth/login', {'username': env['TICKET_STAFF_USERNAME'], 'password': env['TICKET_STAFF_PASSWORD']}, form=True)
    tag = anchor.isoformat()

    categories = {c['slug']: c['id'] for c in api.get('/api/admin/categories')}
    venues, performers = {}, {}
    for v in data['venues']:
        if api.exists(f"/api/public/venues/{v['slug']}"):
            venues[v['slug']] = api.get(f"/api/public/venues/{v['slug']}")
            print('venue exists   ', v['slug'])
            continue
        venues[v['slug']] = api.post('/api/admin/venues', {
            'requestId': rid('venue', v['slug']), 'expectedVersion': 0, 'slug': v['slug'], 'name': v['name'], 'city': v['city'],
            'address': v['address'], 'description': v['description'], 'imageUrl': f"/demo/venue-{v['slug']}.webp"})
        print('venue created  ', v['slug'])
    for p in data['performers']:
        if api.exists(f"/api/public/performers/{p['slug']}"):
            performers[p['slug']] = api.get(f"/api/public/performers/{p['slug']}")
            print('performer exists', p['slug'])
            continue
        performers[p['slug']] = api.post('/api/admin/performers', {
            'requestId': rid('performer', p['slug']), 'expectedVersion': 0, 'slug': p['slug'], 'name': p['name'],
            'description': p['description'], 'imageUrl': f"/demo/performer-{p['slug']}.webp"})
        print('performer created', p['slug'])

    existing = admin_slugs(api)
    # 首頁以最新建立的活動為焦點，因此倒序建立：資料檔第一個活動會成為首頁焦點。
    for e in reversed(data['events']):
        if e['slug'] in existing and existing[e['slug']]['status'] != 'DRAFT':
            print('event exists   ', e['slug'], existing[e['slug']]['status'])
            continue
        venue = venues[e['venueSlug']]
        ev = api.post('/api/admin/events', {
            'requestId': rid('event', tag, e['slug']), 'expectedVersion': 0, 'slug': e['slug'], 'title': e['title'],
            'description': e['description'], 'venue': f"{venue['name']}（{venue['city']}）"})
        made_sessions = []
        for i, s in enumerate(e['sessions']):
            starts, sales_start, sales_end = session_times(anchor, s)
            sess = api.post(f"/api/admin/events/{ev['id']}/sessions", {
                'requestId': rid('session', tag, e['slug'], str(i)), 'expectedVersion': 0, 'name': s['name'],
                'startsAt': iso(starts), 'salesStart': iso(sales_start), 'salesEnd': iso(sales_end)})
            tickets = []
            for j, t in enumerate(e['tickets']):
                tickets.append(api.post(f"/api/admin/sessions/{sess['id']}/tickets", {
                    'requestId': rid('ticket', tag, e['slug'], str(i), str(j)), 'expectedVersion': 0, 'name': t['name'],
                    'priceTwd': t['priceTwd'], 'capacity': t['capacity'], 'maxPerCustomer': t['maxPerCustomer']}))
            made_sessions.append((sess, tickets))
        api.put(f"/api/admin/events/{ev['id']}/profile", {
            'requestId': rid('profile', tag, e['slug']), 'expectedVersion': 0, 'categoryId': categories[e['category']],
            'venueId': venue['id'], 'performerIds': [performers[x]['id'] for x in e['performerSlugs']],
            'imageUrl': f"/demo/event-{e['slug']}.webp"})
        for i, (sess, tickets) in enumerate(made_sessions):
            api.put(f"/api/admin/sessions/{sess['id']}/seat-map", {
                'requestId': rid('seatmap', tag, e['slug'], str(i)), 'expectedVersion': 0, 'stageLabel': '舞台' if e['category'] != 'sports' else '球場',
                'zones': seat_zones([{**t, 'priceTwd': t['priceTwd']} for t in tickets])})
        # 活動檔案與座位圖都完成後才發布；中途中斷時活動仍是草稿，同一錨定日期重跑會以原命令補完。
        api.post(f"/api/admin/events/{ev['id']}/publish", {'requestId': rid('publish', tag, e['slug']), 'expectedVersion': 1})
        print('event created  ', e['slug'], f'{len(made_sessions)} 場')
    print('完成。')


if __name__ == '__main__':
    sys.exit(main())
