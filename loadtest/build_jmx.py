#!/usr/bin/env python3
"""loadtest/fairticket.jmx 를 만든다 (JMeter 5.6). 시나리오를 고칠 때는 이 파일을 고치고 다시 실행한다.

    python3 loadtest/build_jmx.py

시나리오 설명 · 실행 방법 · 통과 기준은 loadtest/README.md.
"""
from pathlib import Path
from xml.sax.saxutils import escape

OUT = Path(__file__).with_name('fairticket.jmx')


def sp(name, value):
    return f'<stringProp name="{name}">{escape(str(value))}</stringProp>'


def bp(name, value):
    return f'<boolProp name="{name}">{"true" if value else "false"}</boolProp>'


def node(xml, *children):
    """요소 하나 + 그 아래 요소들 (JMeter는 요소 뒤에 hashTree로 자식을 둔다)"""
    return xml + ('<hashTree>' + ''.join(children) + '</hashTree>' if children else '<hashTree/>')


def P(name, default):
    return '${__P(%s,%s)}' % (name, default)


# ---------------------------------------------------------------- 설정 요소

def test_plan(*children):
    variables = {
        'HOST': P('host', 'localhost'), 'PORT': P('port', '8080'), 'CONCERT': P('concertId', '2'), 'QWAIT': P('queueWait', '0'), 'TAG': P('tag', ''), 'OFFSET': P('userOffset', '0'), 'PREMADE': P('premade', 'false'),
        'OP_ID': P('operatorId', 'operator'), 'OP_PW': P('operatorPw', 'operator1234'),
        'RUN': '${__time(HHmmss,)}',          # 회원 아이디에 붙여 실행마다 새 회원을 만든다
    }
    args = ''.join(
        f'<elementProp name="{k}" elementType="Argument">{sp("Argument.name", k)}{sp("Argument.value", v)}'
        f'{sp("Argument.metadata", "=")}</elementProp>' for k, v in variables.items())
    return node(
        '<TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="FairTicket 부하 시나리오" enabled="true">'
        + sp('TestPlan.comments', '예제서버(8080) → FairChain(8081) → 노드. 설명은 loadtest/README.md')
        + bp('TestPlan.functional_mode', False) + bp('TestPlan.tearDown_on_shutdown', True)
        + bp('TestPlan.serialize_threadgroups', True)      # 시나리오를 차례로 실행
        + '<elementProp name="TestPlan.user_defined_variables" elementType="Arguments" guiclass="ArgumentsPanel" '
          'testclass="Arguments" testname="User Defined Variables" enabled="true">'
        + f'<collectionProp name="Arguments.arguments">{args}</collectionProp></elementProp></TestPlan>',
        *children)


def http_defaults():
    return node(
        '<ConfigTestElement guiclass="HttpDefaultsGui" testclass="ConfigTestElement" testname="HTTP 기본값" enabled="true">'
        '<elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" '
        'testname="User Defined Variables" enabled="true"><collectionProp name="Arguments.arguments"/></elementProp>'
        + sp('HTTPSampler.domain', '${HOST}') + sp('HTTPSampler.port', '${PORT}') + sp('HTTPSampler.protocol', 'http')
        + sp('HTTPSampler.contentEncoding', 'UTF-8') + sp('HTTPSampler.connect_timeout', '5000')
        + sp('HTTPSampler.response_timeout', '30000') + '</ConfigTestElement>')


def json_header():
    return node(
        '<HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="JSON 헤더" enabled="true">'
        '<collectionProp name="HeaderManager.headers"><elementProp name="" elementType="Header">'
        + sp('Header.name', 'Content-Type') + sp('Header.value', 'application/json')
        + '</elementProp></collectionProp></HeaderManager>')


def cookies():
    return node(
        '<CookieManager guiclass="CookiePanel" testclass="CookieManager" testname="세션 쿠키 (스레드마다 따로)" enabled="true">'
        '<collectionProp name="CookieManager.cookies"/>' + bp('CookieManager.clearEachIteration', False)
        + bp('CookieManager.controlledByThreadGroup', False) + '</CookieManager>')


