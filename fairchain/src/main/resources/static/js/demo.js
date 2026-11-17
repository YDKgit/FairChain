// FairChain 시연 화면 (demo.html) — 이 화면이 예제서버 역할을 한다
// ---------- 해시값 계산 (fairchain-api Sha256RecordHasher와 같은 방식: 필드마다 "길이:값") ----------
async function hash(...fields) {
  const text = fields.map(f => f == null ? '-1:' : String(f).length + ':' + f).join('');
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, '0')).join('');
}
// 좌석 키 = hash(고객사 구분값, 좌석 번호). 원래는 예제서버가 계산한다
const recordKey = (eventId, seat) => hash('demo-' + eventId, String(seat));
const ticketHash = (eventId, seat, userId, type) =>
  type === 'ISSUE' ? hash(String(eventId), String(seat), 'ISSUE')
                   : hash(String(eventId), String(seat), String(userId), type);

// ---------- 상태 ----------
const MAX_SEATS = 100;
let keyToSeat = {};      // recordKey → 좌석 번호
let records = [];        // 서버의 좌석별 최신 기록
let selectedSeat = null; // 화면에서 누른 좌석
const TYPE_LABEL = { ISSUE: '판매 전', PURCHASE: '예매됨', CANCEL: '취소됨', CHECKIN: '입장함' };
// 시연용 회원 이름 (예제서버 회원 DB 역할). 해시값 계산에는 회원 번호를 쓴다
const NAMES = { 100: '김철수', 200: '이영희', 300: '박민수', 400: '최지우', 500: '정하늘' };
const who = u => NAMES[u] || '회원 ' + u;
const seatName = s => 'A열 ' + s + '번';
const EVENT_NAME = 'FairChain 콘서트 2026';
const $ = id => document.getElementById(id);
const eventId = () => Number($('eventId').value);

try { $('apiKey').value = localStorage.getItem('fc-demo-key') || ''; } catch (e) {}
$('apiKey').onchange = () => { try { localStorage.setItem('fc-demo-key', $('apiKey').value); } catch (e) {} };
$('eventId').onchange = buildSeatKeys;
$('buyUser').innerHTML = Object.entries(NAMES).map(([id, n]) => '<option value="' + id + '">' + n + ' (회원 ' + id + ')</option>').join('');

async function api(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json', 'X-API-KEY': $('apiKey').value.trim() },
    body: body ? JSON.stringify(body) : undefined
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const err = new Error(res.status + ' ' + (data && (data.message || data.error) || text));
    err.status = res.status;
    throw err;
  }
  return data;
}

// 예제서버 → FairChain 흐름 표시
function showFlow(ticket, path, sent, response) {
  const boxes = $('flow').querySelectorAll('pre');
  boxes[0].textContent = ticket;
  boxes[1].textContent = 'POST ' + path + '\n헤더 X-API-KEY: (고객사 키)\n' + JSON.stringify(sent, null, 2);
  boxes[2].textContent = response;
}
const short = h => h.slice(0, 12) + '…';

function show(id, text, err) { $(id).textContent = text; $(id).className = 'msg' + (err ? ' err' : ''); }
function showBuy(text, err) { show('buyMsg', text, err); }
const STATUS_TEXT = { 200: 'OK', 400: 'Bad Request', 401: 'Unauthorized', 403: 'Forbidden', 404: 'Not Found', 409: 'Conflict', 429: 'Too Many Requests' };
function showCode(status) {
  $('buyCode').className = 'code ' + (status === 200 ? 'ok' : 'err');
  $('buyCode').innerHTML = '<b>' + (status || '?') + '</b>' + (STATUS_TEXT[status] || '응답 없음');
}
const REJECTED = '\n\nFairChain이 좌석 기록 순서를 검사해서 거절\n(블록에는 아무것도 기록되지 않음)';
const seatRecord = seat => records.find(r => keyToSeat[r.recordKey] === seat);

async function buildSeatKeys() {
  keyToSeat = {};
  const ev = eventId();
  for (let s = 1; s <= MAX_SEATS; s++) keyToSeat[await recordKey(ev, s)] = s;
  refresh();
}

