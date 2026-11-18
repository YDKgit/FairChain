import jsQR from 'jsqr';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { get, post } from '../api.js';
import LoginForm from '../components/LoginForm.jsx';
import { when } from '../utils.js';

/** 게이트 스태프 — QR 토큰을 찍으면 FairChain 기록과 대조해 입장 허용 · 거절 */
export default function Gate() {
  const [staff, setStaff] = useState(undefined);   // undefined: 확인 중, null: 로그인 필요
  const [token, setToken] = useState('');
  const [result, setResult] = useState(null);
  const [history, setHistory] = useState([]);

  const check = useCallback(() => get('/api/staff/me').then(setStaff).catch(() => setStaff(null)), []);
  useEffect(() => { check(); }, [check]);

  const [camera, setCamera] = useState(false);
  const [cameraError, setCameraError] = useState('');
  const video = useRef(null);

  const submit = useCallback(async (value) => {
    const qrToken = value.trim();
    if (!qrToken) return;
    try {
      const r = await post('/api/staff/scan', { qrToken });
      setResult(r);
      setHistory((h) => [{ ...r, at: new Date().toISOString(), token: qrToken.slice(0, 8) }, ...h].slice(0, 10));
      setToken('');
    } catch (err) { setResult({ success: false, reason: err.message }); }
  }, []);

  const scan = (e) => { e.preventDefault(); submit(token); };

  // 카메라 스캔 — 화면에 QR이 잡히면 토큰을 읽어 바로 확인한다. 같은 QR은 3초 동안 다시 보내지 않는다
  useEffect(() => {
    if (!camera) return undefined;
    let stream; let timer; let stopped = false;
    let last = ''; let lastAt = 0;
    const canvas = document.createElement('canvas');
    (async () => {
      try {
        if (!navigator.mediaDevices?.getUserMedia) throw new Error('이 주소에서는 카메라를 쓸 수 없습니다 (localhost 또는 https에서만 가능)');
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false });
        if (stopped) { stream.getTracks().forEach((t) => t.stop()); return; }
        video.current.srcObject = stream;
        await video.current.play();
        timer = setInterval(() => {
          const v = video.current;
          if (!v || !v.videoWidth) return;
          canvas.width = v.videoWidth; canvas.height = v.videoHeight;
          const ctx = canvas.getContext('2d', { willReadFrequently: true });
          ctx.drawImage(v, 0, 0);
          const found = jsQR(ctx.getImageData(0, 0, canvas.width, canvas.height).data, canvas.width, canvas.height);
          if (!found?.data) return;
          if (found.data === last && Date.now() - lastAt < 3000) return;
          last = found.data; lastAt = Date.now();
          submit(found.data);
        }, 200);
      } catch (err) {
        setCameraError(err.name === 'NotAllowedError' ? '카메라 권한이 거부되었습니다. 주소창의 카메라 아이콘에서 허용해 주세요.' : (err.message || '카메라를 열 수 없습니다'));
        setCamera(false);
      }
    })();
    return () => { stopped = true; clearInterval(timer); stream?.getTracks().forEach((t) => t.stop()); };
  }, [camera, submit]);

  if (staff === undefined) return <div className="boot">확인 중…</div>;
  if (staff === null) {
    return (
      <LoginForm title="게이트 스태프 로그인" path="/api/staff/login" defaultId="staff" onDone={setStaff}
        hint="비밀번호는 data/local-secrets.json 에 있습니다.">
        <Link to="/">사용자 화면</Link>
      </LoginForm>
    );
  }

  return (
    <div className="gate">
      <header className="admin-top">
        <div className="logo"><span className="logo-mark dark">F</span><span className="logo-text">Fair<b>Ticket</b> <small>입장 게이트</small></span></div>
        <span className="muted small">{staff.name}</span>
        <button className="link" onClick={async () => { await post('/api/staff/logout'); setStaff(null); }}>로그아웃</button>
      </header>
      <div className="container narrow">
        <div className="card">
          <h3>QR 스캔</h3>
          <p className="muted small">관객의 입장 QR을 카메라에 비추거나 토큰을 붙여 넣으세요. 티켓 해시값을 FairChain 노드 기록과 대조합니다.</p>
          <div className="row gap" style={{ marginBottom: 12 }}>
            <button type="button" className={camera ? 'btn ghost' : 'btn'} onClick={() => { setCameraError(''); setCamera((c) => !c); }}>
              {camera ? '카메라 끄기' : '카메라로 스캔'}
            </button>
            {camera && <span className="muted small">QR을 화면 가운데에 비추세요</span>}
          </div>
          {cameraError && <div className="error">{cameraError}</div>}
          {camera && <video ref={video} className="gate-camera" muted playsInline />}
          <form className="row gap" onSubmit={scan}>
            <input className="grow mono" value={token} onChange={(e) => setToken(e.target.value)} placeholder="qr 토큰" autoFocus />
            <button className="btn">확인</button>
          </form>
        </div>
        {result && (
          <div className={`card scan-result ${result.success ? 'ok' : 'bad'}`}>
            <div className="scan-mark">{result.success ? '✓' : '✕'}</div>
            <h2>{result.success ? '입장 허용' : '입장 거절'}</h2>
            {result.success ? <p>{result.concertTitle} · {result.seatNumber}</p> : <p>{result.reason}</p>}
            <p className="muted small">{result.success ? '블록체인 기록과 일치 — 입장 기록을 FairChain에 보냈습니다' : '블록체인 기록과 대조한 결과입니다'}</p>
          </div>
        )}
        {history.length > 0 && (
          <div className="card">
            <h3>최근 스캔</h3>
            <table className="table small"><thead><tr><th>시각</th><th>토큰</th><th>결과</th><th>내용</th></tr></thead>
              <tbody>{history.map((h, i) => (
                <tr key={i}><td>{when(h.at)}</td><td className="mono">{h.token}…</td>
                  <td><span className={`badge ${h.success ? 'ok' : 'bad'}`}>{h.success ? '허용' : '거절'}</span></td>
                  <td>{h.success ? `${h.concertTitle} · ${h.seatNumber}` : h.reason}</td></tr>
              ))}</tbody></table>
          </div>
        )}
      </div>
    </div>
  );
}
