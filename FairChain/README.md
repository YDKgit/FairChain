# FairChain

티켓 위변조 방지 · 공정 대기열 플랫폼 (캡스톤디자인)

## 모듈
| 모듈 | 종류 | 설명 |
|---|---|---|
| `fairchain-api` | 라이브러리 | FairChain이 제공하는 약속 (인터페이스 · DTO · HttpRequester · RecordHasher) |
| `node-api` | 라이브러리 | 노드가 제공하는 약속 |
| `example-server` | Spring Boot | 예제서버 (콘서트 예매 사이트) |
| `fairchain` | Spring Boot | FairChain 서버 |
| `node` | Spring Boot | 노드 서버 (같은 코드로 3대 실행) |

## 실행
JDK 17 필요
```bash
./gradlew build
```
