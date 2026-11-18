// 홈 — 공연 목록. 장르 · 검색으로 좁혀 보고, 공연을 누르면 상세로
import { useCallback, useMemo, useState } from 'react';
import { Pressable, RefreshControl, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { fetchConcerts } from '../api';
import { useApp, usePolling } from '../app';
import { Empty, ErrorBox, Loading, Poster, SaleBadge } from '../components/ui';
import { GENRES } from '../lib/catalog';
import { bookedRate, dday, saleState, when, won } from '../lib/utils';
import { C, R } from '../theme';

export default function Home() {
  const { nav } = useApp();
  const [concerts, setConcerts] = useState(null);
  const [error, setError] = useState('');
  const [genre, setGenre] = useState('all');
  const [keyword, setKeyword] = useState('');
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async () => {
    try { setConcerts(await fetchConcerts()); setError(''); }
    catch (e) { setError(e.message); }
  }, []);
  usePolling(load, 15000);

  const shown = useMemo(() => {
    const k = keyword.trim().toLowerCase();
    return (concerts || [])
      .filter((c) => genre === 'all' || c.genre === genre)
      .filter((c) => !k || `${c.title} ${c.venue} ${c.artist}`.toLowerCase().includes(k))
      .sort((a, b) => String(a.eventStart).localeCompare(String(b.eventStart)));
  }, [concerts, genre, keyword]);

  const featured = (concerts || []).find((c) => saleState(c) === 'open');

  return (
    <ScrollView
      contentContainerStyle={st.page}
      keyboardShouldPersistTaps="handled"
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={async () => { setRefreshing(true); await load(); setRefreshing(false); }} />}>
      {featured && (
        <Pressable style={st.hero} onPress={() => nav.push('concert', { id: featured.id })}>
          <View style={[st.heroBg, { backgroundColor: featured.palette[0] }]} />
          <View style={{ flex: 1, gap: 6 }}>
            <Text style={st.heroKicker}>{featured.genreLabel} · {dday(featured.eventStart)}</Text>
            <Text style={st.heroTitle} numberOfLines={2}>{featured.title}</Text>
            <Text style={st.heroSub} numberOfLines={1}>{featured.tagline || featured.venue}</Text>
            <Text style={st.heroCta}>지금 예매하기 ›</Text>
          </View>
          <Poster concert={featured} width={92} />
        </Pressable>
      )}

      <TextInput
        style={st.search}
        value={keyword}
        onChangeText={setKeyword}
        placeholder="공연 · 아티스트 · 공연장 검색"
        placeholderTextColor={C.faint}
        returnKeyType="search"
        autoCorrect={false}
      />

      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={st.chips}>
        {[{ key: 'all', label: '전체' }, ...GENRES].map((g) => (
          <Pressable key={g.key} onPress={() => setGenre(g.key)} style={[st.chip, genre === g.key && st.chipOn]}>
            <Text style={[st.chipText, genre === g.key && st.chipTextOn]}>{g.label}</Text>
          </Pressable>
        ))}
      </ScrollView>

      {error ? <ErrorBox message={error} onRetry={load} /> : null}
      {!concerts && !error && <Loading />}
      {concerts && !shown.length && <Empty>조건에 맞는 공연이 없습니다.</Empty>}

      {shown.map((c) => (
        <Pressable key={c.id} style={st.item} onPress={() => nav.push('concert', { id: c.id })}>
          <Poster concert={c} width={84} />
          <View style={st.itemBody}>
            <View style={st.row}>
              <SaleBadge concert={c} />
              <Text style={st.genre}>{c.genreLabel}</Text>
            </View>
            <Text style={st.title} numberOfLines={2}>{c.title}</Text>
            <Text style={st.meta} numberOfLines={1}>{when(c.eventStart)}</Text>
            <Text style={st.meta} numberOfLines={1}>{c.venue}</Text>
            <View style={st.rowBetween}>
              <Text style={st.price}>{c.minPrice != null ? `${won(c.minPrice)}~` : '가격 미정'}</Text>
              {c.totalSeats ? <Text style={st.rate}>예매율 {bookedRate(c)}%</Text> : null}
            </View>
          </View>
        </Pressable>
      ))}
    </ScrollView>
  );
}

const st = StyleSheet.create({
  page: { padding: 16, gap: 12, paddingBottom: 32 },
  hero: { flexDirection: 'row', gap: 14, alignItems: 'center', borderRadius: 22, padding: 18, overflow: 'hidden', backgroundColor: C.dark },
  heroBg: { ...StyleSheet.absoluteFillObject, opacity: 0.55 },
  heroKicker: { color: '#fde68a', fontSize: 12, fontWeight: '700' },
  heroTitle: { color: '#fff', fontSize: 20, fontWeight: '800', lineHeight: 26 },
  heroSub: { color: '#e5e5ef', fontSize: 13 },
  heroCta: { color: '#fff', fontSize: 14, fontWeight: '700', marginTop: 6 },
  search: { backgroundColor: C.card, borderRadius: 14, borderWidth: 1, borderColor: C.line, paddingHorizontal: 14, paddingVertical: 12, fontSize: 15, color: C.ink },
  chips: { gap: 8, paddingVertical: 2 },
  chip: { paddingHorizontal: 14, paddingVertical: 8, borderRadius: R.chip, backgroundColor: C.card, borderWidth: 1, borderColor: C.line },
  chipOn: { backgroundColor: C.ink, borderColor: C.ink },
  chipText: { color: C.sub, fontSize: 13, fontWeight: '600' },
  chipTextOn: { color: '#fff' },
  item: { flexDirection: 'row', gap: 14, backgroundColor: C.card, borderRadius: R.card, padding: 12, borderWidth: 1, borderColor: C.line },
  itemBody: { flex: 1, gap: 4, justifyContent: 'center' },
  row: { flexDirection: 'row', gap: 8, alignItems: 'center' },
  rowBetween: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 4 },
  genre: { color: C.sub, fontSize: 12, fontWeight: '600' },
  title: { color: C.ink, fontSize: 16, fontWeight: '800', lineHeight: 21 },
  meta: { color: C.sub, fontSize: 13 },
  price: { color: C.ink, fontSize: 15, fontWeight: '800' },
  rate: { color: C.primary, fontSize: 12, fontWeight: '700' },
});
