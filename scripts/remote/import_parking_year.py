"""Bounded MySQL import over existing SSH; idempotent immutable dataset id; no secrets in SQL."""
import argparse, csv, gzip, json, re, shlex, sys
from pathlib import Path
from remote_client import RemoteClient
from deploy_cockpit import read_credentials

def quoted(value):
    if value is None or value=='':return 'NULL'
    return "'"+str(value).replace('\\','\\\\').replace("'","''")+"'"

def make_sql(folder, output):
    manifest=json.loads((folder/'manifest.json').read_text(encoding='utf-8'))
    with gzip.open(output,'wt',encoding='utf-8') as sql:
        sql.write('SET NAMES utf8mb4;\n')
        for name in ['occupancy','stays','alerts']:
            with gzip.open(folder/(name+'.csv.gz'),'rt',encoding='utf-8',newline='') as stream:
                reader=csv.DictReader(stream);cols=reader.fieldnames;batch=[]
                for row in reader:
                    batch.append('('+','.join(quoted(row[c]) for c in cols)+')')
                    if len(batch)==500:
                        sql.write('INSERT IGNORE INTO parking_'+name+' ('+','.join(cols)+') VALUES '+','.join(batch)+';\n');batch=[]
                if batch:sql.write('INSERT IGNORE INTO parking_'+name+' ('+','.join(cols)+') VALUES '+','.join(batch)+';\n')
        values=[manifest['id'],manifest['startDate'],manifest['endDate'],manifest['sourceUrl'],manifest['sourceSha256'],manifest['seed'],json.dumps(manifest,ensure_ascii=False)]
        # Marker goes last: an interrupted import can be resumed without announcing completeness.
        sql.write('INSERT IGNORE INTO parking_dataset(id,start_date,end_date,source_url,source_sha256,seed,manifest_json) VALUES('+','.join(map(quoted,values))+');\n')
    return manifest

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('folder',type=Path);args=parser.parse_args()
    folder=args.folder.resolve();sql=folder/'parking-import.sql.gz';manifest=make_sql(folder,sql)
    creds=read_credentials()['mysql.remote'];database=creds['database']
    if not re.fullmatch(r'[A-Za-z0-9_]+',database):raise ValueError('Invalid database name')
    def option(v):return '"'+v.replace('\\','\\\\').replace('"','\\"')+'"'
    options='[client]\nhost=127.0.0.1\nuser='+option(creds['user'])+'\npassword='+option(creds['password'])+'\ndefault-character-set=utf8mb4\n'
    remote=RemoteClient();prefix='/tmp/parking-year-import'
    try:
        remote.upload_bytes(options.encode(),prefix+'.cnf',0o600)
        remote.upload_file(sql,prefix+'.sql.gz',0o600)
        command='mysql --defaults-extra-file='+prefix+'.cnf --batch --raw --skip-column-names '+database
        query="SELECT CONCAT(source_sha256,':',seed) FROM parking_dataset WHERE id='parking-year-v1';"
        existing=remote.run(command+' -N -e '+shlex.quote(query),root=True,timeout=30).strip()
        expected=manifest['sourceSha256']+':'+str(manifest['seed'])
        if existing and expected not in existing:raise ValueError('Dataset id belongs to a different immutable source/seed')
        remote.run('gzip -dc '+prefix+'.sql.gz | '+command,root=True,timeout=600)
        for table,count in manifest['rows'].items():
            result=remote.run(command+' -N -e '+shlex.quote("SELECT COUNT(*) FROM parking_"+table+" WHERE dataset_id='parking-year-v1'"),root=True,timeout=45)
            if int(result.strip().splitlines()[-1])!=count:raise ValueError('Row count mismatch: '+table)
        print(json.dumps({'imported':manifest['rows'],'source':'database-synthetic','range':[manifest['startDate'],manifest['endDate']]},ensure_ascii=False))
    finally:
        remote.run('rm -f '+prefix+'.cnf '+prefix+'.sql.gz',root=True,timeout=20)
        remote.close()