def thread_group(name, threads, ramp, *children, loops='1', duration='', setup=False, teardown=False, comment=''):
    kind = 'SetupThreadGroup' if setup else 'PostThreadGroup' if teardown else 'ThreadGroup'
    gui = {'SetupThreadGroup': 'SetupThreadGroupGui', 'PostThreadGroup': 'PostThreadGroupGui', 'ThreadGroup': 'ThreadGroupGui'}[kind]
    return node(
        f'<{kind} guiclass="{gui}" testclass="{kind}" testname="{escape(name)}" enabled="true">'
        + sp('TestPlan.comments', comment) + sp('ThreadGroup.on_sample_error', 'continue')
        + '<elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" '
          'testclass="LoopController" testname="Loop Controller" enabled="true">'
        + bp('LoopController.continue_forever', False) + sp('LoopController.loops', loops) + '</elementProp>'
        + sp('ThreadGroup.num_threads', threads) + sp('ThreadGroup.ramp_time', ramp)
        + bp('ThreadGroup.scheduler', bool(duration)) + sp('ThreadGroup.duration', duration) + sp('ThreadGroup.delay', '')
        + bp('ThreadGroup.same_user_on_next_iteration', True) + f'</{kind}>',
        *children)


# ---------------------------------------------------------------- 샘플러 · 제어 요소

def http(name, method, path, *children, body=None, timeout=None):
    arguments = ''
    if body is not None:
        arguments = ('<elementProp name="" elementType="HTTPArgument">' + bp('HTTPArgument.always_encode', False)
                     + sp('Argument.value', body) + sp('Argument.metadata', '=') + '</elementProp>')
    return node(
        f'<HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="{escape(name)}" enabled="true">'
        + bp('HTTPSampler.postBodyRaw', body is not None)
        + '<elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" '
          f'testname="User Defined Variables" enabled="true"><collectionProp name="Arguments.arguments">{arguments}</collectionProp></elementProp>'
        + sp('HTTPSampler.path', path) + sp('HTTPSampler.method', method) + bp('HTTPSampler.follow_redirects', True)
        + bp('HTTPSampler.use_keepalive', True) + (sp('HTTPSampler.response_timeout', timeout) if timeout else '')
        + '</HTTPSamplerProxy>',
        *children)


def groovy(kind, name, script):
    """kind: JSR223PostProcessor · JSR223PreProcessor · JSR223Sampler"""
    return node(
        f'<{kind} guiclass="TestBeanGUI" testclass="{kind}" testname="{escape(name)}" enabled="true">'
        + sp('scriptLanguage', 'groovy') + sp('parameters', '') + sp('filename', '') + sp('cacheKey', 'true')
        + sp('script', script.strip()) + f'</{kind}>')


def post(name, script):
    return groovy('JSR223PostProcessor', name, script)


def json_extract(var, path, default='NONE'):
    return node(
        f'<JSONPostProcessor guiclass="JSONPostProcessorGui" testclass="JSONPostProcessor" testname="{var} 뽑기" enabled="true">'
        + sp('JSONPostProcessor.referenceNames', var) + sp('JSONPostProcessor.jsonPathExprs', path)
        + sp('JSONPostProcessor.match_numbers', '1') + sp('JSONPostProcessor.defaultValues', default) + '</JSONPostProcessor>')


def while_(name, condition, *children):
    return node(
        f'<WhileController guiclass="WhileControllerGui" testclass="WhileController" testname="{escape(name)}" enabled="true">'
        + sp('WhileController.condition', '${__jexl3(%s,)}' % condition) + '</WhileController>', *children)


def if_(name, condition, *children):
    return node(
        f'<IfController guiclass="IfControllerPanel" testclass="IfController" testname="{escape(name)}" enabled="true">'
        + sp('IfController.condition', '${__jexl3(%s,)}' % condition) + bp('IfController.evaluateAll', False)
        + bp('IfController.useExpression', True) + '</IfController>', *children)


def transaction(name, *children):
    return node(
        f'<TransactionController guiclass="TransactionControllerGui" testclass="TransactionController" testname="{escape(name)}" enabled="true">'
        + bp('TransactionController.includeTimers', True) + bp('TransactionController.parent', False)
        + '</TransactionController>', *children)


