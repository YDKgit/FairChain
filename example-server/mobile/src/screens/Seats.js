// 좌석 선택 · 예매. offerToken이 있으면 취소표 재배정 제안으로, 제안받은 취소표만 고를 수 있다
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { fetchConcert, get, post } from '../api';
import { useApp, useNow, usePolling } from '../app';
import { Button, Card, ErrorBox, Loading, VerifyBadge } from '../components/ui';
import { parseDate, remaining, splitSeat, when, won } from '../lib/utils';
import { C, gradeColor } from '../theme';

export default function Seats({ id, offerToken }) {
  const { nav, toast } = useApp();
  const [concert, setConcert] = useState(null);
  const [seats, setSeats] = useState([]);
  const [offer, setOffer] = useState(null);
  const [selected, setSelected] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [doneId, setDoneId] = useState(null);
  const now = useNow();

  const load = useCallback(async () => {
    try {
      const [c, s] = await Promise.all([fetchConcert(id), get(`/api/concerts/${id}/seats`)]);
      setConcert(c); setSeats(s.seats || []); setError('');
      if (offerToken) {
        const offers = await get('/api/reallocation/offers');
        setOffer(offers.find((o) => o.offerToken === offerToken) || null);
      }
    } catch (e) { setError(e.message); }
  }, [id, offerToken]);
  usePolling(load, 4000, !doneId);

  const offerSeatIds = useMemo(() => new Set((offer?.seats || []).map((s) => s.seatId)), [offer]);
  const selectable = (s) => (offerToken ? offerSeatIds.has(s.seatId) && s.status === 'CANCELLED' : s.status === 'EMPTY');
  const chosen = seats.find((s) => s.seatId === selected) || null;
  const price = chosen ? (concert?.seatGrades || []).find((g) => g.gradeName === chosen.gradeName)?.price : null;

  // 고른 좌석을 다른 사람이 먼저 가져가면 선택을 푼다
  useEffect(() => { if (chosen && !selectable(chosen)) setSelected(null); });

  const rows = useMemo(() => {
    const map = new Map();
    seats.forEach((s) => {
      const { row, num } = splitSeat(s.seatNumber);
      if (!map.has(row)) map.set(row, []);
      map.get(row).push({ ...s, num });
    });
    return [...map.entries()].map(([row, list]) => [row, list.sort((a, b) => a.num - b.num)]).sort((a, b) => a[0].localeCompare(b[0]));
  }, [seats]);

  const reserve = async () => {
    if (!chosen) return;
    setBusy(true);
    try {
      const result = await post('/api/reservations', { seatId: chosen.seatId, concertId: Number(id), offerToken: offerToken || null });
      if (result.success) setDoneId(result.ticketId);
      else { toast(result.reason || '예매하지 못했습니다', 'bad'); setSelected(null); load(); }
    } catch (e) { toast(e.message, 'bad'); load(); }
    setBusy(false);
  };

  if (doneId) return <Done ticketId={doneId} />;
  if (error && !concert) return <View style={st.page}><ErrorBox message={error} onRetry={load} /></View>;
  if (!concert) return <Loading />;

  const expired = !!offer && (parseDate(offer.expiresAt)?.getTime() ?? 0) < now;

  return (
    <View style={{ flex: 1 }}>
      <ScrollView contentContainerStyle={st.page}>
        <View>
          <Text style={st.title}>{concert.title}</Text>
          <Text style={st.muted}>{concert.venue} · {when(concert.eventStart)}</Text>
        </View>

        {offerToken && (
          offer ? (
            <View style={st.offer}>
              <Text style={st.offerTitle}>취소표 재배정 제안</Text>
              <Text style={st.offerText}>아래 취소표 {offer.seats.length}장 중 하나를 고르세요.</Text>
              <Text style={st.offerClock}>{expired ? '기한이 지났습니다' : `남은 시간 ${remaining(offer.expiresAt, now)}`}</Text>
            </View>
          ) : <ErrorBox message="이 제안은 더 이상 유효하지 않습니다 (기한이 지났거나 이미 사용했습니다)." />
        )}

        <Card style={{ gap: 12 }}>
          <View style={st.legendRow}>
            {(concert.seatGrades || []).map((g) => (
              <View key={g.id} style={st.legend}>
                <View style={[st.dot, { backgroundColor: gradeColor(g.gradeName) }]} />
                <Text style={st.legendText}>{g.gradeName}석 {won(g.price)}</Text>
              </View>
            ))}
          </View>
          <View style={st.stage}><Text style={st.stageText}>STAGE</Text></View>
          {!seats.length && <Text style={st.muted}>등록된 좌석이 없습니다.</Text>}
          <ScrollView horizontal showsHorizontalScrollIndicator={false}>
            <View style={{ gap: 7 }}>
              {rows.map(([row, list]) => (
                <View key={row} style={st.seatRow}>
                  <Text style={st.rowName}>{row}</Text>
                  {list.map((s) => {
                    const can = selectable(s);
                    const on = s.seatId === selected;
                    const held = s.status === 'CANCELLED';
                    return (
                      <Pressable
                        key={s.seatId}
                        disabled={!can}
                        onPress={() => setSelected(on ? null : s.seatId)}
                        style={[
                          st.seat,
                          { borderBottomColor: gradeColor(s.gradeName) },
                          !can && (held ? st.seatHeld : st.seatTaken),
                          on && st.seatOn,
                        ]}>
                        <Text style={[st.seatText, !can && !held && { color: '#b9b8c6' }, on && { color: '#fff' }]}>{s.num}</Text>
                      </Pressable>
                    );
                  })}
                </View>
              ))}
            </View>
          </ScrollView>
          <View style={st.legendRow}>
            <Key style={st.seat} text="예매 가능" />
            <Key style={[st.seat, st.seatTaken]} text="예매됨" />
            <Key style={[st.seat, st.seatHeld]} text="취소표(재배정 중)" />
            <Key style={[st.seat, st.seatOn]} text="선택" />
          </View>
        </Card>
        <Text style={st.muted}>1인 1매 · 같은 좌석을 동시에 고르면 한 명만 성공합니다. 시연용이라 실제 결제는 없습니다.</Text>
      </ScrollView>

      <View style={st.bar}>
        <View style={{ flex: 1 }}>
          <Text style={st.muted}>{chosen ? `${chosen.gradeName}석 ${chosen.seatNumber}` : '좌석을 골라 주세요'}</Text>
          <Text style={st.total}>{chosen ? won(price) : '-'}</Text>
        </View>
        <Button
          title={offerToken ? '취소표 예매' : '결제하고 예매'}
          style={{ flex: 1 }}
          disabled={!chosen || (offerToken && (!offer || expired))}
          busy={busy}
          onPress={reserve}
        />
      </View>
    </View>
  );
}

