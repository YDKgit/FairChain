// 공연 소개 정보 — 서버의 공연 데이터(제목 · 공연장 · 일시)에 화면용 정보(장르 · 아티스트 · 소개)를 덧붙인다.
// 서버 Concert에는 장르 칸이 없어서, 시연용 공연은 제목으로 찾고 그 밖의 공연은 제목에서 장르를 짐작한다.

/** 장르 — palette: 포스터 색 [어두운 쪽, 밝은 쪽, 포인트], art: 포스터 무늬 */
export const GENRES = [
  { key: 'kpop', label: 'K-POP', palette: ['#4c1d95', '#db2777', '#fde047'], art: 'orbit' },
  { key: 'ballad', label: '발라드', palette: ['#172a46', '#5b84b1', '#fbd38d'], art: 'wave' },
  { key: 'rock', label: '록/인디', palette: ['#18181b', '#b91c1c', '#fb923c'], art: 'beam' },
  { key: 'hiphop', label: '힙합/R&B', palette: ['#0f172a', '#ca8a04', '#22d3ee'], art: 'grid' },
  { key: 'festival', label: '페스티벌', palette: ['#0e7490', '#16a34a', '#fef08a'], art: 'sun' },
  { key: 'classic', label: '클래식', palette: ['#2b2118', '#8b6b3d', '#f5e6c8'], art: 'strings' },
  { key: 'world', label: '내한공연', palette: ['#1e1b4b', '#2563eb', '#f472b6'], art: 'prism' },
];

const ETC = { key: 'etc', label: '콘서트', palette: ['#27272a', '#5b3df5', '#c4b5fd'], art: 'orbit' };

export const genreOf = (key) => GENRES.find((g) => g.key === key) || ETC;

/** 시연용 공연 소개 (DataInitializer가 만드는 공연 제목과 같아야 한다) */
const CATALOG = {
  '2026 FAIRCHAIN LIVE TOUR - 서울': {
    genre: 'kpop', artist: 'FAIRCHAIN', tagline: '전국 투어의 시작, 서울 첫 공연', runtime: 150, age: '8세 이상',
    about: ['전국 5개 도시를 도는 라이브 투어의 첫 무대입니다. 올해 발표한 정규 앨범 전곡과 대표곡을 밴드 라이브로 들려줍니다.',
      '공연 당일 예매 · 입장까지 한 번에 시연할 수 있는 공연입니다. 공연이 임박해 취소표는 재배정 명단을 거치지 않고 바로 판매됩니다.'],
  },
  '2026 SUMMER NIGHT CONCERT': {
    genre: 'festival', artist: 'SUMMER NIGHT', tagline: '여름밤, 바다 옆에서 듣는 라이브', runtime: 180, age: '전체 관람가',
    about: ['부산의 여름밤을 채우는 합동 콘서트입니다. 여섯 팀이 차례로 무대에 오르고 마지막은 전 출연진 합동 무대로 마칩니다.',
      '매진된 뒤 나온 취소표가 대기 순번대로 재배정되는 과정을 볼 수 있는 공연입니다.'],
  },
  'MY CENTURY LIVE 2026': {
    genre: 'ballad', artist: 'MY CENTURY', tagline: '데뷔 10주년 기념 단독 공연', runtime: 140, age: '8세 이상',
    about: ['데뷔 10주년을 맞아 여는 단독 공연입니다. 1집부터 최근 싱글까지 시간 순서대로 구성했습니다.',
      '아직 예매 오픈 전입니다. 오픈 시각이 되면 대기열에 들어갈 수 있습니다.'],
  },
  '루나베일 단독 콘서트 〈MOONPHASE〉': {
    genre: 'kpop', artist: '루나베일', tagline: '달의 위상을 따라 흐르는 네 개의 막', runtime: 160, age: '8세 이상', art: 'orbit',
    about: ['5인조 그룹 루나베일의 두 번째 단독 콘서트입니다. 초승달에서 보름달까지 달의 위상을 따라 네 개의 막으로 구성했습니다.',
      '360도 무대와 돌출 무대를 함께 써서 어느 좌석에서도 가깝게 볼 수 있습니다.'],
  },
  '한결 전국투어 〈겨울의 온도〉 - 대구': {
    genre: 'ballad', artist: '한결', tagline: '가장 추운 계절에 건네는 가장 따뜻한 노래', runtime: 130, age: '8세 이상',
    about: ['싱어송라이터 한결의 겨울 전국투어 대구 공연입니다. 현악 4중주와 피아노 중심의 편곡으로 들려줍니다.',
      '공연 중 관객 사연을 읽고 신청곡을 부르는 순서가 있습니다.'],
  },
  'NEON HARBOR ROCK FESTIVAL 2026': {
    genre: 'rock', artist: 'NEON HARBOR', tagline: '항구의 밤을 깨우는 이틀', runtime: 420, age: '전체 관람가', palette: ['#111827', '#be123c', '#fb923c'],
    about: ['국내외 밴드 14팀이 두 개의 무대에 오르는 야외 록 페스티벌입니다.',
      '스탠딩 구역과 지정석 구역이 나뉘어 있고, 이 예매는 지정석 구역입니다.'],
  },
  '블랙파인 〈ASPHALT〉 앙코르': {
    genre: 'hiphop', artist: '블랙파인', tagline: '전석 매진 공연, 한 번 더', runtime: 120, age: '15세 이상',
    about: ['래퍼 블랙파인의 정규 2집 〈ASPHALT〉 발매 공연 앙코르입니다. 라이브 밴드와 DJ가 함께합니다.',
      '본 공연에 없던 미발표곡 두 곡을 처음 공개합니다.'],
  },
  '아르떼 심포니 송년음악회': {
    genre: 'classic', artist: '아르떼 심포니 오케스트라', tagline: '한 해를 보내는 교향곡의 밤', runtime: 110, age: '초등학생 이상',
    about: ['아르떼 심포니 오케스트라의 송년음악회입니다. 1부는 협주곡, 2부는 교향곡으로 구성했습니다.',
      '공연 시작 후에는 곡 사이에만 입장할 수 있습니다.'],
  },
  '새벽세시 어쿠스틱 라이브 〈잔향〉': {
    genre: 'rock', artist: '새벽세시', tagline: '작은 공연장에서 듣는 가까운 소리', runtime: 100, age: '8세 이상', art: 'wave', palette: ['#1c1917', '#a16207', '#fde68a'],
    about: ['인디 듀오 새벽세시의 어쿠스틱 공연입니다. 마이크와 통기타, 건반만으로 진행합니다.',
      '200석 규모의 소극장 공연이라 좌석 수가 적습니다.'],
  },
  'HALCYON DRIVE WORLD TOUR in SEOUL': {
    genre: 'world', artist: 'HALCYON DRIVE', tagline: '첫 내한, 단 하루', runtime: 120, age: '8세 이상',
    about: ['4인조 밴드 HALCYON DRIVE의 첫 내한 공연입니다. 월드투어 아시아 일정 중 서울 공연은 하루뿐입니다.',
      '예매 오픈 시각에 대기열이 열립니다.'],
  },
};

