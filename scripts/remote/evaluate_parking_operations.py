"""Reproducible API checks. Role secrets stay in a private accounts file, never in results."""
import argparse,json,time,uuid,urllib.request,urllib.error
from pathlib import Path
from deploy_cockpit import read_action_auth

def evaluate(base,accounts,llm=False,workflow=False):
    results=[];sessions={}
    def request(path,role=None,body=None,method=None,token=None):
        headers={'Content-Type':'application/json'}
        if role or token:headers['Authorization']='Bearer '+(token or sessions[role]['token'])
        req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method or ('POST' if body is not None else 'GET'))
        try:
            with urllib.request.urlopen(req,timeout=115) as response:return response.status,json.load(response)
        except urllib.error.HTTPError as error:return error.code,json.loads(error.read() or '{}')
    def check(name,call):
        at=time.monotonic()
        try:detail=call();results.append({'name':name,'passed':True,'elapsedMs':round((time.monotonic()-at)*1000),'detail':detail})
        except Exception as error:results.append({'name':name,'passed':False,'elapsedMs':round((time.monotonic()-at)*1000),'error':str(error)[:500]})
    def expect_status(path,role,expected,body=None):
        code,_=request(path,role,body);assert code==expected,(path,code,expected)
    for account in [{'username':'visitor','password':'','role':'visitor'},{'username':'admin','password':read_action_auth()['password'],'role':'admin'}]+accounts:
        code,auth=request('/login',body={'username':account['username'],'password':account['password']});assert code==200,('login',account['role'],code);sessions[account['role']]=auth
    check('anonymous snapshot requires authentication',lambda:expect_status('/snapshot',None,401))
    for path in ['/analytics?from=2025-10-03&to=2026-10-02','/stays','/workorders','/audit','/report-jobs']:
        check('visitor denied '+path,lambda p=path:expect_status(p,'visitor',403))
    check('visitor denied vision',lambda:expect_status('/vision','visitor',403,{'question':'图中有什么','screenshot':'data:image/jpeg;base64,x','confirmed':True}))
    check('security denied yearly ledger',lambda:expect_status('/analytics?from=2025-10-03&to=2026-10-02','security',403))
    check('security denied user provisioning',lambda:expect_status('/users','security',403,{'username':'should_not_exist','role':'operator','displayName':'无效权限测试','password':'not-stored-password'}))
    def redaction():
        code,value=request('/snapshot','visitor');assert code==200 and value['source']=='database-synthetic' and not value['events'] and not value['alerts'];assert len(value['zones'])==3
        assert all(0<=z['occupied']<=z['capacity'] for z in value['zones']);return {'sampledAt':value['sampledAt'],'free':sum(z['capacity']-z['occupied'] for z in value['zones'])}
    check('visitor redacted database snapshot',redaction)
    def tampering():
        token=sessions['security']['token'].replace('.security.','.operator.');code,_=request('/snapshot',token=token);assert code==401
    check('signed role rejects tampering',tampering)
    def ledger():
        code,total=request('/analytics?from=2025-10-03&to=2026-10-02','operator');assert code==200 and len(total['occupancy'])==1095
        assert sum(row['paid_cents'] for row in total['dailyLedger'])==total['ledger']['paid_cents']
        zones=[request('/analytics?from=2025-10-03&to=2026-10-02&zone='+zone,'operator')[1]['ledger'] for zone in 'ABC'];assert sum(z['paid_cents'] for z in zones)==total['ledger']['paid_cents']
        return total['ledger']
    check('yearly money equals daily and zone ledger sums',ledger)
    check('invalid date range rejected',lambda:expect_status('/analytics?from=2024-01-01&to=2026-10-02','operator',400))
    check('invalid pagination rejected',lambda:expect_status('/stays?page=0&size=500','operator',400))
    def preference(pref,zone):
        code,data=request('/recommendation?destination=emergency&preference='+pref,'visitor');assert code==200 and data['candidates'] and all(c['zone']==zone for c in data['candidates']);return {'zones':[c['zone'] for c in data['candidates']]}
    check('charging matches B capability',lambda:preference('charging','B'))
    check('emergency matches C capability',lambda:preference('emergency','C'))
    check('unknown POI rejected',lambda:expect_status('/route?to=nonexistent','visitor',400))
    def path():
        code,data=request('/route?to=emergency','visitor');assert code==200 and len(data['points'])>=3 and data['meters']>0;return {'points':len(data['points']),'meters':data['meters'],'version':data['version']}
    check('open graph returns bounded path',path)
    def stream(question,role,expected=None,model='deepseek-v4-flash'):
        input={'protocolVersion':'1.0','threadId':str(uuid.uuid4()),'runId':str(uuid.uuid4()),'messages':[{'id':'m1','role':'user','content':question}],'tools':[],'context':[],'state':{'sceneReady':True,'model':model,'destination':'outpatient','preference':'standard'},'forwardedProps':{}}
        req=urllib.request.Request(base+'/ag-ui',data=json.dumps(input).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+sessions[role]['token']},method='POST');events=[];start=time.monotonic();first=None
        with urllib.request.urlopen(req,timeout=115) as response:
            for line in response:
                if line.startswith(b'data:'):
                    if first is None:first=round((time.monotonic()-start)*1000)
                    events.append(json.loads(line[5:]))
        assert events[0]['type']=='RUN_STARTED' and events[-1]['type']=='RUN_FINISHED',events[-1]
        plan=next(e['value'] for e in events if e['type']=='CUSTOM' and e['name']=='parking.plan')
        calls=[(e['toolCallName'],e['toolCallId']) for e in events if e['type']=='TOOL_CALL_START'];args=[json.loads(e['delta'])['target'] for e in events if e['type']=='TOOL_CALL_ARGS']
        if expected:assert expected in list(zip([c[0] for c in calls],args)),(question,calls,args)
        if model and llm and expected is None and role!='visitor':assert plan['provider'] not in ['local-guide-fallback','local-visitor-guide'],plan['provider'];assert calls
        refs=[e['value'] for e in events if e['type']=='CUSTOM' and e['name']=='parking.references']
        return {'firstEventMs':first,'runId':input['runId'],'provider':plan['provider'],'actions':list(zip([c[0] for c in calls],args)),'references':len(refs[0]) if refs else 0,'events':len(events)}
    for question,role,target in [('查看停车报表','visitor',('report.show','occupancy')),('推荐停车区','visitor',('report.show','recommendation')),('开始访客导览','visitor',('tour.start','visitor')),('开始运营巡检','security',('tour.start','operations')),('开始夜间巡检','security',('tour.start','night')),('查看日经营报表','operator',('report.show','daily')),('查看周经营报表','operator',('report.show','weekly')),('查看月经营报表','operator',('report.show','monthly')),('查看年经营报表','operator',('report.show','yearly')),('准备告警工单','security',('workorder.prepare','latest'))]:
        check('AG-UI '+question,lambda q=question,r=role,t=target:stream(q,r,t))
    check('visitor knowledge-only answer',lambda:stream('充电与无障碍车位怎么推荐？','visitor'))
    if llm:
        check('Flash plans scene and annual report',lambda:stream('请先展示急诊楼入口，然后让我查看全年的收费账本。','operator'))
        check('Business query grounds charging recommendation without model wait',lambda:stream('我要去住院楼，汽车需要充电，帮我选合适停车区并显示道路。','security'))
    if workflow:
        def workorder():
            snapshot=request('/snapshot','security')[1];alert=next(a for a in snapshot['alerts'] if a['status']=='open');key=str(uuid.uuid4());body={'alertId':alert['id'],'requestKey':key,'note':'自动回归：合成告警核验，不代表现场处置','confirmed':True}
            code,w=request('/workorders','security',body);assert code==200
            assert request('/workorders','security',body)[1]['id']==w['id']
            expect_status('/workorders/'+str(w['id'])+'/transition','security',403,{'version':0,'action':'approve','note':'权限测试','confirmed':True})
            version=0
            for action,role in [('approve','operator'),('assign','security'),('resolve','security'),('close','operator')]:
                code,result=request('/workorders/'+str(w['id'])+'/transition',role,{'version':version,'action':action,'note':'自动回归：合成工单闭环核验','confirmed':True});assert code==200,(action,code);version=result['version']
            assert result['status']=='closed'
            assert request('/workorders','security',body)[1]['id']==w['id']
            body['requestKey']=str(uuid.uuid4());expect_status('/workorders','security',409,body)
            return {'id':w['id'],'status':'closed','version':version,'staleDraftRejected':True}
        check('confirmed workorder approval and audit lifecycle',workorder)
    return {'apiBase':base,'cases':len(results),'passed':sum(r['passed'] for r in results),'results':results}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--api-base',default='https://caibinice.com/smartCockpit/api/parking');p.add_argument('--accounts-file',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--llm',action='store_true');p.add_argument('--workflow',action='store_true');a=p.parse_args()
    result=evaluate(a.api_base.rstrip('/'),json.loads(a.accounts_file.read_text(encoding='utf-8')),a.llm,a.workflow);a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps({'cases':result['cases'],'passed':result['passed'],'failed':[r for r in result['results'] if not r['passed']]},ensure_ascii=False));raise SystemExit(0 if result['cases']==result['passed'] else 1)
