다른 PC에서 JMeter 돌리기 (FairChain 부하 테스트)

이 폴더만 있으면 됩니다. 저장소는 필요 없습니다.

1. 준비 (한 번만)
   - Java 17 이상 + JMeter 5.6
       mac:     brew install jmeter
       Windows: jmeter.apache.org 에서 zip을 받아 풀고, 그 안의 bin 폴더를 PATH에 추가
   - 예제서버 PC와 같은 와이파이. 브라우저에서 http://예제서버IP:8080 이 열리는지 확인
   - 시계가 맞아야 합니다 (자동 시간 맞춤 켜기). 두 PC가 같은 시각에 들어가는 기준이 각자의 시계입니다

2. 실행 (공연번호 · 진입시각은 FairChain PC 담당이 알려 주는 값 그대로)
       mac · Linux:  ./run.sh  예제서버IP 공연번호 HH:MM
       Windows:      run.bat   예제서버IP 공연번호 HH:MM
   예) ./run.sh 172.30.71.30 12 19:42
   - 진입시각 1분 이상 전에 시작하세요 (5천 명이 미리 로그인하는 데 20초쯤 걸립니다)
   - 기본 5,000명, 회원은 load5001 ~ load10000 을 씁니다 (FairChain PC가 load1 ~ load5000)

3. 끝나면
   - results/시각/remote.jtl 을 FairChain PC 담당에게 보내 주세요