// ---------- ① 좌석 발행 ----------
async function issueSeats() {
  const ev = eventId();
  const start = Math.max(0, ...records.map(r => keyToSeat[r.recordKey] || 0)) + 1;
  const count = Number($('issueCount').value);
  if (start + count - 1 > MAX_SEATS) return show('issueMsg', '시연 페이지는 좌석 ' + MAX_SEATS + '석까지만 지원해요', true);
  const list = [];
  for (let s = start; s < start + count; s++) {
    list.push({ type: 'ISSUE', eventId: ev, userId: null, slotKey: 'A-' + s,
                recordKey: await recordKey(ev, s), version: 1, recordHash: await ticketHash(ev, s, null, 'ISSUE') });
  }
  const ticketText = '공연 ' + ev + ' · 좌석 ' + start + '번 ~ ' + (start + count - 1) + '번 (주인 없음)\n\n' +
    '좌석 키 = SHA-256(고객사, 좌석번호)\n해시값 = SHA-256(공연, 좌석, "ISSUE")\n\n예) ' + start + '번 좌석\n키 ' + short(list[0].recordKey) + '\n해시값 ' + short(list[0].recordHash);
  const sent = { eventId: ev, records: [{ type: 'ISSUE', recordKey: short(list[0].recordKey), version: 1,
                 recordHash: short(list[0].recordHash) }, '… 좌석 ' + count + '개'] };
  try {
    await api('POST', '/api/admin/seats/issue', { eventId: ev, records: list });
    showFlow(ticketText, '/api/admin/seats/issue', sent, '200 OK\n\n기록 저장(PENDING) 후 바로 응답\n블록 기록은 대기 줄에서 노드로 →\n리더 층 노드 과반(2대 이상)이 저장하면 CONFIRMED');
    show('issueMsg', start + '번 ~ ' + (start + count - 1) + '번 좌석 발행 요청 완료 → 블록 기록 대기 중');
  } catch (e) { showFlow(ticketText, '/api/admin/seats/issue', sent, e.message); show('issueMsg', e.message, true); }
  refresh();
}

// ---------- ② 예매 ----------
async function purchase(seat, user) {
  const ev = eventId();
  const rec = seatRecord(seat);
  if (!rec) return showBuy('발행되지 않은 좌석이에요', true);
  const body = { type: 'PURCHASE', eventId: ev, userId: user, slotKey: 'A-' + seat,
                 recordKey: rec.recordKey, version: rec.version + 1, recordHash: await ticketHash(ev, seat, user, 'PURCHASE') };
  const ticketText = '🎫 티켓\n공연 ' + ev + ' · ' + seatName(seat) + ' · ' + who(user) + ' (회원 ' + user + ')\n(이 원본은 예제서버 DB에 보관)\n\n' +
    '해시값 = SHA-256(공연, 좌석, 회원, "PURCHASE")\n= ' + short(body.recordHash);
  const sent = { type: 'PURCHASE', eventId: ev, userId: user, recordKey: short(body.recordKey),
                 version: body.version, recordHash: short(body.recordHash) };
  try {
    const res = await api('POST', '/api/records', body);
    showFlow(ticketText, '/api/records', sent, '200 OK  ' + JSON.stringify(res) + '\n\n버전 ' + body.version + ' 기록 저장(PENDING)\n→ 리더 층 노드 과반(2대 이상) 저장 → CONFIRMED');
    showBuy(seatName(seat) + ' → ' + who(user) + ' 예매 완료' + (res.soldOut ? ' (매진)' : ''));
    showCode(200);
  } catch (e) { showFlow(ticketText, '/api/records', sent, e.message + (e.status === 409 ? REJECTED : '')); showBuy(e.message, true); showCode(e.status); }
  refresh();
}