def wait(ms):
    return node(f'<ConstantTimer guiclass="ConstantTimerGui" testclass="ConstantTimer" testname="{ms}ms 쉬기" enabled="true">'
                + sp('ConstantTimer.delay', ms) + '</ConstantTimer>')


def think(base, spread):
    return node('<UniformRandomTimer guiclass="UniformRandomTimerGui" testclass="UniformRandomTimer" testname="생각하는 시간" enabled="true">'
                + sp('ConstantTimer.delay', base) + sp('RandomTimer.range', spread) + '</UniformRandomTimer>')


def all_at_once(name, timeout_ms=30000):
    """그룹의 스레드가 모두 이 자리에 올 때까지 기다렸다가 한꺼번에 보낸다 (timeout_ms 지나면 온 만큼만)"""
    return node(f'<SyncTimer guiclass="TestBeanGUI" testclass="SyncTimer" testname="{escape(name)}" enabled="true">'
                '<stringProp name="groupSize">${__P(syncGroup,0)}</stringProp><longProp name="timeoutInMs">' + str(timeout_ms) + '</longProp></SyncTimer>')


def summary():
    return node('<ResultCollector guiclass="SummaryReport" testclass="ResultCollector" testname="요약 보고서 (GUI에서 볼 때)" enabled="true">'
                + bp('ResultCollector.error_logging', False)
                + '<objProp><name>saveConfig</name><value class="SampleSaveConfiguration"><time>true</time><latency>true</latency>'
                  '<timestamp>true</timestamp><success>true</success><label>true</label><code>true</code><message>true</message>'
                  '<threadName>true</threadName><dataType>true</dataType><encoding>false</encoding><assertions>true</assertions>'
                  '<subresults>true</subresults><responseData>false</responseData><samplerData>false</samplerData><xml>false</xml>'
                  '<fieldNames>true</fieldNames><responseHeaders>false</responseHeaders><requestHeaders>false</requestHeaders>'
                  '<responseDataOnError>false</responseDataOnError><saveAssertionResultsFailureMessage>true</saveAssertionResultsFailureMessage>'
                  '<assertionsResultsToSave>0</assertionsResultsToSave><bytes>true</bytes><sentBytes>true</sentBytes><url>true</url>'
                  '<threadCounts>true</threadCounts><idleTime>true</idleTime><connectTime>true</connectTime></value></objProp>'
                + sp('filename', '') + '</ResultCollector>')


# ---------------------------------------------------------------- 되풀이되는 묶음

def join_and_login(prefix, tag):
    """새 회원을 만들고 로그인한다. 아이디는 실행 시각 + 스레드 번호라 실행마다 새 회원이다"""
    login_id = '%s${RUN}_${__threadNum}' % tag
    return transaction(
        f'{prefix} 준비: 회원가입 + 로그인',
        http(f'{prefix} 회원가입', 'POST', '/api/members',
             body='{"loginId":"%s","password":"load1234","name":"부하%s${__threadNum}","contact":"010-0000-0000"}' % (login_id, tag)),
        http(f'{prefix} 로그인', 'POST', '/api/members/login', body='{"loginId":"%s","password":"load1234"}' % login_id))


LOGIN_ID = '''
// -Jpremade=true 면 서버가 켜질 때 만들어 둔 회원(load1 … loadN, EXAMPLE_LOAD_MEMBERS)을 쓴다.
// JMeter를 여러 개 띄울 때는 -JuserOffset 으로 번호가 겹치지 않게 한다. 아니면 준비 단계에서 가입시킨 회원
def n = ctx.getThreadNum() + 1
vars.put('loginId', props.getProperty('premade', 'false') == 'true'
    ? 'load' + ((vars.get('OFFSET') as int) + n)
    : '%s' + vars.get('RUN') + vars.get('TAG') + '_' + n)
'''


def join_only(tag, name_tag):
    """회원가입만 (준비 단계). 아이디는 login_only와 같은 규칙"""
    return http('준비 회원가입', 'POST', '/api/members',
                body='{"loginId":"%s${RUN}${TAG}_${__threadNum}","password":"load1234","name":"부하%s${__threadNum}","contact":"010-0000-0000"}' % (tag, name_tag))


