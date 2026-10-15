# 설계 변경안 — 노드 간 통신 제거, FairChain에서 다수결

노드 담당 제안 (10/12). **팀 합의 전**이며, 합의되면 설계명세서를 아래와 같이 고친다.

## 바뀌는 점

| | 지금 설계명세서 | 변경안 |
|---|---|---|
| 기록 | FairChain이 노드 1대에 보내고, 그 노드가 다른 2대에 제안 · 투표 | FairChain이 **노드 3대에 같은 기록을 각각** 보낸다 |
| 다수결 | 노드끼리 (2/3) | **FairChain** (3대 중 2대 이상 같은 응답) |
| 노드 ↔ 노드 통신 | 있음 (`NodeConsensusApi`) | **없음** — 노드는 서로의 주소도 모른다 |
| 검증 | FairChain이 3대에 묻고 2/3 | 그대로 |

## 그림

- `SD-0302-03-revised.png` — 설계명세서 4.3.3 **SD-0302-03**을 이 그림으로 교체
- `SD-0302-03-revised.puml` — 같은 그림의 PlantUML 원본

## 설계명세서에서 같이 고칠 곳

| 위치 | 수정 |
|---|---|
| 1.3 "블록체인 노드 장애 대응 범위 제외" | "노드 간 합의는…" → "FairChain의 다수결은 노드 3대가 모두 정상 응답하는 상황을 전제로…" |
| 4.2 CD-03-3 (노드서버 클래스 다이어그램) | `NodeConsensusApi`, `ConsensusService`, `NodePeerAccess`, `NodePeerRequester`, `BlockProposalDto` 삭제 |
| 4.4.3.2 C-0332 `BlockRecordRequester.submitRecord` | "노드에 기록 POST 전송" → "노드 3대에 전송 후 2/3 일치 결과 반환" |
| 4.4.3.3 C-0347 `BlockProposalDto` | 삭제 |
| 4.4.3.3 C-0349 `NodeConsensusApi` | 삭제 |
| 4.4.3.3 C-0350 `NodeController` | `receiveProposal`, `confirmBlock` 행 삭제 |
| 4.4.3.3 C-0351 `BlockRecordService.submitRecord` | "블록 생성 후 합의 요청" → "검증 후 블록 생성 · 저장" |
| 4.4.3.3 C-0352 `ConsensusService`, C-0353 `NodePeerAccess`, C-0354 `NodePeerRequester` | 삭제 |
| 4.3.4 SD-401-02 아래 메모 | "노드끼리 합의(NodeConsensusApi) → 확정" → "FairChain 다수결 → 확정" |
| 6. 용어 해설 "합의(Consensus)" | "FairChain이 노드 3대의 응답을 비교해 2대 이상이 같으면 블록을 확정하는 절차" |
| 4.1 DD-01 (시스템 운용도) | 노드 사이를 잇는 선이 있으면 삭제 |

## 변경안의 약점

- 꺼져 있던 노드는 **스스로 따라오지 못한다.** 놓친 기록을 FairChain이 같은 순서로 다시 보내거나, 정상 노드의 DB를 복사해야 한다. 그 전에 새 기록을 받으면 블록 번호가 어긋난다
- 노드 1대가 꺼져 있는 동안 FairChain은 기록마다 그 노드의 응답을 기다린다 (최대 노드 호출 제한 2초)
- 3대의 체인이 같으려면 FairChain이 기록을 **한 번에 하나씩 같은 순서로** 보내야 한다 (지금의 대기 줄 방식 그대로)
