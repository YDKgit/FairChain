// 취소표 알림 — 나에게 온 제안과, 매진되었거나 취소표가 보류 중인 공연의 현황
import { useCallback, useState } from 'react';
import { Pressable, RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import { fetchConcerts, get } from '../api';
import { useApp, useNow, usePolling } from '../app';
import { Button, Card, Empty, ErrorBox, Poster } from '../components/ui';
import { remaining, saleState, when } from '../lib/utils';
import { C, R } from '../theme';

export default function Realloc() {
  const { member, nav, config } = useApp();
  const [offers, setOffers] = useState([]);
  const [statuses, setStatuses] = useState(null);
  const [error, setError] = useState('');
  const [refreshing, setRefreshing] = useState(false);
  const now = useNow();

  const load = useCallback(async () => {
    if (!member) return;
    try {
      const [o, concerts] = await Promise.all([get('/api/reallocation/offers'), fetchConcerts()]);
      setOffers(o);
      const out = [];
      for (const c of concerts) {            // 한꺼번에 보내면 요청 제한에 걸릴 수 있어 차례로 묻는다
        const realloc = await get(`/api/reallocation/concerts/${c.id}`).catch(() => null);
        if (realloc && (saleState(c) === 'soldout' || realloc.heldCount > 0 || realloc.myRank)) out.push({ ...c, realloc });
      }
      setStatuses(out); setError('');
    } catch (e) { setError(e.message); }
  }, [member]);
  usePolling(load, 6000, !!member);

  if (!member) {
    return (
      <View style={st.page}>
        <Empty>로그인하면 나에게 온 취소표 제안을 볼 수 있습니다.</Empty>
        <Button title="로그인" onPress={() => nav.push('login', {})} />
      </View>
    );
  }

  const r = config.reallocation || {};
  return (
    <ScrollView
      contentContainerStyle={st.page}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={async () => { setRefreshing(true); await load(); setRefreshing(false); }} />}>
      <Card style={{ gap: 6 }}>
        <Text style={st.h2}>취소표는 이렇게 돌아갑니다</Text>
        <Text style={st.step}>1. 매진 뒤 나온 취소표는 바로 풀지 않고 모아 둡니다.</Text>
        <Text style={st.step}>2. 매일 {r.offerTime || '-'}에 대기 순번대로 한 사람씩 {r.offerTtlMinutes ?? '-'}분간 제안합니다.</Text>
        <Text style={st.step}>3. 다음 날 {r.releaseTime || '-'}에 남은 표는 일반 판매로 풀립니다.</Text>
        <Text style={st.step}>4. 공연 {r.finalWindowHours ?? '-'}시간 전부터는 나오는 즉시 판매됩니다.</Text>
      </Card>

      {error ? <ErrorBox message={error} onRetry={load} /> : null}

      <Text style={st.section}>내 차례</Text>
      {!offers.length && <Empty>지금 나에게 온 제안이 없습니다.</Empty>}
      {offers.map((o) => (
        <View key={o.offerId} style={st.offer}>
          <Text style={st.offerTitle}>{o.concertTitle}</Text>
          <Text style={st.offerText}>취소표 {o.seats.length}장 중 하나를 고를 수 있습니다</Text>
          <View style={st.chips}>
            {o.seats.map((s) => <View key={s.seatId} style={st.chip}><Text style={st.chipText}>{s.gradeName}석 {s.seatNumber}</Text></View>)}
          </View>
          <Text style={st.clock}>남은 시간 {remaining(o.expiresAt, now)}</Text>
          <Button title="취소표 고르기" onPress={() => nav.push('seats', { id: o.concertId, offerToken: o.offerToken })} />
        </View>
      ))}

      <Text style={st.section}>재배정 현황</Text>
      {statuses && !statuses.length && <Empty>매진되었거나 취소표가 보류 중인 공연이 없습니다.</Empty>}
      {(statuses || []).map((c) => {
        const s = c.realloc;
        return (
          <Pressable key={c.id} style={st.item} onPress={() => nav.push('concert', { id: c.id })}>
            <Poster concert={c} width={56} />
            <View style={{ flex: 1, gap: 5 }}>
              <Text style={st.itemTitle} numberOfLines={2}>{c.title}</Text>
              <Text style={[st.muted, { textAlign: 'left' }]} numberOfLines={1}>{when(c.eventStart)}</Text>
              <View style={st.chips}>
                <View style={st.chip}><Text style={st.chipText}>보류 취소표 {s.heldCount}장</Text></View>
                {s.roundStatus === 'OFFERING' && <View style={[st.chip, st.chipOn]}><Text style={[st.chipText, { color: '#fff' }]}>제안 중 · {s.currentSequence ?? '-'}번 차례</Text></View>}
                {s.roundStatus === 'RELEASE_PENDING' && <View style={st.chip}><Text style={st.chipText}>{when(s.releaseAt)} 일반 판매</Text></View>}
                {s.nextOfferAt && <View style={st.chip}><Text style={st.chipText}>다음 제안 {when(s.nextOfferAt)}</Text></View>}
                {s.finalWindow && <View style={st.chip}><Text style={st.chipText}>공연 임박 — 바로 판매</Text></View>}
              </View>
            </View>
            <View style={st.rank}>
              {s.myOffer ? <Text style={st.mine}>지금{'\n'}내 차례</Text>
                : s.myRank ? <><Text style={st.muted}>내 순번</Text><Text style={st.rankNum}>{s.myRank}번</Text></>
                  : <Text style={st.muted}>명단에{'\n'}없음</Text>}
            </View>
          </Pressable>
        );
      })}
      <Text style={[st.muted, { textAlign: 'left' }]}>대기열을 통과하고도 좌석을 못 구한 분만 명단에 오릅니다.</Text>
    </ScrollView>
  );
}

const st = StyleSheet.create({
  page: { padding: 16, gap: 12, paddingBottom: 32 },
  h2: { color: C.ink, fontSize: 15, fontWeight: '800', marginBottom: 2 },
  step: { color: '#3f3e4a', fontSize: 13, lineHeight: 19 },
  section: { color: C.ink, fontSize: 17, fontWeight: '800', marginTop: 6 },
  offer: { backgroundColor: C.warnSoft, borderRadius: R.card, padding: 16, gap: 8, borderWidth: 1, borderColor: '#fcd34d' },
  offerTitle: { color: '#78350f', fontSize: 16, fontWeight: '800' },
  offerText: { color: '#78350f', fontSize: 13 },
  clock: { color: C.warn, fontSize: 20, fontWeight: '800', fontVariant: ['tabular-nums'] },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  chip: { backgroundColor: '#ececf2', borderRadius: 999, paddingHorizontal: 10, paddingVertical: 4 },
  chipOn: { backgroundColor: C.primary },
  chipText: { color: '#3f3e4a', fontSize: 12, fontWeight: '600' },
  item: { flexDirection: 'row', gap: 12, backgroundColor: C.card, borderRadius: R.card, padding: 12, borderWidth: 1, borderColor: C.line, alignItems: 'center' },
  itemTitle: { color: C.ink, fontSize: 15, fontWeight: '800' },
  muted: { color: C.sub, fontSize: 12, lineHeight: 17, textAlign: 'center' },
  rank: { width: 64, alignItems: 'center' },
  rankNum: { color: C.primary, fontSize: 18, fontWeight: '800' },
  mine: { color: C.pink, fontSize: 13, fontWeight: '800', textAlign: 'center' },
});