def login_only(prefix, tag):
    """준비 단계에서 미리 만든 회원으로 로그인만 한다"""
    return http(f'{prefix} 로그인 (미리 만든 회원)', 'POST', '/api/members/login',
                groovy('JSR223PreProcessor', '회원 아이디 정하기', LOGIN_ID % tag), body='{"loginId":"${loginId}","password":"load1234"}')


QUEUE_STATE = '''
// 대기열 응답에서 상태를 읽고, 이 뒤 단계에서 쓸 변수를 처음 값으로 맞춘다
def state = 'NONE'
def next = null
try {
    def body = new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString())
    state = body.state ?: 'NONE'
    next = body.nextPollMs
} catch (e) { }
vars.put('qstate', state)
// 다음 순번 확인까지 쉬는 시간: 실제 화면처럼 서버가 알려 준 간격(nextPollMs — 앞 순번 1초, 뒤 순번 최대 15초)을 따른다.
// -JpollMs=1000 처럼 주면 그 간격으로 고정한다 (간격을 지키지 않는 매크로 흉내 — 예제서버가 직전 답을 돌려주는지 볼 때)
def fixed = (props.getProperty('pollMs', '0') as int)
vars.put('qnext', (fixed > 0 ? fixed : (next ?: 1000)) as String)
vars.put('qtry', ((vars.get('qtry') ?: '0') as int) + 1 as String)
// 줄에 선 뒤 지난 초 (-JqueueWait 비교용 — 확인 간격이 순번마다 달라 횟수로는 셀 수 없다)
vars.put('qwaited', ((System.currentTimeMillis() - ((vars.get('qstart') ?: '0') as long)) / 1000) as long as String)
// 서버가 계속 실패로 답하면(죽었거나 공연이 없음) 끝없이 기다리지 않도록 연속 실패 횟수를 센다
// 대기열 진입 요청이 실패한 사람은 줄에 없어서 순번 확인이 200으로 오지만 영영 입장하지 못한다 — 그것도 실패로 센다
def inLine = prev.isSuccessful() && (state == 'WAITING' || state == 'ADMITTED')
vars.put('qfail', inLine ? '0' : ((vars.get('qfail') ?: '0') as int) + 1 as String)
'''

INIT_VARS = '''
vars.put('qtry', '0'); vars.put('qfail', '0'); vars.put('qstate', 'NONE'); vars.put('qnext', '1000'); vars.put('qwaited', '0')
vars.put('qstart', System.currentTimeMillis() as String); vars.put('rtry', '0'); vars.put('reserved', 'false')
vars.put('seatId', ''); vars.put('ticketId', ''); vars.put('vtry', '0'); vars.put('vstate', 'NONE')
'''


WAIT_UNTIL = '''
// -JenterAt=<시각(ms)> 을 주면 그 시각까지 기다린다. JMeter 한 개가 만들 수 있는 스레드 수에 한도가 있어
// (macOS는 프로세스당 약 6천 개) 여러 개를 띄울 때, 모두 같은 순간에 대기열에 들어가게 하려는 것이다
SampleResult.setIgnore()
def at = (props.getProperty('enterAt', '0') as long)
def left = at - System.currentTimeMillis()
if (left > 0) Thread.sleep(left)
'''


def enter_queue_and_wait(prefix, rush=None):
    """대기열에 들어가 입장 허용(ADMITTED)이 될 때까지, 서버가 알려 준 간격(nextPollMs)마다 순번을 확인한다.
    입장해야 재배정 명단에 오르므로 기본은 입장할 때까지 기다린다 (-JqueueWait 초를 주면 그만큼만)"""
    enter_children = [groovy('JSR223PreProcessor', '변수 초기화', INIT_VARS), post('대기열 상태 읽기', QUEUE_STATE)]
    if rush:
        enter_children.insert(0, all_at_once(rush, 600000))    # 수천 명이 로그인을 마칠 때까지 (최대 10분)
    gate = [groovy('JSR223Sampler', f'{prefix} 정해진 시각까지 대기 (enterAt)', WAIT_UNTIL)] if rush else []
    return gate + [
        http(f'{prefix} 대기열 진입', 'POST', '/api/queue/${CONCERT}', *enter_children, body='{}'),
        while_(f'{prefix} 입장 허용까지 대기', '"${qstate}" != "ADMITTED" && ${qfail} < 30 && (${QWAIT} == 0 || ${qwaited} < ${QWAIT})',
               http(f'{prefix} 대기 순번 확인', 'GET', '/api/queue/${CONCERT}/status', wait('${qnext}'), post('대기열 상태 읽기', QUEUE_STATE))),
    ]


