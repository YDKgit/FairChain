import { useCallback, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { fetchConcert, get } from '../api.js';
import { useAuth } from '../auth.jsx';
import Poster from '../components/Poster.jsx';
import { SaleBadge } from '../components/Badge.jsx';
import Countdown from '../components/Countdown.jsx';
import Icon from '../components/Icon.jsx';
import { ErrorBox, Loading } from '../components/PageState.jsx';
import { usePolling } from '../hooks.js';
import { offerStatusText } from '../labels.js';
import { bookedRate, clock, dateParts, day, dday, minutesText, saleState, when, won } from '../utils.js';

const TABS = [['info', '공연 정보'], ['guide', '예매 · 취소 안내'], ['chain', '티켓 검증']];

export default function ConcertDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { me, config, toast } = useAuth();
  const [concert, setConcert] = useState(null);
  const [seats, setSeats] = useState([]);
  const [realloc, setRealloc] = useState(null);
  const [myTicket, setMyTicket] = useState(null);
  const [tab, setTab] = useState('info');
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      setConcert(await fetchConcert(id));
      setSeats(await get(`/api/concerts/${id}/seats`).then((s) => s.seats).catch(() => []));
      setRealloc(await get(`/api/reallocation/concerts/${id}`).catch(() => null));
      if (me) {
        const mine = await get(`/api/tickets?eventId=${id}`).catch(() => ({ tickets: [] }));
        setMyTicket(mine.tickets.find((t) => t.ticketId && t.status !== 'CANCELLED') || null);
      } else {
        setMyTicket(null);
      }
    } catch (e) { setError(e.message); }
  }, [id, me]);

  usePolling(load, 5000);

  // 등급별 남은 좌석 수
  const grades = useMemo(() => (concert?.seatGrades || []).map((g) => {
    const mine = seats.filter((s) => s.gradeName === g.gradeName);
    return { ...g, total: mine.length, left: mine.filter((s) => s.status === 'EMPTY').length };
  }), [concert, seats]);

  if (error) return <ErrorBox message={error} />;
  if (!concert) return <Loading />;

  const state = saleState(concert);
  const r = config.reallocation;
  const date = dateParts(concert.eventStart);
  const rate = bookedRate(concert);

  const book = () => {
    if (!me) { navigate(`/login?next=/concert/${id}`); return; }
    if (myTicket) { toast('이미 이 공연의 티켓을 가지고 있습니다 (1인 1매)'); return; }
    // 대기열 진입 요청은 대기열 화면이 한다 (여기서도 보내면 같은 요청이 두 번 나간다)
    navigate(config.queueEnabled ? `/queue/${id}` : `/seats/${id}`);
  };

  const showRealloc = realloc && (state === 'soldout' || realloc.heldCount > 0 || realloc.roundStatus);

  return (
    <div className="wrap detail">
      <nav className="crumbs">
        <Link to="/">홈</Link><Icon name="right" size={13} />
        <Link to={`/concerts?genre=${concert.genre}`}>{concert.genreLabel}</Link><Icon name="right" size={13} />
        <span>{concert.title}</span>
      </nav>

      <div className="detail-grid">
        <div className="detail-main">
          <section className="detail-top">
            <Poster concert={concert} size="lg" />
            <div className="detail-info">
              <div className="detail-tags"><SaleBadge state={state} /><span className="tag">{concert.genreLabel}</span><span className="tag">{dday(concert.eventStart)}</span></div>
              <h1>{concert.title}</h1>
              {concert.tagline && <p className="detail-tagline">{concert.tagline}</p>}
              <dl className="facts">
                <div><dt>장소</dt><dd>{concert.venue}</dd></div>
                <div><dt>공연일시</dt><dd>{day(concert.eventStart)} {clock(concert.eventStart)} ~ {clock(concert.eventEnd)}</dd></div>
                <div><dt>공연시간</dt><dd>{minutesText(concert.runtime)}</dd></div>
                <div><dt>관람연령</dt><dd>{concert.age}</dd></div>
                {concert.artist && <div><dt>출연</dt><dd>{concert.artist}</dd></div>}
                <div><dt>예매오픈</dt><dd>{when(concert.eventOpen)}</dd></div>
                <div><dt>가격</dt><dd>
                  <ul className="price-list">
                    {concert.seatGrades.map((g) => <li key={g.id}><i className={`dot grade-${g.gradeName.toLowerCase()}`} />{g.gradeName}석 <b>{won(g.price)}</b></li>)}
                  </ul>
                </dd></div>
                <div><dt>예매 제한</dt><dd>1인 1매 · 대기열 순번대로 입장</dd></div>
              </dl>
            </div>
          </section>

          {showRealloc && (
            <section className="panel realloc-box">
              <div className="panel-head"><h3><Icon name="swap" /> 취소표 재배정</h3>{realloc.finalWindow && <span className="badge warn">공연 임박 — 바로 판매</span>}</div>
              <div className="stats">
                <div><span className="stat-no">{realloc.heldCount}</span><span className="stat-label">보류 중 취소표</span></div>
                <div><span className="stat-no">{realloc.roundStatus === 'OFFERING' ? `${realloc.currentSequence ?? '-'}번` : '-'}</span><span className="stat-label">지금 제안 중인 순번</span></div>
                <div><span className="stat-no">{realloc.candidateCount ?? '-'}</span><span className="stat-label">이번 회차 명단</span></div>
              </div>
              {realloc.nextOfferAt && <p>다음 제안 시작: <b>{when(realloc.nextOfferAt)}</b></p>}
              {realloc.roundStatus === 'RELEASE_PENDING' && <p>명단을 모두 돌았습니다. 남은 {realloc.heldCount}장은 <b>{when(realloc.releaseAt)}</b>에 일반 판매로 풀립니다.</p>}
              {me && realloc.myOffer && (
                <div className="offer-callout">
                  <b>내 차례입니다!</b> 남은 시간 <Countdown until={realloc.myOffer.expiresAt} onExpire={load} />
                  <Link className="btn small" to={`/seats/${id}?offer=${realloc.myOffer.offerToken}`}>취소표 고르기</Link>
                </div>
              )}
              {me && !realloc.myOffer && realloc.myRank && (
                <p>내 명단 순번: <b>{realloc.myRank}번</b> {realloc.myOfferStatus ? `(${offerStatusText[realloc.myOfferStatus] || realloc.myOfferStatus})` : ''}</p>
              )}
              {me && !realloc.myRank && !realloc.myOffer && state === 'soldout' && (
                <p className="muted small">대기열을 통과하고도 좌석을 못 구한 분이 명단에 오릅니다. 내 순번은 <Link to="/reallocation" className="textlink">취소표 알림</Link>에서 확인하세요.</p>
              )}
            </section>
          )}

          <div className="tabbar">
            {TABS.map(([k, label]) => <button key={k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{label}</button>)}
          </div>

          {tab === 'info' && (
            <section className="panel prose">
              <h3>공연 소개</h3>
              {concert.about.map((p) => <p key={p}>{p}</p>)}
              <h3>좌석 등급</h3>
              <table className="table">
                <thead><tr><th>등급</th><th>가격</th><th>전체</th><th>잔여</th></tr></thead>
                <tbody>
                  {grades.map((g) => (
                    <tr key={g.id}>
                      <td><i className={`dot grade-${g.gradeName.toLowerCase()}`} />{g.gradeName}석</td>
                      <td>{won(g.price)}</td><td>{g.total}석</td>
                      <td>{g.total ? <b className={g.left ? 'accent' : 'muted'}>{g.left}석</b> : <span className="muted">준비중</span>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <h3>유의 사항</h3>
              <ul>
                <li>이 공연은 1인 1매만 예매할 수 있습니다.</li>
                <li>입장은 공연 시작 3시간 전부터 가능하며, 모바일 티켓의 QR을 게이트에서 확인합니다.</li>
                <li>시연용 사이트라 실제 결제는 이루어지지 않습니다.</li>
              </ul>
            </section>
          )}

          {tab === 'guide' && (
            <section className="panel prose">
              <h3>예매 순서</h3>
              <ol className="flow">
                <li><b>예매하기</b><span>버튼을 누르면 FairChain 대기열에 들어갑니다.</span></li>
                <li><b>대기</b><span>순번이 되면 자동으로 좌석 선택 화면으로 넘어갑니다.</span></li>
                <li><b>좌석 선택</b><span>배치도에서 좌석 하나를 고르고 예매합니다.</span></li>
                <li><b>모바일 티켓</b><span>내 티켓에서 QR을 열어 게이트에 보여 줍니다.</span></li>
              </ol>
              <h3>취소와 취소표</h3>
              <ul>
                <li>예매한 티켓은 내 티켓에서 취소할 수 있습니다.</li>
                <li>매진된 공연의 취소표는 바로 풀리지 않고 모아 두었다가 매일 <b>{r.offerTime}</b>에 대기 순번대로 한 사람씩 <b>{r.offerTtlMinutes}분</b>간 제안됩니다.</li>
                <li>명단을 다 돌고도 남은 표는 다음 날 <b>{r.releaseTime}</b>에 일반 판매로 풀립니다.</li>
                <li>공연 {r.finalWindowHours}시간 전부터는 취소표가 나오는 즉시 판매됩니다.</li>
              </ul>
            </section>
          )}

          {tab === 'chain' && (
            <section className="panel prose">
              <h3>이 공연의 티켓은 이렇게 지켜집니다</h3>
              <ol className="flow">
                <li><b>대기열</b><span>예매 오픈 때 FairChain이 순번을 주고 매크로 요청을 걸러 냅니다.</span></li>
                <li><b>블록 기록</b><span>좌석 발행 · 판매 · 취소 · 입장 때마다 티켓의 해시값이 독립 노드에 기록됩니다.</span></li>
                <li><b>검증</b><span>내 티켓 화면과 입장 게이트에서 티켓을 블록체인 기록과 대조합니다.</span></li>
                <li><b>취소표 재배정</b><span>매진 뒤 취소표는 대기 순번대로 제안되어 공정하게 돌아갑니다.</span></li>
              </ol>
              <p className="muted small">블록체인에는 티켓 내용이 아니라 해시값만 남습니다. 예매 사이트의 티켓이 바뀌면 해시값이 달라져 위조로 드러납니다.</p>
            </section>
          )}
        </div>

        <aside className="detail-side">
          <div className="bookbox">
            <div className="bookbox-date">
              <div className="cal"><span>{date.month}월</span><b>{date.date}</b><span>{date.weekday}요일</span></div>
              <div>
                <strong>{clock(concert.eventStart)} 공연</strong>
                <span className="muted">{concert.hall || concert.venue}</span>
              </div>
            </div>

            <ul className="bookbox-grades">
              {grades.map((g) => (
                <li key={g.id}>
                  <span><i className={`dot grade-${g.gradeName.toLowerCase()}`} />{g.gradeName}석</span>
                  <span>{won(g.price)}</span>
                  <span className={g.left ? 'left' : 'left none'}>{g.total ? (g.left ? `${g.left}석` : '매진') : '준비중'}</span>
                </li>
              ))}
            </ul>

            {concert.totalSeats > 0 && (
              <div className="bookbox-rate">
                <div className="meter"><i style={{ width: `${rate}%` }} /></div>
                <span>예매율 {rate}% · 잔여 {concert.emptySeats} / {concert.totalSeats}석</span>
              </div>
            )}

            {myTicket ? (
              <Link to="/tickets" className="btn big full ghost">내 티켓 보기 · {myTicket.seatGrade}석 {myTicket.seatNumber}</Link>
            ) : state === 'open' ? (
              <button className="btn big full" onClick={book}>예매하기</button>
            ) : state === 'upcoming' ? (
              <button className="btn big full" disabled>{when(concert.eventOpen)} 오픈</button>
            ) : state === 'ready' ? (
              <button className="btn big full" disabled>좌석 준비 중</button>
            ) : state === 'soldout' ? (
              <button className="btn big full" disabled>매진</button>
            ) : (
              <button className="btn big full" disabled>공연 종료</button>
            )}

            {config.queueEnabled && state === 'open' && !myTicket && (
              <p className="bookbox-note"><Icon name="queue" size={16} /> 예매하기를 누르면 대기열에 들어가고, 순번이 되면 좌석을 고릅니다.</p>
            )}
            {state === 'soldout' && !myTicket && (
              <p className="bookbox-note"><Icon name="swap" size={16} /> 취소표는 대기 순번대로 제안됩니다. <Link to="/reallocation" className="textlink">취소표 알림</Link></p>
            )}
          </div>
        </aside>
      </div>
    </div>
  );
}
