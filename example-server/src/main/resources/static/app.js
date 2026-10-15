// FairChain 예제 예매 사이트 — 해시 주소(#/...)로 화면을 바꾸는 단일 페이지
const view = document.getElementById('view');
const state = { me: null, config: { queueEnabled: true, fairChainUrl: '' }, timers: [] };

// ---------------------------------------------------------------- 공용
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const won = (v) => v == null ? '-' : Number(v).toLocaleString('ko-KR') + '원';
const when = (t) => t ? String(t).replace('T', ' ').slice(0, 16) : '-';

async function api(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const err = new Error((data && data.message) || `요청 실패 (HTTP ${res.status})`);
    err.status = res.status;
    throw err;
  }
  return data;
}

function toast(message) {
  const el = document.getElementById('toast');
  el.textContent = message;
  el.classList.remove('hidden');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => el.classList.add('hidden'), 3000);
}

function every(ms, fn) {
  const id = setInterval(fn, ms);
  state.timers.push(() => clearInterval(id));
}

function onLeave(fn) {
  state.timers.push(fn);
}

function render(html) {
  view.innerHTML = html;
}

function requireLogin() {
  if (state.me) return true;
  location.hash = '#/login?next=' + encodeURIComponent(location.hash);
  return false;
}

async function refreshMe() {
  try { state.me = await api('GET', '/api/members/me'); } catch { state.me = null; }
  document.getElementById('account').innerHTML = state.me
    ? `<span><b>${esc(state.me.name)}</b> 님</span><button class="btn ghost small" id="logoutBtn">로그아웃</button>`
    : `<a class="btn ghost small" href="#/login">로그인</a><a class="btn small" href="#/signup">회원가입</a>`;
  const btn = document.getElementById('logoutBtn');
  if (btn) btn.onclick = async () => { await api('POST', '/api/members/logout'); await refreshMe(); location.hash = '#/'; };
}

const verificationBadge = {
  VERIFIED: ['ok', '블록체인 검증 완료'],
  PENDING: ['', '블록 기록 중'],
  TAMPER_SUSPECTED: ['bad', '위조 의심'],
  UNREGISTERED_LOCAL: ['bad', '기록 없음 (가짜 의심)'],
  MISSING_LOCAL: ['warn', '로컬 티켓 없음 (삭제 의심)'],
  UNAVAILABLE: ['', '노드 확인 불가'],
};
const ticketStatusText = { ACTIVE: '사용 가능', CANCELLED: '취소됨', CHECKED_IN: '입장 완료' };

