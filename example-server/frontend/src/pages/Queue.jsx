import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { del, fetchConcert, getWithin, post } from '../api.js';
import { useAuth } from '../auth.jsx';
import Icon from '../components/Icon.jsx';
import Poster from '../components/Poster.jsx';
import { useRequireLogin } from '../hooks.js';
import { when } from '../utils.js';

/** 상태 확인 하나를 기다리는 최대 시간 — 넘으면 끊고 다시 잡는다 (서버가 바쁠 때 요청이 쌓이지 않게) */
const ASK_TIMEOUT_MS = 5000;

/**
 * 대기열 — FairChain이 주는 순번을 보여 주고, 입장이 허용되면 좌석 선택으로.
 * 다음 확인은 서버가 알려 준 간격(nextPollMs)마다: 앞 순번은 1초, 뒤 순번은 최대 15초. 대기 인원이 많아도 서버를 덮지 않는다.
 * 답을 받은 뒤에야 다음 확인을 잡으므로 요청이 겹쳐 쌓이지 않는다. 순번 변화는 SSE로도 밀려 온다.
 */
export default function Queue() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { toast } = useAuth();
  const loggedIn = useRequireLogin(`/queue/${id}`);
  const [concert, setConcert] = useState(null);
  const [status, setStatus] = useState(null);
  const [error, setError] = useState('');
  const [slow, setSlow] = useState(false);
  const [initialRank, setInitialRank] = useState(null);
  const es = useRef(null);

  useEffect(() => {
    if (!loggedIn) return undefined;
    let alive = true;
    let done = false;          // 입장 허용 · 만료 · 줄에 없음 — 더 묻지 않는다
    let timer = null;
    let nextMs = 2000;         // 서버가 알려 준 다음 확인 간격
    fetchConcert(id).then((c) => alive && setConcert(c)).catch(() => {});

    const apply = (s) => {
      if (!alive || done || !s) return;
      setStatus(s);
      setSlow(false);
      if (s.nextPollMs) nextMs = s.nextPollMs;
      setInitialRank((r) => (r == null && s.rank != null ? s.rank : r));
      if (s.state === 'ADMITTED') {
        done = true;
        toast('입장이 허용되었습니다 — 좌석을 고르세요');
        navigate(`/seats/${id}`, { replace: true });
      } else if (s.state === 'EXPIRED') {
        done = true;
        setError('입장 시간이 지나 순번이 만료되었습니다. 다시 줄을 서 주세요.');
      } else if (s.state === 'NOT_FOUND') {
        done = true;
        setError('대기열에 없습니다. 다시 줄을 서 주세요.');
      }
    };

    // SSE가 이어져 있으면 서버가 같은 간격으로 밀어 주므로 직접 확인은 드물게 (스트림이 조용히 멈춰도 입장 허용을 놓치지 않을 만큼만)
    const later = (ms) => (es.current ? Math.max(ms * 3, 6000) : ms);
    const schedule = (ms) => {
      if (alive && !done) timer = setTimeout(ask, later(ms));
    };
    async function ask() {
      try {
        apply(await getWithin(`/api/queue/${id}/status`, ASK_TIMEOUT_MS));
        schedule(nextMs);
      } catch {
        if (!alive) return;
        setSlow(true);                                   // 순번은 서버에 그대로 있다 — 조금 쉬었다 다시
        schedule(Math.min(Math.max(nextMs, 2000) * 2, 15000));
      }
    }

    (async () => {
      try {
        apply(await post(`/api/queue/${id}`));    // 이미 줄에 있으면 현재 상태를 돌려준다
      } catch (e) { if (alive) setError(e.message); return; }
      if (done) return;
      try {
        es.current = new EventSource(`/api/queue/${id}/subscribe`);
        es.current.addEventListener('status', (ev) => apply(JSON.parse(ev.data)));
        es.current.onerror = () => { es.current?.close(); es.current = null; };
      } catch { /* 직접 확인만으로 */ }
      schedule(nextMs);
    })();
    return () => { alive = false; clearTimeout(timer); es.current?.close(); };
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
            {slow && <div className="waitroom-slow">접속이 몰려 순번 확인이 늦어지고 있습니다 — 순번은 그대로 유지됩니다</div>}
            <div className="meter big"><i style={{ width: `${progress}%` }} /></div>
            <ul className="waitroom-notes">
              <li><Icon name="shield" size={17} /> 순번은 FairChain이 매깁니다. 매크로와 반복 요청은 걸러집니다.</li>
              <li><Icon name="check" size={17} /> 순번이 되면 자동으로 좌석 선택 화면으로 넘어갑니다.</li>
              <li><Icon name="info" size={17} /> 새로고침해도 순번은 그대로 유지됩니다.</li>
              <li><Icon name="clock" size={17} /> 순번이 가까울수록 자주(1초), 멀수록 천천히(최대 15초) 새로 고칩니다.</li>
            </ul>
            <button className="linkbtn" onClick={leave}>대기열 나가기</button>
          </div>
        )}
      </div>
    </div>
  );
}
