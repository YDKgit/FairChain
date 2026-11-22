// 관제 대시보드 (SD-605)
//  - 지표 카드 · 공연별 현황: 서버가 1초마다 밀어 주는 SSE(metrics · summary)
//  - 진행 로그: 점검 화면과 같은 조회(받은 요청 · 기록)를 2초마다 읽어 사람이 읽는 말로 바꾼다
//  - 노드: 3초마다 노드 상태 조회
(() => {
  const { $, esc, bindLogin, session, getWithToken } = FairChainConsole;
  const num = (n) => Number(n || 0).toLocaleString('ko-KR');
  const clock = (d) => d.toTimeString().slice(0, 8);

  const BUCKET_MS = 2000, BUCKETS = 30, LOG_MAX = 60, PER_KIND = 3;
  const state = { source: null, timers: [], buckets: [], totals: {}, lastRequestId: 0, lastAnchorId: 0, firstLog: true, log: [] };

  // ───── 로그인 · 로그아웃 ─────
  function start(clientId, token) {
    $('loginView').classList.add('hidden'); $('app').classList.remove('hidden');
    $('clientId').textContent = clientId || '';
    state.source = new EventSource('/dashboard/subscribe?token=' + encodeURIComponent(token));
    state.source.addEventListener('metrics', (e) => onMetrics(JSON.parse(e.data)));
    state.source.addEventListener('summary', (e) => onSummary(JSON.parse(e.data)));
    state.source.onopen = () => live(true);
    state.source.onerror = () => { live(false); $('error').textContent = '연결이 끊겼습니다. 토큰이 만료되었으면 다시 로그인하세요'; };
    pollLog(); pollNodes();
    state.timers = [setInterval(pollLog, 2000), setInterval(pollNodes, 3000)];
  }

  function stop() {
    session.clear(); state.source?.close(); state.timers.forEach(clearInterval);
    $('app').classList.add('hidden'); $('loginView').classList.remove('hidden');
  }
  $('logout').addEventListener('click', stop);

  function live(on) {
    $('live').textContent = on ? '실시간' : '연결 끊김';
    $('live').classList.toggle('off', !on);
    if (on) $('error').textContent = '';
  }

  // ───── 지표 카드 · 그래프 · 공연별 현황 ─────
  function onMetrics(rows) {
    live(true);
    const waiting = rows.reduce((sum, m) => sum + m.waitingCount, 0);
    $('sWaiting').textContent = num(waiting);
    $('sBlocked').textContent = num(rows.reduce((sum, m) => sum + m.blockedCount, 0));
    sample(waiting);
    renderEvents(rows);
  }

  function onSummary(s) {
    $('sAdmitted').textContent = num(s.admittedCount);
    $('sConfirmed').textContent = num(s.confirmedCount);
    $('sPending').textContent = s.pendingCount ? `확정 대기 ${num(s.pendingCount)}건` : '';
  }

  /** 2초 구간마다 그 구간의 최대 대기 인원을 남긴다 (화면을 연 뒤부터 쌓인다) */
  function sample(waiting) {
    const slot = Math.floor(Date.now() / BUCKET_MS);
    const last = state.buckets[state.buckets.length - 1];
    if (last && last.slot === slot) last.value = Math.max(last.value, waiting);
    else state.buckets.push({ slot, value: waiting });
    while (state.buckets.length > BUCKETS) state.buckets.shift();
    renderChart();
  }

  function renderChart() {
    const list = state.buckets, max = Math.max(1, ...list.map((b) => b.value));
    const peak = list.reduce((a, b) => (b.value > a.value ? b : a), list[0]);
    const empty = Array(BUCKETS - list.length).fill('<i style="height:0;min-height:0"></i>').join('');
    $('bars').innerHTML = empty + list.map((b) =>
      `<i class="${peak.value > 0 && b === peak ? 'top' : ''}" style="height:${(b.value / max) * 100}%" title="${clock(new Date(b.slot * BUCKET_MS))} · ${num(b.value)}명"></i>`).join('');
    $('peak').textContent = peak.value > 0 ? `피크 ${num(peak.value)}명 (${clock(new Date(peak.slot * BUCKET_MS))})` : '지금은 대기 중인 사람이 없습니다';
    $('axisFrom').textContent = clock(new Date((list[list.length - 1].slot - BUCKETS + 1) * BUCKET_MS));
    $('axisTo').textContent = clock(new Date(list[list.length - 1].slot * BUCKET_MS));
  }

  function renderEvents(rows) {
    if (!rows.length) { $('events').innerHTML = '<p class="hint">등록된 공연이 없습니다</p>'; return; }
    $('events').innerHTML = rows.slice().sort((a, b) => a.eventId - b.eventId).map((m) => {
      const total = state.totals[m.eventId], sold = total ? total - m.remainingSlots : null;
      const rate = total ? Math.round((sold / total) * 100) : 0;
      return `<div class="event">
        <div class="name">공연 #${m.eventId}</div>
        <div class="seats">
          <div class="track"><div class="fill ${total && m.remainingSlots === 0 ? 'full' : ''}" style="width:${rate}%"></div></div>
          <div class="cap">${total ? `판매 ${num(sold)} / ${num(total)}석 · 남은 좌석 ${num(m.remainingSlots)}${m.remainingSlots === 0 ? ' · 매진' : ''}` : `남은 좌석 ${num(m.remainingSlots)}`}</div>
        </div>
        <div class="mini"><b>${num(m.waitingCount)}</b>대기</div>
        <div class="mini ${m.blockedCount ? 'hot' : ''}"><b>${num(m.blockedCount)}</b>차단</div>
      </div>`;
    }).join('');
  }

  // ───── 진행 로그 ─────
  const json = (text) => { try { return JSON.parse(text); } catch { return null; } };
  const who = (id) => (id == null ? '' : `회원 #${id}`);
  const join = (...parts) => parts.filter(Boolean).join(' · ');
  const RECORD = { PURCHASE: ['예매 접수', 'amber'], CANCEL: ['취소 접수', 'amber'], CHECKIN: ['입장 처리', 'amber'], ISSUE: ['좌석 발행', 'amber'] };
  const CONFIRM = { PURCHASE: '예매', CANCEL: '취소', CHECKIN: '입장', ISSUE: '좌석 발행' };

  /** 받은 요청 한 건 → 로그 한 줄. 화면에 보일 만한 일이 아니면 null */
  function fromRequest(r) {
    const [path, query = ''] = r.path.split('?');
    const params = new URLSearchParams(query), body = json(r.requestBody) || {}, reply = json(r.responseBody) || {};
    const userId = body.userId ?? params.get('userId'), eventId = body.eventId ?? params.get('eventId');
    const event = eventId == null ? '' : `공연 #${eventId}`;
    if (r.status === 429) return { what: '매크로 차단', color: 'red', detail: join(who(userId), '초당 요청 한도 초과'), key: 'macro:' + userId };
    if (r.status === 401) return { what: '인증 실패', color: 'red', detail: 'API Key가 없거나 틀린 요청' };
    if (path === '/api/queue/enter' && r.status < 300) return { what: '대기열 진입', color: 'blue', detail: join(who(userId), event, reply.sequenceNumber != null && `순번 ${num(reply.sequenceNumber)}`) };
    if (path === '/api/admission/verify' && r.status < 300) return { what: '입장 확인', color: 'green', detail: join(who(userId), event, reply.status === 'VERIFIED' ? '입장권 확인됨' : reply.reason || reply.status) };
    if (path === '/api/admin/seats/issue' && r.status < 300) return { what: '좌석 발행', color: 'amber', detail: join(event, '좌석 기록 접수') };
    if (path.endsWith('/reallocation-candidates') && r.status < 300) return { what: '취소표 재배정', color: 'blue', detail: join(`공연 #${path.split('/')[3]}`, `대기 명단 ${(reply.userIds || []).length}명`) };
    if (path.includes('/checkin') && r.method === 'POST') return { what: '입장 QR 검증', color: r.status < 300 ? 'green' : 'red', detail: join(event, r.status < 300 ? reply.status || '확인' : reply.message || `거절 (${r.status})`) };
    if (path === '/api/records') {
      const first = Array.isArray(body) ? body[0] || {} : body;
      const [what, color] = RECORD[first.type] || ['기록 접수', 'amber'];
      if (r.status >= 400) return { what: '기록 거절', color: 'red', detail: join(who(first.userId), reply.message || `HTTP ${r.status}`) };
      return { what, color, detail: join(who(first.userId), first.eventId != null && `공연 #${first.eventId}`, reply.soldOut && '매진') };
    }
    if (r.status >= 400 && r.status !== 404) return { what: '요청 거절', color: 'red', detail: join(path.replace('/api/', ''), reply.message || `HTTP ${r.status}`) };
    return null;   // 순번 조회 · 티켓 목록 같은 반복 조회는 보여 주지 않는다
  }

  /** 새로 확정된 기록들 → 로그. 같은 종류가 이어지면 한 줄로 묶는다 */
  function fromAnchors(anchors) {
    const out = [];
    for (const a of anchors) {
      const last = out[out.length - 1];
      if (last && last.type === a.type && last.eventId === a.eventId && a.type === 'ISSUE') { last.to = a.blockIndex; last.count++; continue; }
      out.push({ type: a.type, eventId: a.eventId, userId: a.userId, from: a.blockIndex, to: a.blockIndex, count: 1, time: a.createdAt });
    }
    return out.map((g) => ({
      what: '블록 확정', color: 'green', time: g.time,
      detail: g.count > 1 ? join(`#${g.from}~#${g.to}`, `공연 #${g.eventId}`, `${CONFIRM[g.type] || g.type} ${g.count}건`)
        : join(`#${g.from}`, `공연 #${g.eventId}`, `${CONFIRM[g.type] || g.type} 기록`, who(g.userId)),
    }));
  }

  /** 한 번에 같은 종류가 쏟아지면 최근 몇 줄만 남기고 나머지는 건수로 줄인다 */
  function squeeze(items) {
    const byKind = {};
    items.forEach((it) => (byKind[it.what] = byKind[it.what] || []).push(it));
    const out = [];
    Object.values(byKind).forEach((list) => {
      const keep = list.slice(-PER_KIND), rest = list.length - keep.length;
      if (rest > 0) keep[0] = { ...keep[0], detail: `${keep[0].detail} 외 ${num(rest)}건` };
      out.push(...keep);
    });
    return out.sort((a, b) => String(a.time).localeCompare(String(b.time)));
  }

  async function pollLog() {
    try {
      const [requests, db] = await Promise.all([
        getWithToken('/dashboard/inspect/requests', stop), getWithToken('/dashboard/inspect/db', stop)]);
      db.events.forEach((e) => { state.totals[e.eventId] = e.totalSeats; });

      const fresh = requests.filter((r) => r.id > state.lastRequestId).reverse();   // 오래된 것부터
      const confirmed = db.anchorRecords.filter((a) => a.id > state.lastAnchorId && a.blockState === 'CONFIRMED').reverse();
      if (requests.length) state.lastRequestId = Math.max(state.lastRequestId, requests[0].id);
      // 아직 확정되지 않은 기록은 다음에 다시 본다
      const pending = db.anchorRecords.filter((a) => a.blockState !== 'CONFIRMED').map((a) => a.id);
      const newest = db.anchorRecords.length ? db.anchorRecords[0].id : 0;
      state.lastAnchorId = Math.max(state.lastAnchorId, pending.length ? Math.min(...pending) - 1 : newest);

      let items = [...fresh.map((r) => { const it = fromRequest(r); return it && { ...it, time: r.time }; }).filter(Boolean), ...fromAnchors(confirmed)];
      items = squeeze(items);
      if (state.firstLog) { items = items.slice(-12); state.firstLog = false; }
      if (!items.length) return;
      state.log = [...items.reverse(), ...state.log].slice(0, LOG_MAX);            // 최신이 위
      $('log').innerHTML = state.log.map((it) => `<li>
        <span class="dot c-${it.color}"></span><span class="what c-${it.color}">${esc(it.what)}</span>
        <span class="detail" title="${esc(it.detail)}">${esc(it.detail)}</span><time>${esc(String(it.time).slice(11, 19))}</time></li>`).join('');
    } catch (e) { $('error').textContent = e.message; }
  }

  // ───── 노드 ─────
  const ROLE = { LEADER: '<b>리더</b>', FOLLOWER: '팔로워', LEARNER: '따라잡는 중', CANDIDATE: '리더 선출 중', NONE: '참여 안 함' };
  async function pollNodes() {
    try {
      const nodes = await getWithToken('/dashboard/inspect/nodes', stop);
      const up = nodes.filter((n) => n.reachable), heads = new Set(up.map((n) => `${n.status.height}:${n.status.headHash}`));
      $('nodeNote').textContent = !up.length ? '응답하는 노드가 없습니다'
        : heads.size === 1 ? `${up.length}/${nodes.length}대 응답 · 장부 일치` : `${up.length}/${nodes.length}대 응답 · 장부 맞추는 중`;
      $('nodes').innerHTML = nodes.map((n) => {
        const id = n.raft?.id || n.url.replace(/^https?:\/\//, '');
        if (!n.reachable) return `<div class="node down"><span class="dot"></span><span class="id">${esc(id)}</span><span class="role">응답 없음</span><span class="height"></span></div>`;
        const state2 = n.raft?.halted ? '기록 중단' : n.raft?.outReason ? esc(n.raft.outReason) : ROLE[n.raft?.raftRole] || '';
        return `<div class="node ${n.status.chainValid ? '' : 'down'}"><span class="dot"></span><span class="id">${esc(id)}</span>
          <span class="role">${state2}${n.status.chainValid ? '' : ' · 체인 손상'}</span><span class="height">블록 ${num(n.status.height)}개</span></div>`;
      }).join('') || '<p class="hint">등록된 노드가 없습니다</p>';
    } catch (e) { $('nodeNote').textContent = '노드 상태를 읽지 못했습니다'; }
  }

  bindLogin($('loginForm'), start);
})();