# ---------------------------------------------------------------- 시나리오

SETUP_SEATS = '''
// 좌석이 모자라면 한 줄에 20석씩 새 줄을 만들어 등록 요청 본문을 만든다.
// 줄 이름은 L + 글자(LA, LB, …)이고 지금 좌석 수에서 번호를 시작해서, 이미 있는 줄이나 앞선 실행의 줄과 겹치지 않는다
def concert = new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString())
def need = (props.getProperty('seats', '150') as int) - (concert.emptySeats as int)
vars.put('gradeId', concert.seatGrades[0].id as String)
vars.put('needSeats', Math.max(0, need) as String)
def rowName = { int i -> def s = ''; i++; while (i > 0) { i--; s = String.valueOf((char) (65 + i % 26)) + s; i = i.intdiv(26) }; s }
def firstRow = concert.totalSeats as int
def ranges = []
for (int k = 0; k * 20 < need; k++) { def r = 'L' + rowName(firstRow + k); ranges << '"' + r + '1-' + r + Math.min(20, need - k * 20) + '"' }
vars.put('seatRanges', ranges.join(','))
if (!concert.open) { prev.setSuccessful(false); prev.setResponseMessage('이 공연은 아직 예매 오픈 전이라 시나리오를 돌릴 수 없습니다 — concertId를 바꾸세요') }
'''

SETUP_RACE_SEAT = '''
// 시나리오 B에서 모두가 동시에 노릴 좌석 하나를 정해 둔다 (예매 가능한 첫 좌석)
def seats = new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString()).seats
def empty = seats.findAll { it.status == 'EMPTY' }
props.put('raceSeatId', empty ? empty[0].seatId as String : '')
props.put('raceWins', new java.util.concurrent.atomic.AtomicInteger(0))
log.info('준비 끝: 예매 가능 좌석 ' + empty.size() + '석, 경합 좌석 ID ' + props.get('raceSeatId'))
if (!empty) { prev.setSuccessful(false); prev.setResponseMessage('예매 가능한 좌석이 없습니다') }
'''

PICK_SEAT = '''
// 예매 가능한 좌석 중 하나를 무작위로 고른다 (실제 사용자가 서로 다른 좌석을 누르는 것처럼). 없으면 매진으로 끝낸다
def seats = new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString()).seats
def empty = seats.findAll { it.status == 'EMPTY' }
vars.put('rtry', ((vars.get('rtry') ?: '0') as int) + 1 as String)
if (empty) {
    vars.put('seatId', empty[new Random().nextInt(empty.size())].seatId as String)
} else {
    vars.put('seatId', ''); vars.put('rtry', '99')
    prev.setSampleLabel(prev.getSampleLabel() + ' — 매진이라 포기')
}
'''

RESERVE_RESULT = '''
// 예매 결과: 성공이면 티켓 번호를 기억한다. 다른 사람이 먼저 잡은 좌석이면 정상적인 거절이므로 실패로 세지 않고 다시 고른다
def code = prev.getResponseCode()
def r = null
try { r = new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString()) } catch (e) { }
if (code == '200' && r?.success) {
    vars.put('reserved', 'true'); vars.put('ticketId', r.ticketId as String)
} else if (code == '200' && r != null) {
    prev.setSampleLabel(prev.getSampleLabel() + ' — 선점 실패 (다시 고름)')
    prev.setSuccessful(true)
}
'''

VERIFY_STATE = '''
def state = 'NONE'
try { state = new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString()).verificationStatus ?: 'NONE' } catch (e) { }
vars.put('vstate', state)
vars.put('vtry', ((vars.get('vtry') ?: '0') as int) + 1 as String)
'''