// ---------------------------------------------------------------- 화면
const routes = [
  [/^#\/?$/, concertsPage],
  [/^#\/concert\/(\d+)/, concertPage],
  [/^#\/queue\/(\d+)/, queuePage],
  [/^#\/seats\/(\d+)/, seatsPage],
  [/^#\/tickets/, ticketsPage],
  [/^#\/login/, loginPage],
  [/^#\/signup/, signupPage],
  [/^#\/staff/, staffPage],
  [/^#\/admin/, adminPage],
];

async function route() {
  state.timers.splice(0).forEach((stop) => stop());
  const hash = location.hash || '#/';
  document.querySelectorAll('nav a').forEach((a) => a.classList.toggle('active',
    (a.dataset.nav === 'concerts' && /^#\/?($|concert|queue|seats)/.test(hash)) || hash.startsWith('#/' + a.dataset.nav)));
  for (const [pattern, page] of routes) {
    const m = hash.match(pattern);
    if (m) {
      try { await page(...m.slice(1)); } catch (e) { render(`<div class="card error">${esc(e.message)}</div>`); }
      return;
    }
  }
  render('<div class="card">페이지가 없습니다</div>');
}

function query(name) {
  const q = (location.hash.split('?')[1] || '');
  return new URLSearchParams(q).get(name);
}

// 공연 목록 (UIR-03)
async function concertsPage() {
  render(`<h1>공연</h1>
    <div class="card form wide" style="margin-bottom:16px">
      <label>공연명<input id="kw" placeholder="검색어"></label>
      <label>공연장<input id="venue" placeholder="예: 서울"></label>
      <button class="btn" id="search">검색</button>
    </div>
    <div class="grid" id="list"></div>`);
  const load = async () => {
    const list = await api('GET', `/api/concerts?keyword=${encodeURIComponent(kw.value)}&venue=${encodeURIComponent(venue.value)}`);
    document.getElementById('list').innerHTML = list.map((c) => `
      <div class="card concert" data-id="${c.id}">
        <div class="poster">${esc(c.title)}</div>
        <div class="muted small">${esc(c.venue)}</div>
        <div style="margin:6px 0">공연 ${when(c.eventStart)}</div>
        ${c.open ? '<span class="badge ok">예매 중</span>' : `<span class="badge warn">오픈 ${when(c.eventOpen)}</span>`}
      </div>`).join('') || '<div class="card muted">공연이 없습니다</div>';
    document.querySelectorAll('.concert').forEach((el) => el.onclick = () => location.hash = '#/concert/' + el.dataset.id);
  };
  document.getElementById('search').onclick = load;
  await load();
}

// 공연 상세
async function concertPage(id) {
  const c = await api('GET', '/api/concerts/' + id);
  render(`<div class="card">
      <div class="poster" style="height:160px">${esc(c.title)}</div>
      <h1>${esc(c.title)}</h1>
      <p class="muted">${esc(c.venue)}</p>
      <table><tbody>
        <tr><th>예매 오픈</th><td>${when(c.eventOpen)}</td></tr>
        <tr><th>공연</th><td>${when(c.eventStart)} ~ ${when(c.eventEnd)}</td></tr>
        <tr><th>좌석</th><td>${c.emptySeats} / ${c.totalSeats}석 예매 가능${c.totalSeats === 0 ? ' <span class="muted small">(운영자 화면에서 좌석을 먼저 등록하세요)</span>' : ''}</td></tr>
        ${c.seatGrades.map((g) => `<tr><th>${esc(g.gradeName)}석</th><td>${won(g.price)}</td></tr>`).join('')}
      </tbody></table>
      <div style="margin-top:18px">
        <button class="btn" id="book" ${c.open ? '' : 'disabled'}>${c.open ? '예매하기' : '예매 오픈 전'}</button>
        <span class="muted small" style="margin-left:10px">${state.config.queueEnabled ? 'FairChain 대기열을 거쳐 좌석을 고릅니다' : '대기열 없이 바로 좌석을 고릅니다'}</span>
      </div>
    </div>`);
  document.getElementById('book').onclick = () => {
    if (!requireLogin()) return;
    location.hash = (state.config.queueEnabled ? '#/queue/' : '#/seats/') + id;
  };
}

// 대기열 (UIR-04) — 진입 후 SSE로 순번을 받고, 입장 가능해지면 좌석 선택으로
async function queuePage(id) {
  if (!requireLogin()) return;
  const c = await api('GET', '/api/concerts/' + id);
  render(`<div class="card queue-box">
      <h2>${esc(c.title)}</h2>
      <p class="muted">FairChain 대기열</p>
      <div class="queue-rank" id="rank">…</div>
      <p id="msg" class="muted">대기열에 들어가는 중</p>
      <button class="btn ghost" id="leave">대기열 나가기</button>
    </div>`);
  const show = (s) => {
    if (s.state === 'WAITING') {
      document.getElementById('rank').textContent = s.rank + '번째';
      document.getElementById('msg').textContent = `전체 대기 ${s.waitingCount}명 · 차례가 되면 자동으로 좌석 선택 화면으로 이동합니다`;
    } else if (s.state === 'ADMITTED') {
      document.getElementById('rank').textContent = '입장!';
      document.getElementById('msg').textContent = '좌석 선택 화면으로 이동합니다';
      setTimeout(() => { if (location.hash.startsWith('#/queue/' + id)) location.hash = '#/seats/' + id; }, 800);
      return true;
    } else {
      document.getElementById('rank').textContent = '-';
      document.getElementById('msg').textContent = s.state === 'EXPIRED' ? '입장 권한이 만료되었습니다. 다시 줄을 서세요' : '대기열에 없습니다';
    }
    return false;
  };
  const first = await api('POST', `/api/queue/${id}`);
  if (show(first)) return;
  let done = false;
  const source = new EventSource(`/api/queue/${id}/subscribe`);
  source.addEventListener('status', (e) => { done = show(JSON.parse(e.data)) || done; });
  source.onerror = () => source.close();
  onLeave(() => source.close());
  // SSE가 끊겨도 순번을 놓치지 않도록 느리게 확인
  every(3000, async () => { if (!done) done = show(await api('GET', `/api/queue/${id}/status`)); });
  document.getElementById('leave').onclick = async () => {
    await api('DELETE', `/api/queue/${id}`);
    toast('대기열에서 나왔습니다');
    location.hash = '#/concert/' + id;
  };
}

// 좌석 선택 · 예매 (UIR-05). ?offer=토큰&seat=좌석이면 재배정 예매
async function seatsPage(id) {
  if (!requireLogin()) return;
  const offer = query('offer');
  const offerSeat = Number(query('seat'));
  const [c, data] = await Promise.all([api('GET', '/api/concerts/' + id), api('GET', `/api/concerts/${id}/seats`)]);
  const grades = {};
  data.seats.forEach((s) => (grades[s.gradeName] = grades[s.gradeName] || []).push(s));
  render(`<h1>${esc(c.title)} · 좌석 선택</h1>
    ${offer ? '<div class="card"><span class="badge accent">재배정 제안</span> 취소표 좌석을 예매할 수 있습니다. 제한시간 안에 예매하세요.</div>' : ''}
    <div class="card">
      <div class="legend"><span>□ 예매 가능</span><span style="color:#9ca3af">■ 예매됨</span><span style="color:#b45309">■ 재배정 중</span></div>
      ${Object.entries(grades).map(([g, seats]) => `<div class="seat-grade"><h2>${esc(g)}석 · ${won(seats[0].price)}</h2>
        <div class="seats">${seats.map((s) => `<button class="seat ${s.status}" data-id="${s.seatId}" ${s.status === 'EMPTY' || s.seatId === offerSeat ? '' : 'disabled'}>${esc(s.seatNumber)}</button>`).join('')}</div></div>`).join('')
        || '<p class="muted">등록된 좌석이 없습니다. 운영자 화면에서 좌석을 먼저 등록하세요.</p>'}
      <div style="margin-top:12px; display:flex; gap:12px; align-items:center">
        <button class="btn" id="reserve" disabled>예매하기</button><span id="picked" class="muted"></span>
      </div>
      <p class="error" id="err"></p>
    </div>`);
  let selected = offer ? offerSeat : null;
  const pick = (seatId) => {
    selected = seatId;
    document.querySelectorAll('.seat').forEach((b) => b.classList.toggle('selected', Number(b.dataset.id) === seatId));
    const seat = data.seats.find((s) => s.seatId === seatId);
    document.getElementById('picked').textContent = seat ? `${seat.gradeName}석 ${seat.seatNumber} · ${won(seat.price)}` : '';
    document.getElementById('reserve').disabled = !seat;
  };
  document.querySelectorAll('.seat:not([disabled])').forEach((b) => b.onclick = () => pick(Number(b.dataset.id)));
  if (selected) pick(selected);
  document.getElementById('reserve').onclick = async () => {
    document.getElementById('err').textContent = '';
    try {
      const r = await api('POST', '/api/reservations', { seatId: selected, concertId: Number(id), offerToken: offer || null });
      if (!r.success) { document.getElementById('err').textContent = r.reason; return; }
      toast('예매 완료! 티켓 번호 ' + r.ticketId);
      location.hash = '#/tickets';
    } catch (e) { document.getElementById('err').textContent = e.message; }
  };
}

// 내 티켓 (UIR-06 · 07) — 블록체인 검증 상태, 취소, 재배정 제안
async function ticketsPage() {
  if (!requireLogin()) return;
  render(`<h1>내 티켓</h1>
    <div id="offers"></div>
    <div class="card"><div id="tickets" class="muted">불러오는 중…</div></div>
    <p class="muted small">검증 상태는 3초마다 갱신됩니다. 예매 직후에는 "블록 기록 중"이었다가 노드 3대 중 2대가 확정하면 "블록체인 검증 완료"로 바뀝니다.</p>`);
  const load = async () => {
    const [list, offers] = await Promise.all([api('GET', '/api/tickets'), api('GET', '/api/reallocation/offers')]);
    document.getElementById('offers').innerHTML = offers.map((o) => `
      <div class="card"><span class="badge accent">재배정 제안</span>
        <b>${esc(o.concertTitle)}</b> ${esc(o.seatNumber)}석 — ${when(o.expiresAt)}까지
        <a class="btn small" style="margin-left:12px" href="#/seats/${o.concertId}?offer=${o.offerToken}&seat=${o.seatId}">예매하기</a></div>`).join('');
    document.getElementById('tickets').innerHTML = list.tickets.length === 0 ? '보유한 티켓이 없습니다' : `
      ${list.verificationAvailable ? '' : '<p class="error">FairChain · 노드에 연결할 수 없어 검증하지 못했습니다</p>'}
      <table><thead><tr><th>#</th><th>공연</th><th>좌석</th><th>상태</th><th>블록체인 검증</th><th>블록</th><th>입장 QR 토큰</th><th></th></tr></thead><tbody>
      ${list.tickets.map((t) => {
        const [cls, text] = verificationBadge[t.verificationStatus] || ['', '-'];
        return `<tr>
          <td>${t.ticketId ?? '-'}</td><td>${esc(t.concertTitle)}</td><td>${esc(t.seatGrade ?? '')} ${esc(t.seatNumber)}</td>
          <td>${ticketStatusText[t.status] || '-'} <span class="muted small">v${t.version ?? '-'}</span></td>
          <td>${t.verificationStatus ? `<span class="badge ${cls}">${text}</span>` : '-'}</td>
          <td>${t.blockIndex ?? '-'}</td>
          <td>${t.qrToken ? `<span class="mono">${esc(t.qrToken)}</span> <button class="link copy" data-qr="${esc(t.qrToken)}">복사</button>` : '-'}</td>
          <td>${t.status === 'ACTIVE' ? `<button class="btn danger small cancel" data-id="${t.ticketId}">취소</button>` : ''}</td></tr>`;
      }).join('')}</tbody></table>`;
    document.querySelectorAll('.copy').forEach((b) => b.onclick = () => navigator.clipboard.writeText(b.dataset.qr).then(() => toast('QR 토큰을 복사했습니다')));
    document.querySelectorAll('.cancel').forEach((b) => b.onclick = async () => {
      if (!confirm('이 티켓을 취소할까요?')) return;
      try { await api('POST', `/api/tickets/${b.dataset.id}/cancel`); toast('취소했습니다'); load(); } catch (e) { toast(e.message); }
    });
  };
  await load();
  every(3000, () => load().catch(() => {}));
}

// 로그인 · 회원가입 (UIR-01)
async function loginPage() {
  render(`<div class="card" style="max-width:440px;margin:40px auto"><h1>로그인</h1>
    <form class="form" id="f"><input id="id" placeholder="아이디" required><input id="pw" type="password" placeholder="비밀번호" required>
    <button class="btn">로그인</button><p class="error" id="err"></p></form>
    <p class="muted small">계정이 없나요? <a href="#/signup">회원가입</a></p></div>`);
  document.getElementById('f').onsubmit = async (e) => {
    e.preventDefault();
    try {
      await api('POST', '/api/members/login', { loginId: id.value, password: pw.value });
      await refreshMe();
      location.hash = query('next') || '#/';
    } catch (err) { document.getElementById('err').textContent = err.message; }
  };
}

async function signupPage() {
  render(`<div class="card" style="max-width:440px;margin:40px auto"><h1>회원가입</h1>
    <form class="form" id="f"><input id="id" placeholder="아이디" required><input id="pw" type="password" placeholder="비밀번호" required>
    <input id="nm" placeholder="이름" required><input id="ct" placeholder="연락처 (선택)">
    <button class="btn">가입하기</button><p class="error" id="err"></p></form></div>`);
  document.getElementById('f').onsubmit = async (e) => {
    e.preventDefault();
    try {
      await api('POST', '/api/members', { loginId: id.value, password: pw.value, name: nm.value, contact: ct.value });
      await api('POST', '/api/members/login', { loginId: id.value, password: pw.value });
      await refreshMe();
      toast('가입했습니다');
      location.hash = '#/';
    } catch (err) { document.getElementById('err').textContent = err.message; }
  };
}

// 게이트 스태프 — QR 스캔 · 입장 처리 (UIR-09)
async function staffPage() {
  const loggedIn = sessionStorage.getItem('staff');
  if (!loggedIn) {
    render(`<div class="card" style="max-width:440px;margin:40px auto"><h1>게이트 스태프 로그인</h1>
      <form class="form" id="f"><input id="id" placeholder="스태프 아이디" required><input id="pw" type="password" placeholder="비밀번호" required>
      <button class="btn">로그인</button><p class="error" id="err"></p></form></div>`);
    document.getElementById('f').onsubmit = async (e) => {
      e.preventDefault();
      try {
        const r = await api('POST', '/api/staff/login', { loginId: id.value, password: pw.value });
        sessionStorage.setItem('staff', r.name);
        staffPage();
      } catch (err) { document.getElementById('err').textContent = err.message; }
    };
    return;
  }
  render(`<h1>QR 스캔 · 입장 처리</h1>
    <div class="card"><p class="muted">스태프 <b>${esc(loggedIn)}</b> · QR에서 읽은 값을 그대로 넣으세요 (내 티켓 화면의 QR 토큰)</p>
      <form class="form" id="f" style="max-width:none;grid-template-columns:1fr auto">
        <input id="qr" placeholder="QR 토큰" required autofocus><button class="btn">입장 처리</button></form>
      <div id="result"></div>
      <p style="margin-top:16px"><button class="link" id="out">스태프 로그아웃</button></p></div>`);
  document.getElementById('f').onsubmit = async (e) => {
    e.preventDefault();
    const box = document.getElementById('result');
    try {
      const r = await api('POST', '/api/staff/scan', { qrToken: qr.value });
      box.innerHTML = r.success
        ? `<div class="scan-result ok">입장 허용<div class="small" style="font-weight:600;margin-top:6px">${esc(r.concertTitle)} · ${esc(r.seatNumber)}석</div></div>`
        : `<div class="scan-result bad">입장 거부<div class="small" style="font-weight:600;margin-top:6px">${esc(r.reason)}</div></div>`;
      qr.select();
    } catch (err) {
      if (err.status === 401) { sessionStorage.removeItem('staff'); return staffPage(); }
      box.innerHTML = `<div class="scan-result bad">${esc(err.message)}</div>`;
    }
  };
  document.getElementById('out').onclick = async () => { await api('POST', '/api/staff/logout'); sessionStorage.removeItem('staff'); staffPage(); };
}

// 운영자 — 키 · 콘서트 · 좌석 사전 등록 · 스태프 (UIR-10)
async function adminPage() {
  let keys;
  try {
    keys = await api('GET', '/api/operator/keys');
  } catch (e) {
    if (e.status !== 401) { keys = { error: e.message }; } else {
      render(`<div class="card" style="max-width:440px;margin:40px auto"><h1>운영자 로그인</h1>
        <form class="form" id="f"><input id="id" placeholder="운영자 아이디" required><input id="pw" type="password" placeholder="비밀번호" required>
        <button class="btn">로그인</button><p class="error" id="err"></p></form></div>`);
      document.getElementById('f').onsubmit = async (ev) => {
        ev.preventDefault();
        try { await api('POST', '/api/operator/login', { loginId: id.value, password: pw.value }); adminPage(); }
        catch (err) { document.getElementById('err').textContent = err.message; }
      };
      return;
    }
  }
  const fc = state.config.fairChainUrl;
  const concerts = await api('GET', '/api/concerts');
  const options = concerts.map((c) => `<option value="${c.id}">${c.id} · ${esc(c.title)}</option>`).join('');
  render(`<h1>운영자</h1>
    <div class="card"><h2>FairChain 연동</h2>
      ${keys.error ? `<p class="error">${esc(keys.error)}</p>` : `
      <table><tbody>
        <tr><th>API Key</th><td class="mono">${esc((keys.apiKey || '').slice(0, 6))}… (서버 설정 FAIRCHAIN_API_KEY)</td></tr>
        <tr><th>대시보드 토큰</th><td class="mono">${esc(keys.dashboardToken || '-')}</td></tr>
      </tbody></table>`}
      <p style="margin-top:12px"><a class="btn small" target="_blank" href="${esc(fc)}/inspector.html">FairChain 점검 화면 (요청 · DB · 블록)</a>
        <a class="btn ghost small" target="_blank" href="${esc(fc)}/dashboard.html">관제 대시보드</a></p>
      <p class="muted small">점검 화면 · 대시보드는 FairChain 운영자 계정으로 로그인합니다.</p>
    </div>
    <div class="cols">
      <div class="card"><h2>좌석 사전 등록</h2>
        <form class="form" id="seatForm" style="max-width:none">
          <label>공연<select id="sc">${options}</select></label>
          <label>좌석 등급<select id="sg"></select></label>
          <label>좌석 번호 (예: A1-A10, B1, B2)<input id="sn" value="A1-A10" required></label>
          <button class="btn">등록 (FairChain에 발행 기록)</button><p class="error" id="seatErr"></p>
        </form></div>
      <div class="card"><h2>좌석 현황</h2>
        <label>공연<select id="vc">${options}</select></label>
        <div id="seatView" style="margin-top:12px"></div></div>
    </div>
    <div class="card"><h2>공연</h2>
      <table><thead><tr><th>#</th><th>제목</th><th>공연장</th><th>예매 오픈</th><th>공연</th><th></th></tr></thead><tbody>
      ${concerts.map((c) => `<tr><td>${c.id}</td><td>${esc(c.title)}</td><td>${esc(c.venue)}</td><td>${when(c.eventOpen)}</td><td>${when(c.eventStart)}</td>
        <td><button class="btn danger small del" data-id="${c.id}">삭제</button></td></tr>`).join('')}</tbody></table>
      <h2 style="margin-top:20px">공연 등록</h2>
      <form class="form wide" id="concertForm">
        <label>제목<input id="ct" required></label><label>공연장<input id="cv" required></label>
        <label>예매 오픈<input id="co" type="datetime-local" required></label>
        <label>공연 시작<input id="cs" type="datetime-local" required></label>
        <label>공연 종료<input id="ce" type="datetime-local" required></label>
        <label>좌석 등급 (등급:가격, …)<input id="cg" value="R:132000, S:88000"></label>
        <button class="btn">등록</button>
      </form><p class="error" id="concertErr"></p></div>
    <div class="card"><h2>게이트 스태프 계정 등록</h2>
      <form class="form wide" id="staffForm"><label>아이디<input id="sid" required></label><label>비밀번호<input id="spw" type="password" required></label>
        <label>이름<input id="snm" required></label><button class="btn">등록</button></form><p class="error" id="staffErr"></p></div>
    <p><button class="link" id="out">운영자 로그아웃</button></p>`);

  const loadGrades = async () => {
    if (!sc.value) return;
    const c = await api('GET', '/api/concerts/' + sc.value);
    sg.innerHTML = c.seatGrades.map((g) => `<option value="${g.id}">${esc(g.gradeName)} · ${won(g.price)}</option>`).join('');
  };
  const loadSeats = async () => {
    if (!vc.value) return;
    const data = await api('GET', `/api/concerts/${vc.value}/seats`);
    const count = (s) => data.seats.filter((x) => x.status === s).length;
    document.getElementById('seatView').innerHTML = `<p class="muted small">예매 가능 ${count('EMPTY')} · 예매됨 ${count('RESERVED')} · 재배정 중 ${count('CANCELLED')}</p>
      <div class="seats">${data.seats.map((s) => `<span class="seat ${s.status}" style="display:grid;place-items:center">${esc(s.seatNumber)}</span>`).join('') || '<span class="muted">좌석 없음</span>'}</div>`;
  };
  sc.onchange = loadGrades;
  vc.onchange = loadSeats;
  await loadGrades();
  await loadSeats();
  every(3000, () => loadSeats().catch(() => {}));

  document.getElementById('seatForm').onsubmit = async (e) => {
    e.preventDefault();
    document.getElementById('seatErr').textContent = '';
    try {
      const r = await api('POST', '/api/operator/seats', { concertId: Number(sc.value), seatGradeId: Number(sg.value), seatNumbers: [sn.value] });
      toast(`${r.registered.length}석 등록 — FairChain에 발행 기록을 보냈습니다`);
      vc.value = sc.value;
      loadSeats();
    } catch (err) { document.getElementById('seatErr').textContent = err.message; }
  };
  document.getElementById('concertForm').onsubmit = async (e) => {
    e.preventDefault();
    const seatGrades = cg.value.split(',').map((p) => p.split(':')).filter((p) => p.length === 2)
      .map(([g, p]) => ({ gradeName: g.trim(), price: Number(p.trim()) }));
    try {
      await api('POST', '/api/operator/concerts', { title: ct.value, venue: cv.value, eventOpen: co.value, eventStart: cs.value, eventEnd: ce.value, seatGrades });
      toast('공연을 등록했습니다');
      adminPage();
    } catch (err) { document.getElementById('concertErr').textContent = err.message; }
  };
  document.querySelectorAll('.del').forEach((b) => b.onclick = async () => {
    if (!confirm('이 공연을 삭제할까요?')) return;
    try { await api('DELETE', '/api/operator/concerts/' + b.dataset.id); adminPage(); } catch (err) { toast(err.message); }
  });
  document.getElementById('staffForm').onsubmit = async (e) => {
    e.preventDefault();
    try { await api('POST', '/api/operator/staff', { loginId: sid.value, password: spw.value, name: snm.value }); toast('스태프 계정을 만들었습니다'); e.target.reset(); }
    catch (err) { document.getElementById('staffErr').textContent = err.message; }
  };
  document.getElementById('out').onclick = async () => { await api('POST', '/api/operator/logout'); adminPage(); };
}

// ---------------------------------------------------------------- 시작
window.addEventListener('hashchange', route);
(async () => {
  try { state.config = await api('GET', '/api/config'); } catch { /* 기본값 사용 */ }
  await refreshMe();
  await route();
})();