// ---------- ② 예매 취소 ----------
async function cancel(seat) {
  const ev = eventId();
  const rec = seatRecord(seat);
  if (!rec) return showBuy('발행되지 않은 좌석이에요', true);
  const user = rec.userId ?? Number($('buyUser').value);   // 판매 전 좌석이면 고른 예매자 이름으로 보내 본다
  const body = { type: 'CANCEL', eventId: ev, userId: user, slotKey: 'A-' + seat,
                 recordKey: rec.recordKey, version: rec.version + 1, recordHash: await ticketHash(ev, seat, user, 'CANCEL') };
  const ticketText = '🎫 취소할 티켓\n공연 ' + ev + ' · ' + seatName(seat) + ' · ' + who(user) + ' (회원 ' + user + ')\n\n' +
    '해시값 = SHA-256(공연, 좌석, 회원, "CANCEL")\n= ' + short(body.recordHash);
  const sent = { type: 'CANCEL', eventId: ev, userId: user, recordKey: short(body.recordKey),
                 version: body.version, recordHash: short(body.recordHash) };
  try {
    const res = await api('POST', '/api/records', body);
    showFlow(ticketText, '/api/records', sent, '200 OK  ' + JSON.stringify(res) + '\n\n버전 ' + body.version +
      ' 취소 기록 저장(PENDING)\n→ 리더 층 노드 과반(2대 이상) 저장 → CONFIRMED\n이전 예매 기록(v' + rec.version + ')은 블록에 그대로 남음');
    showBuy(seatName(seat) + ' ' + who(user) + ' 예매 취소 완료 → 다시 판매 가능');
    showCode(200);
  } catch (e) { showFlow(ticketText, '/api/records', sent, e.message + (e.status === 409 ? REJECTED : '')); showBuy(e.message, true); showCode(e.status); }
  refresh();
}

// ---------- 좌석 선택 → ②, ④ 칸에 그 좌석 채우기 ----------
function selectSeat(seat) {
  selectedSeat = seat;
  const rec = seatRecord(seat);
  if (rec) { $('buySeat').value = seat; $('cancelSeat').value = seat; }
  if (rec && rec.type !== 'ISSUE') {
    $('ticketSeat').value = seat;
    prepareTicket(defaultKind());
  }
  renderSeats();
}

// ---------- ④⑤ 체크인 ----------
// 시나리오: normal 정상 / seat 좌석 번호 위조 / owner 예매자 위조 / old 취소된 예전 티켓
const SCENARIO = {
  normal: '예매한 그대로의 정상 티켓',
  seat: '누군가 좌석 번호만 앞자리로 고친 티켓',
  owner: '예매자를 다른 사람으로 고친 티켓 (양도 · 되팔기 위조)',
  old: '예매했다가 취소한 티켓을 그대로 들고 옴',
  used: '이미 입장한 티켓으로 한 번 더 들어가려 함'
};
const RESULT_HINT = '<p class="msg">티켓 아래 [이 티켓으로 체크인]을 누르면 여기에 결과가 나와요</p>';
let ticket = null;  // 화면에 보이는 티켓 {recordKey, seat, user, version, kind, origSeat, origUser, baseVersion}

const defaultKind = () => {
  const rec = seatRecord(Number($('ticketSeat').value));
  return rec && rec.type === 'CANCEL' ? 'old' : rec && rec.type === 'CHECKIN' ? 'used' : 'normal';
};

// VERIFIED면 예제서버가 입장 처리 → CHECKIN 기록 (같은 티켓으로는 다시 못 들어옴)
async function recordCheckin(t, rec) {
  const ev = eventId();
  const body = { type: 'CHECKIN', eventId: ev, userId: t.user, slotKey: 'A-' + t.seat,
                 recordKey: rec.recordKey, version: rec.version + 1, recordHash: await ticketHash(ev, t.seat, t.user, 'CHECKIN') };
  try {
    await api('POST', '/api/records', body);
    // 기록이 바뀌어도 새로고침이 티켓 · 결과를 다시 그리지 않게 (결과를 화면에 남겨 둔다)
    t.baseVersion = body.version;
    $('checkinResult').insertAdjacentHTML('beforeend', '<p class="msg">입장 처리 → CHECKIN 기록 v' + body.version +
      ' 저장. 이제 이 좌석은 "입장함"이고, 같은 티켓으로는 다시 들어올 수 없어요</p>');
  } catch (e) {
    $('checkinResult').insertAdjacentHTML('beforeend', '<p class="msg err">입장 기록 실패: ' + e.message + '</p>');
  }
  refresh();
}

