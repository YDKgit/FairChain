import { useCallback, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { get, post } from '../api.js';
import { useAuth } from '../auth.jsx';
import { useConcerts, useRequireLogin, usePolling } from '../hooks.js';
import { ErrorBox, Loading } from '../components/PageState.jsx';
import Icon from '../components/Icon.jsx';
import { TicketCard, TicketModal } from '../components/Ticket.jsx';
import { when } from '../utils.js';

const TABS = [['active', '사용 가능'], ['used', '입장 완료'], ['cancelled', '취소']];

/** 내 티켓 — 예제서버 티켓과 FairChain · 노드 기록을 대조한 결과를 함께 보여 준다 */
export default function MyTickets() {
  const loggedIn = useRequireLogin('/tickets');
  const { me, config, toast } = useAuth();
  const { concerts } = useConcerts();
  const [data, setData] = useState(null);
  const [tab, setTab] = useState('active');
  const [openId, setOpenId] = useState(null);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try { setData(await get('/api/tickets')); } catch (e) { setError(e.message); }
  }, []);
  usePolling(load, 4000, loggedIn);     // 블록 기록 중 → 검증 완료로 바뀌는 것을 보여 주기 위해

  const concertOf = useMemo(() => {
    const byId = new Map(concerts.map((c) => [c.id, c]));
    return (t) => byId.get(t.concertId) || concerts.find((c) => c.title === t.concertTitle) || null;
  }, [concerts]);

  const cancel = async (t) => {
    const r = config.reallocation;
    const msg = `${t.concertTitle} ${t.seatNumber} 티켓을 취소할까요?\n\n매진된 공연이면 이 좌석은 바로 풀리지 않고 매일 ${r.offerTime}에 대기 순번대로 재배정됩니다.`;
    if (!window.confirm(msg)) return;
    try {
      await post(`/api/tickets/${t.ticketId}/cancel`);
      toast('취소했습니다 — FairChain에 취소 기록을 보냈습니다');
      load();
    } catch (e) { toast(e.message, 'error'); }
  };

  if (error) return <ErrorBox message={error} />;
  if (!data) return <Loading />;

  const groups = {
    active: data.tickets.filter((t) => t.status !== 'CANCELLED' && t.status !== 'CHECKED_IN'),
    used: data.tickets.filter((t) => t.status === 'CHECKED_IN'),
    cancelled: data.tickets.filter((t) => t.status === 'CANCELLED'),
  };
  const shown = groups[tab];
  const opened = data.tickets.find((t) => t.ticketId === openId) || null;   // 열어 둔 티켓도 검증 상태가 따라 바뀐다

  return (
    <div className="wrap mypage">
      <header className="mypage-head">
        <div>
          <h1>내 티켓</h1>
          <p className="muted">{me?.name} 님의 예매 내역입니다. 티켓마다 블록체인 노드에 남은 기록과 대조한 결과가 표시됩니다.</p>
        </div>
        <div className="mypage-counts">
          <div><b>{groups.active.length}</b><span>사용 가능</span></div>
          <div><b>{groups.used.length}</b><span>입장 완료</span></div>
          <div><b>{groups.cancelled.length}</b><span>취소</span></div>
        </div>
      </header>

      {!data.verificationAvailable && (
        <div className="notice warn"><Icon name="info" size={18} /> 지금은 노드에 물어볼 수 없어 예매 사이트의 티켓만 보여 드립니다.</div>
      )}

      <div className="tabbar">
        {TABS.map(([k, label]) => <button key={k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{label} <em>{groups[k].length}</em></button>)}
      </div>

      {tab === 'cancelled' ? (
        shown.length ? (
          <table className="table cancelled-table">
            <thead><tr><th>공연</th><th>좌석</th><th>예매</th><th>블록체인 기록</th></tr></thead>
            <tbody>
              {shown.map((t) => (
                <tr key={t.ticketId}>
                  <td>{t.concertTitle}</td><td>{t.seatGrade}석 {t.seatNumber}</td><td>{when(t.issuedAt)}</td>
                  <td className="mono">v{t.version}{t.blockIndex != null && ` · 블록 #${t.blockIndex}`}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : <div className="empty">취소한 티켓이 없습니다.</div>
      ) : shown.length ? (
        <div className="tk-list">
          {shown.map((t, i) => (
            <TicketCard key={t.ticketId ?? `m${i}`} ticket={t} concert={concertOf(t)} onOpen={(x) => setOpenId(x.ticketId)} onCancel={cancel} />
          ))}
        </div>
      ) : (
        <div className="empty">
          {tab === 'active' ? <>사용 가능한 티켓이 없습니다. <Link to="/concerts" className="textlink">공연 보러 가기</Link></> : '입장한 티켓이 없습니다.'}
        </div>
      )}

      <TicketModal ticket={opened} concert={opened ? concertOf(opened) : null} onClose={() => setOpenId(null)} />
    </div>
  );
}
