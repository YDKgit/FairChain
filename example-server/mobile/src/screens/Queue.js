// 대기열 — FairChain이 매긴 순번을 보여 주고, 입장이 허용되면 좌석 선택으로 넘어간다.
// 웹은 서버가 순번을 밀어 주지만(SSE) 휴대폰 앱에서는 1.5초마다 상태를 물어본다.
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { del, fetchConcert, get, post } from '../api';
import { useApp } from '../app';
import { Button, ErrorBox } from '../components/ui';
import { when } from '../lib/utils';
import { C } from '../theme';

export default function Queue({ id }) {
  const { nav, toast } = useApp();
  const [concert, setConcert] = useState(null);
  const [status, setStatus] = useState(null);
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  const first = useRef(null);

  useEffect(() => {
    let alive = true;
    let timer;
    setError(''); setStatus(null); first.current = null;
    fetchConcert(id).then((c) => alive && setConcert(c)).catch(() => {});

    const apply = (s) => {
      if (!alive || !s) return;
      setStatus(s);
      if (first.current == null && s.rank != null) first.current = s.rank;
      if (s.state === 'ADMITTED') {
        clearInterval(timer);
        toast('입장이 허용되었습니다 — 좌석을 고르세요');
        nav.replace('seats', { id });
      } else if (s.state === 'EXPIRED') {
        clearInterval(timer); setError('입장 시간이 지나 순번이 만료되었습니다. 다시 줄을 서 주세요.');
      } else if (s.state === 'NOT_FOUND') {
        clearInterval(timer); setError('대기열에 없습니다. 다시 줄을 서 주세요.');
      }
    };

    (async () => {
      try { apply(await post(`/api/queue/${id}`)); }       // 이미 줄에 있으면 현재 상태를 돌려준다
      catch (e) { if (alive) setError(e.message); return; }
      timer = setInterval(() => { get(`/api/queue/${id}/status`).then(apply).catch(() => {}); }, 1500);
    })();
    return () => { alive = false; clearInterval(timer); };
  }, [id, attempt, nav, toast]);

  const leave = async () => {
    try { await del(`/api/queue/${id}`); toast('대기열에서 나왔습니다'); } catch (e) { toast(e.message, 'bad'); }
    nav.back();
  };

  const rank = status?.rank ?? null;
  const progress = first.current && rank != null ? Math.min(100, Math.round(((first.current - rank) / first.current) * 100)) : 0;

  return (
    <View style={st.page}>
      <View style={st.box}>
        {concert && (
          <View style={st.head}>
            <Text style={st.headTitle} numberOfLines={1}>{concert.title}</Text>
            <Text style={st.headSub} numberOfLines={1}>{concert.venue} · {when(concert.eventStart)}</Text>
          </View>
        )}
        {error ? (
          <View style={st.body}>
            <Text style={st.h1}>대기열에 다시 들어가야 합니다</Text>
            <ErrorBox message={error} />
            <Button title="다시 줄 서기" onPress={() => setAttempt((a) => a + 1)} />
            <Button title="공연으로" kind="ghost" onPress={() => nav.back()} />
          </View>
        ) : (
          <View style={st.body}>
            <View style={st.kicker}><ActivityIndicator color={C.primary} size="small" /><Text style={st.kickerText}>접속 대기 중</Text></View>
            <Text style={st.h1}>잠시만 기다려 주세요</Text>
            <Text style={st.muted}>내 대기 순번</Text>
            <Text style={st.rank}>{rank ?? '…'}</Text>
            <Text style={st.muted}>앞에 {rank != null ? Math.max(0, rank - 1) : '…'}명 · 전체 대기 {status?.waitingCount ?? '…'}명</Text>
            <View style={st.meter}><View style={[st.meterFill, { width: `${progress}%` }]} /></View>
            <View style={st.notes}>
              <Text style={st.note}>· 순번은 FairChain이 매깁니다. 매크로와 반복 요청은 걸러집니다.</Text>
              <Text style={st.note}>· 순번이 되면 자동으로 좌석 선택 화면으로 넘어갑니다.</Text>
              <Text style={st.note}>· 앱을 다시 열어도 순번은 그대로 유지됩니다.</Text>
            </View>
            <Pressable onPress={leave}><Text style={st.leave}>대기열 나가기</Text></Pressable>
          </View>
        )}
      </View>
    </View>
  );
}

const st = StyleSheet.create({
  page: { flex: 1, padding: 16, justifyContent: 'center' },
  box: { backgroundColor: C.card, borderRadius: 24, overflow: 'hidden', borderWidth: 1, borderColor: C.line },
  head: { backgroundColor: C.dark, padding: 18, gap: 4 },
  headTitle: { color: '#fff', fontSize: 16, fontWeight: '800' },
  headSub: { color: '#c9c8d6', fontSize: 13 },
  body: { padding: 22, gap: 12, alignItems: 'stretch' },
  kicker: { flexDirection: 'row', gap: 8, alignSelf: 'center', alignItems: 'center', backgroundColor: C.primarySoft, borderRadius: 999, paddingHorizontal: 14, paddingVertical: 7 },
  kickerText: { color: C.primary, fontWeight: '700', fontSize: 13 },
  h1: { color: C.ink, fontSize: 22, fontWeight: '800', textAlign: 'center' },
  muted: { color: C.sub, fontSize: 14, textAlign: 'center' },
  rank: { color: C.primary, fontSize: 84, fontWeight: '900', textAlign: 'center', lineHeight: 92 },
  meter: { height: 8, borderRadius: 4, backgroundColor: '#ececf2', overflow: 'hidden' },
  meterFill: { height: 8, backgroundColor: C.primary },
  notes: { backgroundColor: C.bg, borderRadius: 14, padding: 14, gap: 6 },
  note: { color: '#3f3e4a', fontSize: 13, lineHeight: 19 },
  leave: { color: C.sub, textAlign: 'center', textDecorationLine: 'underline', paddingVertical: 6 },
});