function prepareTicket(kind) {
  const want = { old: 'CANCEL', used: 'CHECKIN' }[kind] || 'PURCHASE';
  let rec = seatRecord(Number($('ticketSeat').value));
  if (!rec || rec.type !== want) {
    rec = records.filter(r => r.type === want).pop();   // 고른 좌석이 시나리오와 안 맞으면 맞는 좌석으로 바꾼다
    if (!rec) {
      ticket = null;
      $('ticketCard').innerHTML = '<p class="msg err">' + ({
        old: '취소된 좌석이 없어요. ②에서 좌석 하나를 예매 취소해 주세요',
        used: '입장한 좌석이 없어요. 정상 티켓으로 먼저 체크인해 주세요'
      }[kind] || '예매된 좌석이 없어요. ②에서 먼저 예매해 주세요') + '</p>';
      $('checkinResult').innerHTML = '';
      return false;
    }
    $('ticketSeat').value = keyToSeat[rec.recordKey];
  }
  const seat = keyToSeat[rec.recordKey];
  ticket = { recordKey: rec.recordKey, seat, user: rec.userId, version: rec.version, kind,
             origSeat: seat, origUser: rec.userId, baseVersion: rec.version };
  if (kind === 'seat') ticket.seat = seat === 1 ? 2 : 1;
  if (kind === 'owner') ticket.user = rec.userId === 100 ? 200 : 100;
  if (kind === 'old' || kind === 'used') ticket.version = rec.version - 1;   // 취소 · 입장 바로 전의 예매 기록
  renderTicket();
  $('checkinResult').innerHTML = RESULT_HINT;
  return true;
}

function renderTicket() {
  const t = ticket;
  const seatChg = t.seat !== t.origSeat, userChg = t.user !== t.origUser, old = t.kind === 'old' || t.kind === 'used';
  $('ticketCard').innerHTML =
    '<div class="tk"><div class="tk-main"><div class="tk-title">🎫 ' + EVENT_NAME + '</div>' +
    '<div class="tk-row"><span>공연</span><b>#' + eventId() + '</b></div>' +
    '<div class="tk-row"><span>좌석</span><b' + (seatChg ? ' class="chg"' : '') + '>' + seatName(t.seat) + '</b>' +
      (seatChg ? '<small class="chg">' + seatName(t.origSeat) + '</small>' : '') + '</div>' +
    '<div class="tk-row"><span>예매자</span><b' + (userChg ? ' class="chg"' : '') + '>' + who(t.user) + '</b>' +
      (userChg ? '<small class="chg">' + who(t.origUser) + '</small>' : '<small>회원 ' + t.user + '</small>') + '</div>' +
    '</div><div class="tk-stub">티켓 버전<b' + (old ? ' class="chg"' : '') + '>v' + t.version + '</b>' + (old ? (t.kind === 'used' ? '입장 전' : '취소 전') : '') + '</div></div>' +
    '<p class="scenario">' + (t.kind === 'normal' ? '' : '⚠️ ') + SCENARIO[t.kind] + '</p>' +
    '<button class="checkin-btn" onclick="checkin()">이 티켓으로 체크인</button>';
}

async function checkin() {
  const t = ticket;
  const rec = t && records.find(r => r.recordKey === t.recordKey);
  if (!rec) return;
  // 해시값은 화면의 티켓 내용으로 다시 계산 → 내용이 바뀌었으면 해시값이 달라진다
  const recordHash = await ticketHash(eventId(), t.seat, t.user, 'PURCHASE');
  const ticketText = '🎫 입장하려는 티켓 (' + SCENARIO[t.kind] + ')\n공연 ' + eventId() + ' · ' + seatName(t.seat) + ' · ' +
    who(t.user) + ' (회원 ' + t.user + ') · v' + t.version + '\n\n해시값을 티켓 내용으로 다시 계산\n= ' + short(recordHash);
  const sent = { recordKey: short(t.recordKey), version: t.version, recordHash: short(recordHash) };
  try {
    const res = await api('POST', '/api/checkin/verify', { recordKey: t.recordKey, version: t.version, recordHash });
    showFlow(ticketText, '/api/checkin/verify', sent, res.status + (res.reason ? '\n' + res.reason : '') +
      '\n\nFairChain이 노드 3대의 블록 해시값과 비교\n(2대 이상 같아야 VERIFIED)');
    const label = { VERIFIED: '✅ 입장 가능 (VERIFIED)', TAMPERED: '⛔ 위조 티켓 (TAMPERED)' }[res.status] || res.status;
    const same = rec.version === t.version && rec.recordHash === recordHash;
    $('checkinResult').innerHTML = '<div class="result ' + res.status + '">' + label + '</div>' +
      '<div class="cmp"><div class="cmp-row">블록체인에 기록된 해시값 (최신 v' + rec.version +
        (rec.blockState === 'PENDING' ? ', 블록 기록 중' : '') + ')<code>' + rec.recordHash + '</code></div>' +
      '<div class="cmp-verdict">' + (same ? '= 같음' : '≠ 다름') + '</div>' +
      '<div class="cmp-row ' + (same ? 'same' : 'diff') + '">들고 온 티켓으로 다시 계산한 해시값 (v' + t.version + ')<code>' + recordHash + '</code></div></div>' +
      '<p class="msg">' + (res.reason || '') + '</p>';
    if (res.status === 'VERIFIED') await recordCheckin(t, rec);
  } catch (e) {
    $('checkinResult').innerHTML = '<p class="msg err">' + e.message + '</p>';
  }
}

