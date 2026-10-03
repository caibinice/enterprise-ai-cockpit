"""Add parking tables without restarting the existing app; verified backup first."""
import gzip,re,shlex
from pathlib import Path
from datetime import datetime
from remote_client import RemoteClient
from deploy_cockpit import read_credentials

def main():
    credentials=read_credentials()['mysql.remote'];database=credentials['database']
    if not re.fullmatch(r'[A-Za-z0-9_]+',database):raise ValueError('Invalid database identifier')
    def option(value):return '"'+value.replace('\\','\\\\').replace('"','\\"')+'"'
    config='[client]\nhost=127.0.0.1\nuser='+option(credentials['user'])+'\npassword='+option(credentials['password'])+'\ndefault-character-set=utf8mb4\n'
    remote=RemoteClient();prefix='/tmp/parking-schema';backup='/opt/enterprise-ai-cockpit/backups/parking-operations-'+datetime.now().strftime('%Y%m%d%H%M%S')
    try:
        remote.upload_bytes(config.encode(),prefix+'.cnf',0o600)
        remote.upload_file(Path(__file__).resolve().parents[2]/'backend/src/main/resources/db/migration/mysql/V3__parking_operations.sql',prefix+'.sql',0o600)
        remote.run("set -e; mkdir -p "+backup+"; chmod 700 "+backup+"; cp -p /opt/enterprise-ai-cockpit/shared/app.env "+backup+"/app.env; cp -p /etc/systemd/system/enterprise-ai-cockpit.service "+backup+"/service; cp -a /etc/nginx/conf.d "+backup+"/nginx-conf.d; cp -a /etc/nginx/snippets "+backup+"/nginx-snippets; readlink -f /opt/enterprise-ai-cockpit/current > "+backup+"/cockpit-release.txt; readlink -f /opt/3d-smart-parking/www > "+backup+"/parking-release.txt; readlink -f /opt/ai-blog/current > "+backup+"/blog-release.txt; set -o pipefail; mysqldump --defaults-extra-file="+prefix+".cnf --single-transaction --no-tablespaces "+database+" | gzip > "+backup+"/mysql-before.sql.gz; gzip -t "+backup+"/mysql-before.sql.gz; test -s "+backup+"/mysql-before.sql.gz; find "+backup+" -type f -exec chmod 600 {} \\;; find "+backup+" -type d -exec chmod 700 {} \\;; echo BACKUP="+backup,root=True,timeout=300)
        remote.run('mysql --defaults-extra-file='+prefix+'.cnf '+database+' < '+prefix+'.sql',root=True,timeout=60)
        remote.run('mysql --defaults-extra-file='+prefix+'.cnf '+database+' -N -e '+shlex.quote("SHOW TABLES LIKE 'parking_%'"),root=True,timeout=30)
    finally:
        remote.run('rm -f '+prefix+'.cnf '+prefix+'.sql',root=True,timeout=30);remote.close()

if __name__=='__main__':main()
