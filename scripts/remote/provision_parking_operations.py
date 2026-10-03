"""Seed the isolated knowledge, graph and named roles using the existing admin password."""
import argparse,json,secrets,urllib.request
from pathlib import Path
from deploy_cockpit import read_action_auth

def post(base,path,body,token=''):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    request=urllib.request.Request(base+path,data=json.dumps(body).encode(),headers=headers,method='POST')
    with urllib.request.urlopen(request,timeout=180) as response:return json.load(response)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--api-base',default='https://caibinice.com/smartCockpit/api/parking');parser.add_argument('--accounts-file',type=Path,required=True);args=parser.parse_args()
    base=args.api_base.rstrip('/');auth=post(base,'/login',{'username':'admin','password':read_action_auth()['password']});token=auth['token']
    result=post(base,'/setup',{},token)
    if args.accounts_file.exists():accounts=json.loads(args.accounts_file.read_text(encoding='utf-8'))
    else:
        accounts=[{'username':'parking_security','displayName':'停车安保演示账号','role':'security','password':secrets.token_urlsafe(18)},{'username':'parking_operator','displayName':'停车运营演示账号','role':'operator','password':secrets.token_urlsafe(18)}]
        args.accounts_file.parent.mkdir(parents=True,exist_ok=True);args.accounts_file.write_text(json.dumps(accounts,ensure_ascii=False,indent=2),encoding='utf-8')
    for account in accounts:post(base,'/users',account,token)
    print(json.dumps({'setup':result,'accounts':[{'username':a['username'],'role':a['role']} for a in accounts],'privateAccountsFile':str(args.accounts_file)},ensure_ascii=False))
if __name__=='__main__':main()