function Key({ style, text }) {
  return <View style={st.legend}><View style={[style, { width: 18, height: 18, borderRadius: 5 }]} /><Text style={st.legendText}>{text}</Text></View>;
}

/** 예매 완료 — 블록이 확정될 때까지 티켓을 다시 읽어 "검증 완료"로 바뀌는 것을 보여 준다 */
function Done({ ticketId }) {
  const { nav } = useApp();
  const [ticket, setTicket] = useState(null);
  const verified = ticket?.verificationStatus === 'VERIFIED';
  usePolling(async () => {
    try { setTicket(await get(`/api/tickets/${ticketId}`)); } catch { /* 다음 주기에 다시 */ }
  }, 1500, !verified);

  return (
    <View style={[st.page, { flex: 1, justifyContent: 'center' }]}>
      <Card style={{ gap: 14, alignItems: 'center', paddingVertical: 28 }}>
        <View style={st.check}><Text style={st.checkText}>✓</Text></View>
        <Text style={st.doneTitle}>예매가 완료되었습니다</Text>
        {ticket ? (
          <>
            <Text style={st.doneConcert}>{ticket.concertTitle}</Text>
            <Text style={st.muted}>{ticket.seatGrade}석 {ticket.seatNumber} · {won(ticket.price)}</Text>
            <VerifyBadge status={ticket.verificationStatus} />
            <Text style={[st.muted, { textAlign: 'center' }]}>
              {verified
                ? `블록 #${ticket.blockIndex ?? '-'}에 기록되었습니다. 이제 입장할 수 있습니다.`
                : '노드들이 이 티켓의 기록을 확정하는 중입니다. 몇 초 걸립니다.'}
            </Text>
          </>
        ) : <Text style={st.muted}>티켓을 불러오는 중…</Text>}
      </Card>
      <Button title="내 티켓 보기" onPress={() => nav.home('tickets')} />
      <Button title="홈으로" kind="ghost" onPress={() => nav.home('home')} />
    </View>
  );
}

const st = StyleSheet.create({
  page: { padding: 16, gap: 12, paddingBottom: 24 },
  title: { color: C.ink, fontSize: 19, fontWeight: '800' },
  muted: { color: C.sub, fontSize: 13, lineHeight: 19 },
  offer: { backgroundColor: C.warnSoft, borderRadius: 14, padding: 14, gap: 4 },
  offerTitle: { color: C.warn, fontWeight: '800', fontSize: 15 },
  offerText: { color: '#78350f', fontSize: 13 },
  offerClock: { color: C.warn, fontWeight: '800', fontSize: 18, fontVariant: ['tabular-nums'] },
  legendRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 12 },
  legend: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  legendText: { color: C.sub, fontSize: 12 },
  dot: { width: 10, height: 10, borderRadius: 5 },
  stage: { backgroundColor: C.dark, borderRadius: 10, paddingVertical: 8, alignItems: 'center' },
  stageText: { color: '#fff', fontSize: 11, fontWeight: '800', letterSpacing: 4 },
  seatRow: { flexDirection: 'row', gap: 6, alignItems: 'center' },
  rowName: { width: 18, color: C.sub, fontSize: 12, fontWeight: '700' },
  seat: { width: 34, height: 34, borderRadius: 9, backgroundColor: C.primarySoft, borderBottomWidth: 3, borderBottomColor: C.primary, alignItems: 'center', justifyContent: 'center' },
  seatTaken: { backgroundColor: '#ececf2', borderBottomColor: '#d9d8e3' },
  seatHeld: { backgroundColor: C.warnSoft, borderBottomColor: '#f59e0b' },
  seatOn: { backgroundColor: C.primary, borderBottomColor: '#3b21c9' },
  seatText: { color: C.primary, fontSize: 12, fontWeight: '700' },
  bar: { flexDirection: 'row', gap: 12, alignItems: 'center', padding: 12, paddingBottom: 14, backgroundColor: C.card, borderTopWidth: 1, borderTopColor: C.line },
  total: { color: C.ink, fontSize: 19, fontWeight: '800' },
  check: { width: 64, height: 64, borderRadius: 32, backgroundColor: C.okSoft, alignItems: 'center', justifyContent: 'center' },
  checkText: { color: C.ok, fontSize: 32, fontWeight: '800' },
  doneTitle: { color: C.ink, fontSize: 21, fontWeight: '800' },
  doneConcert: { color: C.ink, fontSize: 15, fontWeight: '700', textAlign: 'center' },
});