const GUESS = [
  [/페스티벌|FESTIVAL|FEST\b/i, 'festival'],
  [/WORLD TOUR|내한|in SEOUL/i, 'world'],
  [/심포니|필하모닉|음악회|오케스트라|리사이틀|클래식/i, 'classic'],
  [/어쿠스틱|밴드|록|ROCK|인디/i, 'rock'],
  [/힙합|랩|R&B|HIPHOP/i, 'hiphop'],
  [/발라드|전국투어/i, 'ballad'],
];

/** "서울 · 잠실실내체육관" → { region: "서울", hall: "잠실실내체육관" } */
export function splitVenue(venue = '') {
  const parts = venue.split(/\s*[·|,/-]\s*/).filter(Boolean);
  return parts.length > 1 ? { region: parts[0], hall: parts.slice(1).join(' ') } : { region: '', hall: venue };
}

/** 서버 공연 한 건에 화면용 정보를 붙인다 */
export function enrich(concert) {
  if (!concert) return concert;
  const meta = CATALOG[concert.title] || {};
  const guessed = GUESS.find(([re]) => re.test(concert.title || ''));
  const genre = genreOf(meta.genre || (guessed ? guessed[1] : 'etc'));
  const { region, hall } = splitVenue(concert.venue);
  const prices = (concert.seatGrades || []).map((g) => Number(g.price)).filter((p) => !Number.isNaN(p));
  return {
    ...concert,
    genre: genre.key,
    genreLabel: genre.label,
    artist: meta.artist || '',
    tagline: meta.tagline || '',
    runtime: meta.runtime || 120,
    age: meta.age || '전체 관람가',
    about: meta.about || ['운영자가 등록한 공연입니다. 좌석 발행 · 판매 · 취소 · 입장 기록은 모두 FairChain 블록체인 노드에 남습니다.'],
    palette: meta.palette || genre.palette,
    art: meta.art || genre.art,
    region,
    hall,
    minPrice: prices.length ? Math.min(...prices) : null,
    booked: Math.max(0, (concert.totalSeats || 0) - (concert.emptySeats || 0)),
  };
}
