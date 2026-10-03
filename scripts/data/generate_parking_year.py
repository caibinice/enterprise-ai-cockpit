"""Reproducible, source-shaped synthetic parking ledger; no real vehicles or hospital policies."""
import argparse, csv, gzip, hashlib, io, json, math, random, urllib.request, zipfile
from collections import defaultdict
from datetime import date, datetime, timedelta
from pathlib import Path

URL = 'https://archive.ics.uci.edu/static/public/482/parking%2Bbirmingham.zip'
PAGE = 'https://archive.ics.uci.edu/dataset/482/parking%2Bbirmingham'
DATASET = 'parking-year-v1'
ZONE_CAP = {'A': 120, 'B': 100, 'C': 80}

def load_source(path):
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(URL, timeout=45) as response:
            archive = zipfile.ZipFile(io.BytesIO(response.read()))
            member = next(n for n in archive.namelist() if n.endswith('.csv'))
            path.write_bytes(archive.read(member))
    groups, rejected = defaultdict(list), 0
    with path.open(encoding='utf-8-sig', newline='') as stream:
        for row in csv.DictReader(stream):
            try:
                cap, occupied = int(row['Capacity']), int(row['Occupancy'])
                at = datetime.fromisoformat(row['LastUpdated'])
                if cap <= 0 or not 0 <= occupied <= cap: raise ValueError()
                groups[(at.weekday() >= 5, at.hour * 4 + at.minute // 15)].append(occupied / cap)
            except (ValueError, KeyError): rejected += 1
    if len(groups) < 10: raise ValueError('Source lacks usable hourly observations')
    return {k: sum(v)/len(v) for k,v in groups.items()}, rejected

def target_load(profile, at, zone, rng):
    # Daytime follows public observations; missing nights and annual seasonality are generated.
    slot = at.hour * 4 + at.minute // 15
    weekend = at.weekday() >= 5
    candidates = [(abs(s-slot), value) for (w,s),value in profile.items() if w == weekend]
    daytime = min(candidates)[1]
    hour = at.hour + at.minute / 60
    if hour < 7: daytime = .10 + .09 * (hour/7)
    elif hour > 17: daytime = .12 + .38 * math.exp(-(hour-17)/2.1)
    zone_factor = {'A':1.08,'B':.85,'C':.66}[zone]
    base = {'A':.035,'B':.20,'C':.16}[zone]
    season = .035 * math.sin(2*math.pi*at.timetuple().tm_yday/365)
    return max(0, min(ZONE_CAP[zone]-1, round(ZONE_CAP[zone]*(base+daytime*zone_factor+season+rng.uniform(-.02,.02)))))

def fee(entry, exit):
    minutes = max(0, (exit-entry).total_seconds()/60)
    return 0 if minutes <= 30 else min(2500*max(1,math.ceil(minutes/1440)), math.ceil((minutes-30)/30)*200)

def generate(source, output, start=date(2025,10,3), days=365, seed=20261003):
    profile, rejected = load_source(source)
    rng, rows, stays, alerts = random.Random(seed), [], [], []
    active = {z:[] for z in ZONE_CAP}; next_id=1
    first = datetime.combine(start, datetime.min.time())
    for step in range(days*96):
        at = first+timedelta(minutes=step*15)
        for zone,capacity in ZONE_CAP.items():
            wanted=target_load(profile,at,zone,rng)
            current=active[zone]
            turnover=min(len(current), rng.randint(0, max(1,capacity//60))) if step else 0
            leaving=min(len(current), max(0,len(current)-wanted)+turnover)
            for _ in range(leaving):
                stay=current.pop(0)
                exit=at-timedelta(seconds=rng.randint(0,899)) if step else at
                exit=max(stay['entered_at'],exit)
                stay['exited_at']=exit;stay['fee_cents']=fee(stay['entered_at'],exit)
                stay['paid_cents']=stay['fee_cents'] # settled synthetic fee ledger, not occupancy-derived revenue.
            arriving=max(0,wanted-len(current))
            for _ in range(arriving):
                entry=at-timedelta(seconds=rng.randint(0,899)) if step else at
                stay={'id':next_id,'dataset_id':DATASET,'zone_id':zone,
                      'vehicle_alias':'模拟-%s-%06d'%(zone,next_id),'entered_at':entry,'exited_at':None,
                      'purpose':{'A':'outpatient','B':'inpatient','C':'emergency'}[zone], 'fee_cents':0,'paid_cents':0}
                current.append(stay);stays.append(stay);next_id+=1
            rows.append({'dataset_id':DATASET,'observed_at':at,'zone_id':zone,'capacity':capacity,
                         'occupied':len(current),'arrivals':arriving,'departures':leaving})
        if step%96==40:
            zone='ABC'[(step//96)%3]
            alerts.append({'id':step//96+1,'dataset_id':DATASET,'zone_id':zone,
                'title':{'A':'入口排队达到模拟预警阈值','B':'充电车位长时间占用提醒','C':'急诊通道停留待核验'}[zone],
                'severity':'高' if zone=='C' else '中','occurred_at':at,'status':'open'})
    for zone in ZONE_CAP:
        selected=[r for r in rows if r['zone_id']==zone]
        count=0
        for r in selected:
            count+=r['arrivals']-r['departures']
            assert count==r['occupied'] and 0<=count<=r['capacity']
    output.mkdir(parents=True,exist_ok=True)
    for name,data in [('occupancy',rows),('stays',stays),('alerts',alerts)]:
        with gzip.open(output/(name+'.csv.gz'),'wt',encoding='utf-8',newline='') as stream:
            writer=csv.DictWriter(stream,fieldnames=list(data[0]));writer.writeheader()
            writer.writerows({k:v.isoformat(' ') if isinstance(v,datetime) else '' if v is None else v for k,v in r.items()} for r in data)
    manifest={'id':DATASET,'startDate':start.isoformat(),'endDate':(start+timedelta(days=days-1)).isoformat(),
        'sourceUrl':PAGE,'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'seed':seed,
        'sourceCitation':'Stolfi, D. (2017). Parking Birmingham. UCI. DOI:10.24432/C51K5Z',
        'sourceLicense':'UCI CC BY 4.0; original source description UK OGL',
        'sourceRange':'2016-10-04 to 2016-12-19, daytime only','rejectedSourceRows':rejected,
        'source':'database-synthetic','timezone':'Asia/Shanghai','intervalMinutes':15,
        'rows':{'occupancy':len(rows),'stays':len(stays),'alerts':len(alerts)},
        'transform':'Daytime occupancy profile aggregated across source parks; mapped A/B/C capacities; generated nights, seasonality, arrivals/departures, aliases and charges.',
        'feePolicy':'Synthetic only: first 30 min free, 2 CNY/30 min thereafter, capped 25 CNY per started day; no hospital policy claim.',
        'ledgerInvariant':'occupied[t] = occupied[t-1] + arrivals[t] - departures[t]; fees are per closed synthetic stay'}
    (output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    return manifest

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--source',type=Path,default=Path(__file__).parent/'sources'/'parking-birmingham.csv')
    parser.add_argument('--days',type=int,default=365);args=parser.parse_args()
    print(json.dumps(generate(args.source,args.output,days=args.days),ensure_ascii=False))