RACE_RESULT = '''
// 같은 좌석을 동시에 노렸으니 한 명만 성공해야 한다. 나머지의 거절은 정상이라 실패로 세지 않는다
def r = null
try { r = new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString()) } catch (e) { }
if (prev.getResponseCode() == '200' && r?.success) {
    props.get('raceWins').incrementAndGet()
    prev.setSampleLabel(prev.getSampleLabel() + ' — 성공')
} else if (prev.getResponseCode() == '200' && r != null) {
    prev.setSampleLabel(prev.getSampleLabel() + ' — 거절 (정상)')
    prev.setSuccessful(true)
}
'''

RACE_CHECK = '''
// 통과 기준: 같은 좌석에 성공한 사람이 정확히 1명
def wins = props.get('raceWins')?.get() ?: 0
def racers = props.getProperty('raceUsers', '20') as int
SampleResult.setSampleLabel('B 판정: 같은 좌석 예매 성공 ' + wins + '명 (기준 1명)')
SampleResult.setSuccessful(racers == 0 || wins == 1)
SampleResult.setResponseMessage('성공 ' + wins + '명 / 시도 ' + racers + '명')
SampleResult.setResponseData('성공 ' + wins + '명 / 시도 ' + racers + '명', 'UTF-8')
'''

MACRO_RESULT = '''
// 한 회원이 쉬지 않고 요청하면 FairChain이 초당 한도(기본 5회)를 넘는 요청을 거절한다.
// 예제서버는 그 거절을 502 + "요청이 너무 많습니다"로 전달한다. 이 시나리오에서는 그 거절이 기대한 결과다
def body = prev.getResponseDataAsString()
if (prev.getResponseCode() == '200') {
    prev.setSampleLabel(prev.getSampleLabel() + ' — 통과')
} else if (body.contains('요청이 너무 많습니다')) {
    prev.setSampleLabel(prev.getSampleLabel() + ' — 차단됨 (정상)')
    prev.setSuccessful(true)
}
'''


def setup_group():
    return thread_group(
        '준비: 좌석 등록 (운영자)', '1', '1',
        cookies(),
        http('준비 운영자 로그인', 'POST', '/api/operator/login', body='{"loginId":"${OP_ID}","password":"${OP_PW}"}'),
        http('준비 공연 확인', 'GET', '/api/concerts/${CONCERT}', post('모자란 좌석 계산', SETUP_SEATS)),
        if_('좌석이 모자라면 등록', '${needSeats} > 0',
            http('준비 좌석 등록 (좌석마다 FairChain 발행 기록)', 'POST', '/api/operator/seats',
                 body='{"concertId":${CONCERT},"seatGradeId":${gradeId},"seatNumbers":[${seatRanges}]}', timeout='180000')),
        http('준비 좌석 목록', 'GET', '/api/concerts/${CONCERT}/seats', post('경합 좌석 정하기', SETUP_RACE_SEAT)),
        setup=True, comment='예매 가능한 좌석이 -Jseats 보다 적으면 모자란 만큼 등록한다')


def setup_users():
    """준비: A에 쓸 회원을 미리 만든다 — 가입(비밀번호 해시)에 걸리는 시간이 '동시에 진입'을 흩뜨리지 않게"""
    return thread_group(
        '준비: A 회원 미리 만들기', P('users', '100'), P('rampUp', '5'),
        if_('서버가 미리 만든 회원을 쓰지 않을 때만', '"${PREMADE}" != "true"', join_only('a', 'a')),
        setup=True, comment='-Jusers 명의 회원을 미리 가입시킨다 (A는 로그인만 한다)')