// ---------- 화면 새로고침 ----------
function fillSelect(sel, seats, label = s => s + '번') {
  const before = sel.value;
  sel.innerHTML = seats.map(s => '<option value="' + s + '">' + label(s) + '</option>').join('');
  if (seats.includes(Number(before))) sel.value = before;
  return before !== sel.value;
}

async function refreshSeats() {
  try { records = await api('GET', '/api/demo/events/' + eventId() + '/records'); }
  catch (e) { $('seats').innerHTML = '<p class="msg err">' + e.message + '</p>'; return; }
  records.sort((a, b) => (keyToSeat[a.recordKey] || 0) - (keyToSeat[b.recordKey] || 0));
  renderSeats();

  const seatsOf = (...types) => records.filter(r => types.includes(r.type)).map(r => keyToSeat[r.recordKey]);
  // 어떤 좌석이든 고를 수 있다 — 이미 예매된 좌석을 또 예매하면 FairChain이 409로 거절하는 걸 보여 주려고
  const seatLabel = s => s + '번 (' + TYPE_LABEL[seatRecord(s).type] + ')';
  const all = seatsOf('ISSUE', 'PURCHASE', 'CANCEL', 'CHECKIN');
  fillSelect($('buySeat'), all, seatLabel);
  fillSelect($('cancelSeat'), all, seatLabel);
  const changed = fillSelect($('ticketSeat'), seatsOf('PURCHASE', 'CANCEL', 'CHECKIN'), s => {
    const r = seatRecord(s);
    return seatName(s) + ' · ' + who(r.userId) + ' (' + TYPE_LABEL[r.type] + ')';
  });
  // 고른 좌석이 바뀌었거나, 보이는 티켓의 좌석 기록이 그새 바뀌었으면(예매 · 취소) 티켓을 다시 만든다
  const shown = ticket && records.find(r => r.recordKey === ticket.recordKey);
  if (ticket && !shown) { ticket = null; $('ticketCard').innerHTML = ''; $('checkinResult').innerHTML = ''; }
  if ($('ticketSeat').value && (changed || !ticket || shown.version !== ticket.baseVersion)) prepareTicket(defaultKind());
}

function renderSeats() {
  $('seats').innerHTML = records.map(r => {
    const seat = keyToSeat[r.recordKey] || '?';
    const owner = r.type === 'ISSUE' ? '주인 없음' : who(r.userId);
    return '<div class="seat t-' + r.type + (seat === selectedSeat ? ' sel' : '') + '" onclick="selectSeat(' + seat + ')">' +
      '<b>' + seat + '번</b> <span class="type ' + r.type + '">' + TYPE_LABEL[r.type] + '</span><br>' +
      '<span class="who">' + owner + '</span> · v' + r.version + '<br>' +
      '<span class="badge ' + r.blockState + '">' + r.blockState + '</span>' +
      (r.blockIndex != null ? ' 블록 #' + r.blockIndex : '') +
      (r.totalMillis != null ? '<br><span class="ms">노드 ' + (r.nodeMillis ?? '-') + 'ms · 접수→확정 ' + r.totalMillis + 'ms</span>' : '') + '</div>';
  }).join('') || '<p class="msg">아직 발행된 좌석이 없어요</p>';
  // 이번 화면의 좌석별 최신 기록 중 시간이 잡힌 것들의 평균 (FairChain이 잰 값)
  const timed = records.filter(r => r.totalMillis != null && r.nodeMillis != null);
  const avg = f => Math.round(timed.reduce((a, r) => a + f(r), 0) / timed.length);
  $('timing').textContent = timed.length
    ? '블록 확정 시간 (FairChain 측정, 좌석별 최신 기록 ' + timed.length + '건 평균) — 노드 응답 ' + avg(r => r.nodeMillis) +
      'ms · 접수부터 확정까지 ' + avg(r => r.totalMillis) + 'ms · 가장 느린 노드 응답 ' + Math.max(...timed.map(r => r.nodeMillis)) + 'ms'
    : '';
}

