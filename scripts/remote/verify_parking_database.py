"""Independently check imported ledger invariants without printing any credentials."""
import json
import re
import shlex
from remote_client import RemoteClient, read_credentials


def main():
    credentials = read_credentials()['mysql.remote']
    database = credentials['database']
    if not re.fullmatch(r'[A-Za-z0-9_]+', database):
        raise ValueError('Invalid database identifier')

    def option(value):
        return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'

    config = ('[client]\nhost=127.0.0.1\nuser=' + option(credentials['user'])
              + '\npassword=' + option(credentials['password'])
              + '\ndefault-character-set=utf8mb4\n')
    remote = RemoteClient()
    path = '/tmp/parking-verify.cnf'
    try:
        remote.upload_bytes(config.encode(), path, 0o600)
        command = 'mysql --defaults-extra-file=' + path + ' --batch --raw --skip-column-names ' + database

        def values(sql):
            result = remote.run(command + ' -e ' + shlex.quote(sql), root=True, timeout=90)
            return [list(map(int, line.split('\t'))) for line in result.strip().splitlines()]

        counts = values("SELECT (SELECT COUNT(*) FROM parking_occupancy WHERE dataset_id='parking-year-v1'),"
                        "(SELECT COUNT(*) FROM parking_stays WHERE dataset_id='parking-year-v1'),"
                        "(SELECT COUNT(*) FROM parking_alerts WHERE dataset_id='parking-year-v1');")[0]
        assert counts == [105120, 167906, 365], counts
        ledger = values("SELECT SUM(exited_at IS NOT NULL),SUM(exited_at IS NULL),SUM(paid_cents) "
                        "FROM parking_stays WHERE dataset_id='parking-year-v1';")[0]
        assert ledger == [167841, 65, 352094400], ledger
        invalid = values("SELECT COUNT(*) FROM parking_occupancy o LEFT JOIN parking_occupancy p "
                         "ON p.dataset_id=o.dataset_id AND p.zone_id=o.zone_id "
                         "AND p.observed_at=DATE_SUB(o.observed_at,INTERVAL 15 MINUTE) "
                         "WHERE o.dataset_id='parking-year-v1' AND "
                         "(o.occupied<>COALESCE(p.occupied,0)+o.arrivals-o.departures "
                         "OR o.occupied<0 OR o.occupied>o.capacity);")[0][0]
        assert invalid == 0, invalid
        mismatch = values("SELECT COUNT(*) FROM parking_occupancy o WHERE "
                          "o.dataset_id='parking-year-v1' AND o.observed_at="
                          "(SELECT MAX(observed_at) FROM parking_occupancy WHERE dataset_id='parking-year-v1') "
                          "AND o.occupied<>(SELECT COUNT(*) FROM parking_stays s "
                          "WHERE s.dataset_id=o.dataset_id AND s.zone_id=o.zone_id AND s.exited_at IS NULL);")[0][0]
        assert mismatch == 0, mismatch
        caches = values('SELECT COUNT(*) FROM parking_report_cache;')[0][0]
        assert caches == 4, caches
        print(json.dumps({'counts': counts, 'settledStays': ledger[0], 'openStays': ledger[1],
                          'paidCents': ledger[2], 'invalidTransitions': invalid,
                          'latestZoneMismatch': mismatch, 'reportCaches': caches}))
    finally:
        remote.run('rm -f ' + path, root=True, timeout=20)
        remote.close()


if __name__ == '__main__':
    main()
