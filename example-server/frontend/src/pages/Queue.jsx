import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { del, fetchConcert, get, post } from '../api.js';
import { useAuth } from '../auth.jsx';
import Icon from '../components/Icon.jsx';
import Poster from '../components/Poster.jsx';
import { useRequireLogin } from '../hooks.js';
import { when } from '../utils.js';

/** 대기열 — FairChain이 주는 순번을 SSE로 받아 보여 주고, 입장이 허용되면 좌석 선택으로 */
export default function Queue() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { toast } = useAuth();
  const loggedIn = useRequireLogin(`/queue/${id}`);
  const [concert, setConcert] = useState(null);
  const [status, setStatus] = useState(null);
  const [error, setError] = useState('');
  const [initialRank, setInitialRank] = useState(null);
  const es = useRef(null);

  useEffect(() => {
    if (!loggedIn) return undefined;
    let alive = true;
    fetchConcert(id).then((c) => alive && setConcert(c)).catch(() => {});

    const apply = (s) => {
      if (!alive) return;
      setStatus(s);
      setInitialRank((r) => (r == null && s.rank != null ? s.rank : r));
      if (s.state === 'ADMITTED') {
        toast('입장이 허용되었습니다 — 좌석을 고르세요');
        navigate(`/seats/${id}`, { replace: true });
      } else if (s.state === 'EXPIRED') {
        setError('입장 시간이 지나 순번이 만료되었습니다. 다시 줄을 서 주세요.');
      } else if (s.state === 'NOT_FOUND') {
        setError('대기열에 없습니다. 다시 줄을 서 주세요.');
      }
    };

    (async () => {
      try {
        apply(await post(`/api/queue/${id}`));    // 이미 줄에 있으면 현재 상태를 돌려준다
      } catch (e) { if (alive) setError(e.message); return; }
      // 순번 변화는 서버가 밀어 준다 (SSE)
      try {
        es.current = new EventSource(`/api/queue/${id}/subscribe`);
        es.current.addEventListener('status', (ev) => apply(JSON.parse(ev.data)));
        es.current.onerror = () => { es.current?.close(); es.current = null; };
      } catch { /* 폴링으로 */ }
    })();
    // SSE가 끊기면 2초마다, 이어져 있어도 6초마다 상태를 직접 물어본다.
    // 서버 사이의 순번 스트림이 조용히 멈춰도 입장 허용을 놓치지 않기 위해서다
    let tick = 0;
    const poll = setInterval(() => {
      tick += 1;
      if (!es.current || tick % 3 === 0) get(`/api/queue/${id}/status`).then(apply).catch(() => {});
    }, 2000);
    return () => { alive = false; clearInterval(poll); es.current?.close(); };
  }, [id, loggedIn, navigate, toast]);

  const leave = async () => {
    try { await del(`/api/queue/${id}`); toast('대기열에서 나왔습니다'); navigate(`/concert/${id}`); }
    catch (e) { toast(e.message, 'error'); }
  };

  const rank = status?.rank ?? null;
  const total = status?.waitingCount ?? null;
  const progress = initialRank && rank != null ? Math.min(100, Math.round(((initialRank - rank) / initialRank) * 100)) : 0;

  return (
    <div className="wrap narrow">
      <div className="waitroom">
        {concert && (
          <div className="waitroom-concert">
            <Poster concert={concert} size="xs" bare />
            <div><strong>{concert.title}</strong><span>{concert.venue} · {when(concert.eventStart)}</span></div>
          </div>
        )}
        {error ? (
          <div className="waitroom-body">
            <h1>대기열에 다시 들어가야 합니다</h1>
            <div className="error">{error}</div>
            <div className="row gap center">
              <button className="btn" onClick={() => window.location.reload()}>다시 줄 서기</button>
              <Link className="btn ghost" to={`/concert/${id}`}>공연으로</Link>
            </div>
          </div>
        ) : (
          <div className="waitroom-body">
            <span className="waitroom-kicker"><span className="spinner" /> 접속 대기 중</span>
            <h1>잠시만 기다려 주세요</h1>
            <div className="waitroom-rank">
              <span>내 대기 순번</span>
              <b>{rank ?? '…'}</b>
              <span>앞에 <em>{rank != null ? Math.max(0, rank - 1) : '…'}</em>명 · 전체 대기 <em>{total ?? '…'}</em>명</span>
            </div>
            <div className="meter big"><i style={{ width: `${progress}%` }} /></div>
            <ul className="waitroom-notes">
              <li><Icon name="shield" size={17} /> 순번은 FairChain이 매깁니다. 매크로와 반복 요청은 걸러집니다.</li>
              <li><Icon name="check" size={17} /> 순번이 되면 자동으로 좌석 선택 화면으로 넘어갑니다.</li>
              <li><Icon name="info" size={17} /> 새로고침해도 순번은 그대로 유지됩니다.</li>
            </ul>
            <button className="linkbtn" onClick={leave}>대기열 나가기</button>
          </div>
        )}
      </div>
    </div>
  );
}