async function refreshNodes() {
  let list;
  try { list = await api('GET', '/api/demo/nodes'); } catch (e) { return; }
  // 가장 앞선 정상 노드의 블록 수보다 적으면 "따라잡는 중"
  const top = Math.max(0, ...list.filter(n => n.up && n.chainValid).map(n => n.height));
  // 리더 층 Raft 역할 (리더 · 팔로워 · learner). 예전 노드면 raftRole이 없다
  const roleName = { LEADER: '👑 리더', FOLLOWER: '팔로워', LEARNER: 'learner (따라잡는 중)', CANDIDATE: '선거 중' };
  $('nodes').innerHTML = list.map(n => {
    const name = '<b>노드 ' + n.node + (n.id ? ' · ' + n.id + ' (팀 ' + n.team + ')' : '') + '</b>';
    if (!n.up) return '<div class="node down">' + name + ' — 꺼짐<br>투표에 참여하지 않음 (켜지면 따라잡음)</div>';
    const state = n.outReason ? ['bad', '⛔ ' + n.outReason]
                : !n.chainValid ? ['bad', '⛔ 체인 깨짐 · 투표에서 빠짐']
                : n.height < top ? ['lag', '⏳ 따라잡는 중 (' + (top - n.height) + '개 남음)']
                : ['', '✅ 정상 · ' + (roleName[n.raftRole] || '투표 참여') + (n.term ? ' · 임기 ' + n.term : '')];
    const team = (n.teamMembers || []).map(m => m.id + ' ' + (m.state === 'OK' ? '✅' : m.state)).join(', ');
    return '<div class="node ' + state[0] + '">' + name + ' — 블록 ' + n.height + '개<br>' + state[1] +
      (team ? '<br>팀원: ' + team : '') +
      '<br>마지막 블록 해시<div class="hash">' + (n.headHash || '-') + '</div></div>';
  }).join('');
  const up = list.filter(n => n.up);
  const voters = up.filter(n => n.chainValid && !n.outReason).length;
  const same = up.length > 1 && up.every(n => n.headHash === up[0].headHash);
  const anyRaft = up.find(n => n.raftRole && !n.outReason);
  const verdicts = anyRaft ? anyRaft.verdicts : [];
  const actionName = { EXCLUDED: '제외', RESTORED: '복귀', HALTED: '기록 중단', RESUMED: '재개' };
  $('nodeSummary').textContent = (same ? '노드 ' + up.length + '대의 마지막 블록 해시가 모두 같아요'
                                       : '노드끼리 마지막 블록 해시가 달라요 (기록 · 따라잡기 중이거나 노드 이상)') +
    ' · 투표 가능한 노드 ' + voters + '대' + (voters >= 2 ? ' → 새 기록 확정 가능' : ' → 2대 미만이라 새 기록 확정 불가') +
    (anyRaft && anyRaft.halted ? ' · ⛔ 기록 중단 (관리자 확인 필요)' : '') +
    (verdicts.length ? ' · 판정: ' + verdicts.slice(-4).map(v => (v.suspectId || '') + ' #' + (v.blockIndex ?? '-') + ' '
        + (actionName[v.action] || v.action) + (v.differentFields && v.differentFields.length ? ' (' + v.differentFields.join(', ') + ')' : '')).join(' → ') : '');
}

function refresh() { refreshSeats(); refreshNodes(); }
buildSeatKeys();
setInterval(refresh, 1000);
