import csv, gzip, json, tempfile, unittest
from datetime import date, datetime, timedelta
from pathlib import Path
from generate_parking_year import generate

class ParkingYearTest(unittest.TestCase):
    def test_reproducible_balanced_ledger(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root);source=root/'source.csv'
            with source.open('w',newline='',encoding='utf-8') as stream:
                writer=csv.writer(stream);writer.writerow(['SystemCodeNumber','Capacity','Occupancy','LastUpdated'])
                for day in [date(2016,10,4),date(2016,10,8)]:
                    for slot in range(32,67):
                        at=datetime.combine(day,datetime.min.time())+timedelta(minutes=slot*15)
                        writer.writerow(['SYNTHETIC_TEST',100,40+slot%20,at.isoformat(' ')])
                writer.writerow(['bad',0,50,'bad'])
            a=generate(source,root/'a',days=2);b=generate(source,root/'b',days=2)
            self.assertEqual(a,b);self.assertEqual(a['rows']['occupancy'],576)
            self.assertEqual(a['rejectedSourceRows'],1)
            for name in ['occupancy','stays','alerts']:
                with gzip.open(root/'a'/(name+'.csv.gz'),'rt',encoding='utf-8') as stream: x=stream.read()
                with gzip.open(root/'b'/(name+'.csv.gz'),'rt',encoding='utf-8') as stream: y=stream.read()
                self.assertEqual(x,y)
            with gzip.open(root/'a'/'occupancy.csv.gz','rt') as stream:
                count={'A':0,'B':0,'C':0}
                for r in csv.DictReader(stream):
                    count[r['zone_id']]+=int(r['arrivals'])-int(r['departures'])
                    self.assertEqual(count[r['zone_id']],int(r['occupied']))
            with gzip.open(root/'a'/'stays.csv.gz','rt',encoding='utf-8') as stream:
                for r in csv.DictReader(stream):
                    self.assertTrue(r['vehicle_alias'].startswith('模拟-'))
                    if r['exited_at']:self.assertGreaterEqual(datetime.fromisoformat(r['exited_at']),datetime.fromisoformat(r['entered_at']))
                    self.assertEqual(r['fee_cents'],r['paid_cents'])

if __name__=='__main__':unittest.main()
