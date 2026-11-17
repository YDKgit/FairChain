import { when } from '../../utils.js';

/** 전체 판매 현황 + FairChain 연동 정보 · 운영자용 FairChain 화면 링크 */
export default function Dashboard({ concerts, keys, fairChainUrl }) {
  const sold = concerts.reduce((a, c) => a + ((c.totalSeats || 0) - (c.emptySeats || 0)), 0);
  const total = concerts.reduce((a, c) => a + (c.totalSeats || 0), 0);
  return (
    <>
      <div className="stats cards">
        <div className="card"><span className="stat-no">{concerts.length}</span><span className="stat-label">공연</span></div>
        <div className="card"><span className="stat-no">{total}</span><span className="stat-label">등록 좌석</span></div>
        <div className="card"><span className="stat-no">{sold}</span><span className="stat-label">예매된 좌석</span></div>
        <div className="card"><span className="stat-no">{total ? Math.round((sold / total) * 100) : 0}%</span><span className="stat-label">판매율</span></div>
      </div>
      <div className="cols">
        <div className="card">
          <h3>FairChain 연동</h3>
          {keys?.error ? <p className="error">{keys.error}</p> : (
            <table className="kv small"><tbody>
              <tr><th>API Key</th><td className="mono">{(keys?.apiKey || '').slice(0, 8)}… (서버 설정 FAIRCHAIN_API_KEY)</td></tr>
              <tr><th>대시보드 토큰</th><td className="mono">{keys?.dashboardToken || '-'}</td></tr>
              <tr><th>FairChain</th><td className="mono">{fairChainUrl}</td></tr>
            </tbody></table>
          )}
          <div className="row gap wrap" style={{ marginTop: 12 }}>
            <a className="btn small" target="_blank" rel="noreferrer" href={`${fairChainUrl}/dashboard.html`}>관제 대시보드</a>
            <a className="btn small ghost" target="_blank" rel="noreferrer" href={`${fairChainUrl}/inspector.html`}>점검 화면 (요청 · DB · 블록)</a>
            <a className="btn small ghost" target="_blank" rel="noreferrer" href={`${fairChainUrl}/demo.html`}>노드 시연 화면</a>
          </div>
        </div>
        <div className="card">
          <h3>공연별 판매 현황</h3>
          <table className="table"><thead><tr><th>공연</th><th>공연일</th><th>예매</th><th>잔여</th><th>상태</th></tr></thead>
            <tbody>
              {concerts.map((c) => (
                <tr key={c.id}>
                  <td>{c.title}</td><td>{when(c.eventStart)}</td>
                  <td>{(c.totalSeats || 0) - (c.emptySeats || 0)}</td><td>{c.emptySeats ?? '-'}</td>
                  <td>{c.totalSeats > 0 && c.emptySeats === 0 ? <span className="badge bad">매진</span> : c.open ? <span className="badge ok">예매중</span> : <span className="badge wait">오픈 전</span>}</td>
                </tr>
              ))}
            </tbody></table>
        </div>
      </div>
    </>
  );
}