def scenario_a():
    """A. 예매 오픈 폭주 — 모두가 같은 순간에 대기열에 들어가 좌석을 잡고, 티켓이 블록체인 검증 완료가 될 때까지"""
    return thread_group(
        'A. 예매 오픈 폭주', P('users', '100'), P('rampUp', '5'),
        cookies(),
        login_only('A-0', 'a'),
        *enter_queue_and_wait('A-1', rush='모두 모이면 동시에 진입'),
        if_('입장 허용됨', '"${qstate}" == "ADMITTED"',
            while_('A-2 좌석 잡기 (최대 5번)', '"${reserved}" != "true" && ${rtry} < 5',
                   http('A-2 좌석 조회', 'GET', '/api/concerts/${CONCERT}/seats', think(300, 700), post('좌석 고르기', PICK_SEAT)),
                   if_('고른 좌석이 있으면', '"${seatId}" != ""',
                       http('A-3 예매', 'POST', '/api/reservations',
                            post('예매 결과', RESERVE_RESULT), body='{"seatId":${seatId},"concertId":${CONCERT}}'))),
            if_('예매 성공', '"${reserved}" == "true"',
                transaction('A-4 예매 후 블록체인 검증 완료까지',
                            while_('검증 완료까지 확인 (최대 60초)', '"${vstate}" != "VERIFIED" && ${vtry} < 120',
                                   http('A-4 내 티켓 확인', 'GET', '/api/tickets/${ticketId}', wait(500), post('검증 상태 읽기', VERIFY_STATE)))))),
        comment='-Jusers 명이 미리 만든 회원으로 -JrampUp 초에 걸쳐 로그인한 뒤 한꺼번에 대기열 진입')


def scenario_b():
    """B. 같은 좌석 동시 예매 — 입장한 사람들이 같은 좌석 하나를 동시에 예매한다. 한 명만 성공해야 한다"""
    return thread_group(
        'B. 같은 좌석 동시 예매', P('raceUsers', '20'), '2',
        cookies(),
        join_and_login('B-0', 'b'),
        *enter_queue_and_wait('B-1'),
        if_('입장 허용됨', '"${qstate}" == "ADMITTED"',
            http('B-2 같은 좌석 예매', 'POST', '/api/reservations', all_at_once('모두 모이면 동시에 예매'),
                 post('경합 결과', RACE_RESULT), body='{"seatId":${__P(raceSeatId,0)},"concertId":${CONCERT}}')),
        comment='-JraceUsers 명이 준비 단계에서 정한 좌석 하나를 동시에 예매')


def scenario_c():
    """C. 매크로 — 회원 한 명이 쉬지 않고 대기열 요청을 보낸다. 초당 한도를 넘는 요청은 차단되어야 한다"""
    return thread_group(
        'C. 매크로 (한 회원의 반복 요청)', P('macroUsers', '1'), '1',
        cookies(),
        join_and_login('C-0', 'c'),
        node('<LoopController guiclass="LoopControlPanel" testclass="LoopController" testname="쉬지 않고 반복" enabled="true">'
             + bp('LoopController.continue_forever', True) + sp('LoopController.loops', P('macroLoops', '100')) + '</LoopController>',
             http('C-1 대기열 반복 요청', 'POST', '/api/queue/${CONCERT}', post('차단 여부', MACRO_RESULT), body='{}')),
        comment='-JmacroUsers 명이 각자 -JmacroLoops 번 연속 요청')


def scenario_d():
    """D. 둘러보기 — 로그인 없이 목록 · 상세 · 좌석 현황을 본다 (FairChain을 거치지 않는 요청의 기준 응답 시간)"""
    return thread_group(
        'D. 둘러보기 (조회만)', P('browseUsers', '0'), '5',
        http('D-1 공연 목록', 'GET', '/api/concerts?keyword=', think(200, 600)),
        http('D-2 공연 상세', 'GET', '/api/concerts/${CONCERT}', think(200, 600)),
        http('D-3 좌석 현황', 'GET', '/api/concerts/${CONCERT}/seats', think(200, 600)),
        loops='-1', duration=P('browseSeconds', '30'),
        comment='-JbrowseUsers 명이 -JbrowseSeconds 초 동안 (기본 0명 = 건너뜀)')


def teardown_group():
    return thread_group('판정', '1', '1', groovy('JSR223Sampler', 'B 판정', RACE_CHECK), teardown=True)


def build():
    plan = test_plan(http_defaults(), json_header(), setup_group(), setup_users(), scenario_b(), scenario_a(), scenario_c(), scenario_d(),
                     teardown_group(), summary())
    xml = ('<?xml version="1.0" encoding="UTF-8"?>\n<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3"><hashTree>'
           + plan + '</hashTree></jmeterTestPlan>\n')
    OUT.write_text(xml, encoding='utf-8')
    print(f'{OUT} ({len(xml):,}자)')


if __name__ == '__main__':
    build()
