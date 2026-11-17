// FairChain 운영자용 화면 공통 — 운영자 로그인(대시보드 토큰), 세션 보관, 화면 도우미
// dashboard.html · inspector.html이 함께 쓴다. 토큰은 탭을 닫으면 사라지는 sessionStorage에 둔다.
const FairChainConsole = (() => {
  const $ = (id) => document.getElementById(id);
  const esc = (v) => String(v ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const short = (h) => (h ? `<span class="mono" title="${esc(h)}">${esc(h.slice(0, 10))}…</span>` : '');
  const time = (t) => (t ? esc(String(t).replace('T', ' ').slice(0, 19)) : '');

  const session = {
    get token() { return sessionStorage.getItem('fcToken'); },
    get clientId() { return sessionStorage.getItem('fcClient'); },
    save(token, clientId) { sessionStorage.setItem('fcToken', token); sessionStorage.setItem('fcClient', clientId); },
    clear() { sessionStorage.removeItem('fcToken'); sessionStorage.removeItem('fcClient'); },
  };

  /** POST /dashboard/login → {clientId, dashboardToken}. 실패하면 서버가 준 message로 예외 */
  async function login(loginId, password) {
    const res = await fetch('/dashboard/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ loginId, password }),
    });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(body.message || '로그인 실패');
    session.save(body.dashboardToken, body.clientId);
    return body;
  }

  /**
   * 로그인 폼을 연결한다. 폼에는 #loginId · #password 입력과 #loginError가 있어야 한다.
   * 이미 세션이 있으면 바로 onLogin을 부른다.
   */
  function bindLogin(form, onLogin) {
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      try {
        await login($('loginId').value, $('password').value);
        onLogin(session.clientId, session.token);
      } catch (err) { $('loginError').textContent = err.message; }
    });
    if (session.token) onLogin(session.clientId, session.token);
  }

  /** 대시보드 토큰이 붙은 GET. 401이면 세션을 비우고 onExpired */
  async function getWithToken(path, onExpired) {
    const sep = path.includes('?') ? '&' : '?';
    const res = await fetch(`${path}${sep}token=${encodeURIComponent(session.token)}`);
    if (res.status === 401) { session.clear(); onExpired(); throw new Error('토큰이 만료되었습니다. 다시 로그인하세요'); }
    if (!res.ok) throw new Error(`${path}: HTTP ${res.status}`);
    return res.json();
  }

  return { $, esc, short, time, session, login, bindLogin, getWithToken };
})();
