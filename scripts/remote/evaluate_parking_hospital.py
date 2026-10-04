"""Visitor-only hospital, recommendation and navigation regression; no private credentials."""
import argparse
import json
import time
import uuid
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path


def evaluate(base: str, weather: bool = False):
    def request(path, body):
        request = urllib.request.Request(
            base + path, data=json.dumps(body).encode(),
            headers={'Content-Type': 'application/json'}, method='POST',
        )
        with urllib.request.urlopen(request, timeout=115) as response:
            return json.load(response)

    token = request('/login', {'username': 'visitor', 'password': ''})['token']
    results = []

    def stream(question, history=None, state=None):
        turns = (history or []) + [{'role': 'user', 'content': question}]
        payload = {
            'protocolVersion': '1.0', 'threadId': str(uuid.uuid4()),
            'runId': str(uuid.uuid4()), 'messages': turns, 'tools': [], 'context': [],
            'state': {'sceneReady': True, 'destination': 'outpatient',
                      'preference': 'standard', **(state or {})}, 'forwardedProps': {},
        }
        req = urllib.request.Request(
            base + '/ag-ui', data=json.dumps(payload).encode(),
            headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token},
        )
        events = []
        with urllib.request.urlopen(req, timeout=115) as response:
            for line in response:
                if line.startswith(b'data:'):
                    event = json.loads(line[5:])
                    events.append(event)
                    if event['type'] in ('RUN_FINISHED', 'RUN_ERROR'):
                        break
        assert events and events[-1]['type'] == 'RUN_FINISHED', events[-1:]
        custom = {e['name']: e['value'] for e in events if e['type'] == 'CUSTOM'}
        answer = ''.join(e['delta'] for e in events if e['type'] == 'TEXT_MESSAGE_CONTENT')
        assert answer and 'network error' not in answer.lower()
        assert all(identity not in answer for identity in ('常州市中医医院', '武进中医院', '和平北路', '0519-'))
        return custom, answer

    def check(name, question, assertion, history=None, state=None):
        start = time.monotonic()
        try:
            values, answer = stream(question, history, state)
            assertion(values, answer)
            results.append({'name': name, 'passed': True, 'elapsedMs': round((time.monotonic()-start)*1000),
                            'provider': values['parking.plan']['provider'], 'answer': answer,
                            'actions': values['parking.plan']['actions'],
                            'references': values.get('parking.references', [])})
        except Exception as error:
            results.append({'name': name, 'passed': False, 'elapsedMs': round((time.monotonic()-start)*1000),
                            'error': str(error)[:500]})

    def recommendation(values, answer):
        assert values['parking.plan']['provider'] == 'deterministic-business'
        data = values['parking.report']['data']
        assert data['destination'] == 'inpatient' and data['preference'] == 'charging'
        assert data['candidates'] and all(c['zone'] == 'B' for c in data['candidates'])
        assert '模拟' in answer

    def guide(fragment):
        def assertion(values, answer):
            assert values['parking.plan']['provider'] == 'hospital-guide'
            assert fragment in answer and values['parking.references']
            assert all(r['title'].startswith('示范') for r in values['parking.references'])
        return assertion

    def route(source, destination):
        def assertion(values, answer):
            path = values['parking.route']
            assert path['from'] == source and path['to'] == destination
            assert len(path['points']) >= 2 and path['meters'] > 0
            assert {'type': 'route.show', 'target': destination} in values['parking.plan']['actions']
        return assertion

    def clear(values, answer):
        assert values['parking.plan']['actions'] == [
            {'type': 'route.clear', 'target': 'campus'}, {'type': 'tour.stop', 'target': 'campus'}]

    check('natural charging recommendation', '我要去住院楼，帮我推荐一个可以充电的停车区', recommendation)
    check('follow-up keeps parking preference', '那住院楼呢', recommendation,
          history=[{'role': 'user', 'content': '推荐去门诊的停车区'},
                   {'role': 'assistant', 'content': '建议查看门诊附近停车区'}],
          state={'destination': 'outpatient', 'preference': 'charging'})
    check('registration evidence', '医院怎么预约挂号', guide('示范挂号流程'))
    check('department alias and fictitious physician', '骨科怎么挂号', guide('示例医师庚'))
    check('ward lookup is anonymized', '心内科的病房在哪', guide('住院楼3层'))
    check('entrance to outpatient route', '从大门到门诊楼入口怎么走', route('entrance', 'outpatient'))
    check('B parking to inpatient route', '从B区到住院楼怎么走', route('parking-b', 'inpatient'))
    check('ward destination takes priority over outpatient specialty', '导航到心内科护士站', route('entrance', 'inpatient'))
    check('route end clears both navigation and tour', '结束导航', clear)
    if weather:
        def current_weather(values, answer):
            today = datetime.now(timezone(timedelta(hours=8))).date().isoformat()
            assert values['parking.plan']['provider'] == 'weather-mcp'
            assert today in answer and '常州' in answer and 'Open-Meteo' in answer and '℃' in answer
        check('Changzhou weather has current local date and source', '今天医院天气怎么样', current_weather)
    return {'apiBase': base, 'cases': len(results), 'passed': sum(r['passed'] for r in results), 'results': results}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--api-base', default='https://caibinice.com/smartCockpit/api/parking')
    parser.add_argument('--weather', action='store_true')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = evaluate(args.api_base.rstrip('/'), args.weather)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'cases': result['cases'], 'passed': result['passed'],
                      'failed': [r for r in result['results'] if not r['passed']]}, ensure_ascii=False))
    raise SystemExit(0 if result['cases'] == result['passed'] else 1)
