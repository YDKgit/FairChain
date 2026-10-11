# Blockchain v6 — наблюдение за согласованностью (MVP)

## Изменения
- Координатор сохраняет собственное предложение в `pending_blocks` **перед** голосованием; после трёх подтверждений подтверждает локальный блок через `confirm()`.
- `GET /node/ledger/status` (заголовок `X-Node-Secret`) возвращает `height`, `headHash`, `chainValid`, `pendingIndices`.
- `GET /api/blockchain/audit` (заголовок `X-API-Key` зарегистрированного клиента) опрашивает все три узла.

## Примеры
```http
GET http://localhost:8081/node/ledger/status
X-Node-Secret: local-demo-change-me
```
```json
{"height":1,"headHash":"<64 lowercase hex>","chainValid":true,"pendingIndices":[]}
```
```http
GET http://localhost:8080/api/blockchain/audit
X-API-Key: <registered-client-key>
```
Пример формы ответа при расхождении:
```json
{"consistent":false,"reason":"HEAD_MISMATCH","nodes":[{"url":"http://localhost:8081","reachable":true,"status":{"height":1,"headHash":"<hash A>","chainValid":true,"pendingIndices":[]},"error":null},{"url":"http://localhost:8082","reachable":true,"status":{"height":2,"headHash":"<hash B>","chainValid":true,"pendingIndices":[]},"error":null},{"url":"http://localhost:8083","reachable":true,"status":{"height":1,"headHash":"<hash A>","chainValid":true,"pendingIndices":[]},"error":null}]}
```
Возможные причины: `CONSISTENT`, `NODE_UNREACHABLE`, `CHAIN_INVALID`, `PENDING_PROPOSALS`, `HEAD_MISMATCH`.

## Ограничения и безопасность
- `consistent=true` означает равенство локально вычисленной целостности и текущих head-хешей, **не** криптографический консенсус.
- Запись по-прежнему неатомарна. Сбой после частичного подтверждения может вызвать divergence; автоматическое исправление намеренно НЕ включено.
- Не открывайте демо-секреты и HTTP-порты в публичной сети.
- `pending_blocks` требует процедуры ручного разрешения; до её реализации новые записи могут быть заблокированы.
- Реальная интеграция и Gradle-тесты должны быть выполнены на машине с доступным Gradle.

## Windows
```powershell
.\gradlew.bat :node:test :fairchain:test
.\gradlew.bat :node:bootJar :fairchain:bootJar
.\scripts\run-three-nodes.ps1
```
