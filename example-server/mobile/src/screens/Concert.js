// 공연 상세 — 정보 · 가격 · 좌석 현황, 아래 고정된 "예매하기"
import { useCallback, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { fetchConcert, get } from '../api';
import { useApp, usePolling } from '../app';
import { Button, Card, ErrorBox, Loading, Poster, SaleBadge } from '../components/ui';
import { bookedRate, dday, minutesText, saleState, when, won } from '../lib/utils';
import { C, gradeColor } from '../theme';

const CTA = {
  open: '예매하기',
  upcoming: '예매 오픈 전',
  ready: '좌석 준비중',
  soldout: '매진 — 취소표 알림 보기',
  ended: '종료된 공연',
};

export default function Concert({ id }) {
  const { nav, member, config, requireLogin } = useApp();
  const [concert, setConcert] = useState(null);
  const [mine, setMine] = useState(null);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try { setConcert(await fetchConcert(id)); setError(''); }
    catch (e) { setError(e.message); }
    if (member) {
      try { setMine(((await get(`/api/tickets?eventId=${id}`)).tickets || []).find((t) => t.status === 'ACTIVE') || null); }
      catch { /* 티켓 확인은 못 해도 화면은 보여 준다 */ }
    }
  }, [id, member]);
  usePolling(load, 8000);

  if (error && !concert) return <View style={st.page}><ErrorBox message={error} onRetry={load} /></View>;
  if (!concert) return <Loading />;

  const state = saleState(concert);
  const book = () => {
    if (mine) return nav.home('tickets');
    if (state === 'soldout') return nav.home('realloc');
    // 대기열 진입 요청은 대기열 화면이 한다
    return requireLogin(config.queueEnabled ? 'queue' : 'seats', { id });
  };

  return (
    <View style={{ flex: 1 }}>
      <ScrollView contentContainerStyle={st.page}>
        <View style={[st.top, { backgroundColor: concert.palette[0] }]}>
          <Poster concert={concert} width={118} />
          <View style={{ flex: 1, gap: 6 }}>
            <View style={st.row}><SaleBadge concert={concert} /><Text style={st.dday}>{dday(concert.eventStart)}</Text></View>
            <Text style={st.title}>{concert.title}</Text>
            {concert.tagline ? <Text style={st.tagline}>{concert.tagline}</Text> : null}
          </View>
        </View>

        <Card style={{ gap: 10 }}>
          <Info label="일시" value={when(concert.eventStart)} />
          <Info label="장소" value={concert.venue} />
          <Info label="장르" value={concert.genreLabel} />
          <Info label="관람" value={`${minutesText(concert.runtime)} · ${concert.age}`} />
          <Info label="예매 오픈" value={when(concert.eventOpen)} />
        </Card>

        <Card style={{ gap: 12 }}>
          <Text style={st.h2}>가격</Text>
          {(concert.seatGrades || []).map((g) => (
            <View key={g.id} style={st.rowBetween}>
              <View style={st.row}><View style={[st.dot, { backgroundColor: gradeColor(g.gradeName) }]} /><Text style={st.grade}>{g.gradeName}석</Text></View>
              <Text style={st.price}>{won(g.price)}</Text>
            </View>
          ))}
          {concert.totalSeats ? (
            <View style={{ gap: 6, marginTop: 4 }}>
              <View style={st.meter}><View style={[st.meterFill, { width: `${bookedRate(concert)}%` }]} /></View>
              <Text style={st.muted}>예매율 {bookedRate(concert)}% · 남은 좌석 {concert.emptySeats}/{concert.totalSeats}</Text>
            </View>
          ) : <Text style={st.muted}>아직 좌석이 등록되지 않았습니다.</Text>}
        </Card>

        <Card style={{ gap: 8 }}>
          <Text style={st.h2}>공연 소개</Text>
          {(concert.about || []).map((p, i) => <Text key={i} style={st.body}>{p}</Text>)}
        </Card>

        <Card style={{ gap: 8 }}>
          <Text style={st.h2}>예매 안내</Text>
          <Text style={st.body}>· 1인 1매입니다. 같은 좌석을 동시에 고르면 한 명만 성공합니다.</Text>
          <Text style={st.body}>· 예매 · 취소 · 입장 기록은 FairChain 블록체인 노드에 남고, 티켓은 그 기록과 대조해 검증합니다.</Text>
          <Text style={st.body}>· 매진 뒤 나온 취소표는 대기 순번대로 재배정됩니다.</Text>
        </Card>
      </ScrollView>

      <View style={st.bar}>
        <Button
          title={mine ? '예매 완료 — 내 티켓 보기' : CTA[state] || '예매하기'}
          kind={mine ? 'ghost' : 'primary'}
          disabled={!mine && !['open', 'soldout'].includes(state)}
          onPress={book}
        />
      </View>
    </View>
  );
}

function Info({ label, value }) {
  return <View style={st.info}><Text style={st.infoLabel}>{label}</Text><Text style={st.infoValue}>{value}</Text></View>;
}

const st = StyleSheet.create({
  page: { padding: 16, gap: 12, paddingBottom: 24 },
  top: { flexDirection: 'row', gap: 14, borderRadius: 22, padding: 16, alignItems: 'center' },
  row: { flexDirection: 'row', gap: 8, alignItems: 'center' },
  rowBetween: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  dday: { color: '#fde68a', fontWeight: '800', fontSize: 13 },
  title: { color: '#fff', fontSize: 20, fontWeight: '800', lineHeight: 26 },
  tagline: { color: '#e5e5ef', fontSize: 13, lineHeight: 18 },
  h2: { color: C.ink, fontSize: 16, fontWeight: '800' },
  info: { flexDirection: 'row', gap: 12 },
  infoLabel: { width: 70, color: C.sub, fontSize: 14 },
  infoValue: { flex: 1, color: C.ink, fontSize: 14, fontWeight: '600' },
  dot: { width: 10, height: 10, borderRadius: 5 },
  grade: { color: C.ink, fontSize: 15, fontWeight: '600' },
  price: { color: C.ink, fontSize: 15, fontWeight: '800' },
  meter: { height: 8, borderRadius: 4, backgroundColor: '#ececf2', overflow: 'hidden' },
  meterFill: { height: 8, backgroundColor: C.primary, borderRadius: 4 },
  muted: { color: C.sub, fontSize: 13 },
  body: { color: '#3f3e4a', fontSize: 14, lineHeight: 21 },
  bar: { padding: 12, paddingBottom: 14, backgroundColor: C.card, borderTopWidth: 1, borderTopColor: C.line },
});
