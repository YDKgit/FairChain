// 대기열 — FairChain이 매긴 순번을 보여 주고, 입장이 허용되면 좌석 선택으로 넘어간다.
// 상태는 서버가 알려 준 간격(nextPollMs)마다 묻는다: 앞 순번은 1초, 뒤 순번은 최대 15초.
// 답을 받은 뒤에야 다음 확인을 잡으므로(5초 넘으면 끊음) 서버가 바빠도 요청이 쌓이지 않는다.
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { del, fetchConcert, getWithin, post } from '../api';
import { useApp } from '../app';
import { Button, ErrorBox } from '../components/ui';
import { when } from '../lib/utils';
import { C } from '../theme';

export default function Queue({ id }) {
  const { nav, toast } = useApp();
  const [concert, setConcert] = useState(null);
  const [status, setStatus] = useState(null);
  const [error, setError] = useState('');
  const [slow, setSlow] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const first = useRef(null);

  useEffect(() => {
    let alive = true;
    let done = false;          // 입장 허용 · 만료 · 줄에 없음 — 더 묻지 않는다
    let timer;
    let nextMs = 2000;         // 서버가 알려 준 다음 확인 간격
    setError(''); setStatus(null); setSlow(false); first.current = null;
    fetchConcert(id).then((c) => alive && setConcert(c)).catch(() => {});

    const apply = (s) => {
      if (!alive || done || !s) return;
      setStatus(s);
      setSlow(false);
      if (s.nextPollMs) nextMs = s.nextPollMs;
      if (first.current == null && s.rank != null) first.current = s.rank;
      if (s.state === 'ADMITTED') {
        done = true;
        toast('입장이 허용되었습니다 — 좌석을 고르세요');
        nav.replace('seats', { id });
      } else if (s.state === 'EXPIRED') {
        done = true; setError('입장 시간이 지나 순번이 만료되었습니다. 다시 줄을 서 주세요.');
      } else if (s.state === 'NOT_FOUND') {
        done = true; setError('대기열에 없습니다. 다시 줄을 서 주세요.');
      }
    };

    const schedule = (ms) => { if (alive && !done) timer = setTimeout(ask, ms); };
    async function ask() {
      try {
        apply(await getWithin(`/api/queue/${id}/status`, 5000));
        schedule(nextMs);
      } catch {
        if (!alive) return;
        setSlow(true);                                   // 순번은 서버에 그대로 있다 — 조금 쉬었다 다시
        schedule(Math.min(Math.max(nextMs, 2000) * 2, 15000));
      }
    }

    (async () => {
      try { apply(await post(`/api/queue/${id}`)); }       // 이미 줄에 있으면 현재 상태를 돌려준다
      catch (e) { if (alive) setError(e.message); return; }
      schedule(nextMs);
    })();
    return () => { alive = false; clearTimeout(timer); };
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
            {slow && <Text style={st.slow}>접속이 몰려 순번 확인이 늦어지고 있습니다 — 순번은 그대로 유지됩니다</Text>}
            <View style={st.meter}><View style={[st.meterFill, { width: `${progress}%` }]} /></View>
            <View style={st.notes}>
              <Text style={st.note}>· 순번은 FairChain이 매깁니다. 매크로와 반복 요청은 걸러집니다.</Text>
              <Text style={st.note}>· 순번이 되면 자동으로 좌석 선택 화면으로 넘어갑니다.</Text>
              <Text style={st.note}>· 앱을 다시 열어도 순번은 그대로 유지됩니다.</Text>
              <Text style={st.note}>· 순번이 가까울수록 자주(1초), 멀수록 천천히(최대 15초) 새로 고칩니다.</Text>
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
  slow: { color: '#b42318', backgroundColor: '#fdecea', borderRadius: 10, padding: 8, fontSize: 13, textAlign: 'center' },
  rank: { color: C.primary, fontSize: 84, fontWeight: '900', textAlign: 'center', lineHeight: 92 },
  meter: { height: 8, borderRadius: 4, backgroundColor: '#ececf2', overflow: 'hidden' },
  meterFill: { height: 8, backgroundColor: C.primary },
  notes: { backgroundColor: C.bg, borderRadius: 14, padding: 14, gap: 6 },
  note: { color: '#3f3e4a', fontSize: 13, lineHeight: 19 },
  leave: { color: C.sub, textAlign: 'center', textDecorationLine: 'underline', paddingVertical: 6 },
});
