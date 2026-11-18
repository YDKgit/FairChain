// 여러 화면이 함께 쓰는 작은 부품들
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import Svg, { Circle, Defs, LinearGradient, Path, Rect, Stop } from 'react-native-svg';
import { saleBadge, verificationBadge } from '../lib/labels';
import { saleState } from '../lib/utils';
import { C, R, badgeColors } from '../theme';

export function Button({ title, onPress, kind = 'primary', disabled, busy, style }) {
  const ghost = kind === 'ghost';
  const danger = kind === 'danger';
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled || busy}
      style={({ pressed }) => [
        s.button,
        ghost && s.buttonGhost,
        danger && s.buttonDanger,
        (disabled || busy) && s.buttonOff,
        pressed && { opacity: 0.85 },
        style,
      ]}>
      {busy ? <ActivityIndicator color={ghost ? C.primary : '#fff'} />
        : <Text style={[s.buttonText, ghost && { color: C.primary }]}>{title}</Text>}
    </Pressable>
  );
}

export function Badge({ kind = 'muted', children }) {
  const [bg, fg] = badgeColors[kind] || badgeColors.muted;
  return <View style={[s.badge, { backgroundColor: bg }]}><Text style={[s.badgeText, { color: fg }]}>{children}</Text></View>;
}

export function SaleBadge({ concert }) {
  const [kind, text] = saleBadge[saleState(concert)] || ['muted', ''];
  return <Badge kind={kind}>{text}</Badge>;
}

export function VerifyBadge({ status }) {
  const [kind, text] = verificationBadge[status] || ['muted', status || ''];
  return <Badge kind={kind}>{text}</Badge>;
}

export function Card({ children, style }) {
  return <View style={[s.card, style]}>{children}</View>;
}

export function Empty({ children }) {
  return <View style={s.empty}><Text style={s.emptyText}>{children}</Text></View>;
}

export function ErrorBox({ message, onRetry }) {
  return (
    <View style={s.error}>
      <Text style={s.errorText}>{message}</Text>
      {onRetry && <Pressable onPress={onRetry}><Text style={s.errorRetry}>다시 시도</Text></Pressable>}
    </View>
  );
}

export function Loading() {
  return <View style={s.loading}><ActivityIndicator color={C.primary} size="large" /></View>;
}

/** 공연 포스터 — 장르 색으로 그린 그림 (실제 포스터 이미지가 없어 색 · 무늬로 대신한다) */
export function Poster({ concert, width = 96, height, radius = 14 }) {
  const h = height || Math.round(width * 1.3);
  const [dark, light, point] = concert?.palette || ['#27272a', '#5b3df5', '#c4b5fd'];
  const id = `g${concert?.id ?? 'x'}-${width}`;
  return (
    <View style={{ width, height: h, borderRadius: radius, overflow: 'hidden', backgroundColor: dark }}>
      <Svg width={width} height={h} viewBox="0 0 100 130" preserveAspectRatio="xMidYMid slice">
        <Defs>
          <LinearGradient id={id} x1="0" y1="0" x2="1" y2="1">
            <Stop offset="0" stopColor={dark} />
            <Stop offset="1" stopColor={light} />
          </LinearGradient>
        </Defs>
        <Rect width="100" height="130" fill={`url(#${id})`} />
        <Circle cx="72" cy="34" r="15" fill={point} opacity="0.92" />
        <Path d="M0 84 Q25 70 50 84 T100 84 V130 H0 Z" fill={light} opacity="0.55" />
        <Path d="M0 98 Q25 86 50 98 T100 98 V130 H0 Z" fill={dark} opacity="0.45" />
        <Path d="M0 112 Q25 102 50 112 T100 112 V130 H0 Z" fill={point} opacity="0.28" />
      </Svg>
    </View>
  );
}

export const s = StyleSheet.create({
  button: { backgroundColor: C.primary, borderRadius: R.button, paddingVertical: 15, paddingHorizontal: 18, alignItems: 'center', justifyContent: 'center', minHeight: 52 },
  buttonGhost: { backgroundColor: C.primarySoft },
  buttonDanger: { backgroundColor: C.bad },
  buttonOff: { opacity: 0.45 },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '700' },
  badge: { alignSelf: 'flex-start', borderRadius: R.chip, paddingHorizontal: 10, paddingVertical: 4 },
  badgeText: { fontSize: 12, fontWeight: '700' },
  card: { backgroundColor: C.card, borderRadius: R.card, padding: 16, borderWidth: 1, borderColor: C.line },
  empty: { padding: 28, alignItems: 'center', backgroundColor: C.card, borderRadius: R.card, borderWidth: 1, borderColor: C.line },
  emptyText: { color: C.sub, fontSize: 14, textAlign: 'center', lineHeight: 21 },
  error: { backgroundColor: C.badSoft, borderRadius: 12, padding: 14, gap: 8 },
  errorText: { color: C.bad, fontSize: 14, lineHeight: 20 },
  errorRetry: { color: C.bad, fontWeight: '700', textDecorationLine: 'underline' },
  loading: { padding: 48, alignItems: 'center' },
});
