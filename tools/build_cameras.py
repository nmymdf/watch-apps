"""每月抓官方測速照相資料，整理成手錶用的 cameras.csv
欄位：lat,lon,limit,dir,name   dir = 拍攝方向的行車方位角（0=往北，90=往東…；-1=不分方向）"""
import csv, io, json, re, sys, urllib.request, urllib.parse, zipfile, collections
UA={'User-Agent':'Mozilla/5.0'}
def get(u):
    p=urllib.parse.urlsplit(u); u=urllib.parse.urlunsplit(p._replace(path=urllib.parse.quote(p.path)))
    return urllib.request.urlopen(urllib.request.Request(u,headers=UA),timeout=120).read()
def dist_url(ds):
    j=json.loads(get(f'https://data.gov.tw/api/v2/rest/dataset/{ds}'))
    for d in j['result']['distribution']:
        if d.get('resourceDownloadUrl'): return d['resourceDownloadUrl']
def text_of(b):
    if b[:2]==b'PK':
        z=zipfile.ZipFile(io.BytesIO(b)); n=[x for x in z.namelist() if x.lower().endswith('.csv')][0]; b=z.read(n)
    for enc in ('utf-8-sig','cp950','big5'):
        try: return b.decode(enc)
        except Exception: pass
    return b.decode('utf-8','replace')
DIRS={'北':0,'東北':45,'東':90,'東南':135,'南':180,'西南':225,'西':270,'西北':315}
def bearing(s):
    s=(s or '').replace(' ','').replace('　','')
    if not s or '雙向' in s or '雙' in s: return -1
    m=re.search(r'(東北|東南|西北|西南|東|西|南|北)[向往→至](東北|東南|西北|西南|東|西|南|北)',s)
    if m: return DIRS[m.group(2)]
    m=re.search(r'往(東北|東南|西北|西南|東|西|南|北)',s)
    if m: return DIRS[m.group(1)]
    m=re.search(r'(東北|東南|西北|西南|東|西|南|北)(向|下|上|行|側)',s)
    if m: return DIRS[m.group(1)]
    if '北上' in s: return 0
    if '南下' in s: return 180
    if '東行' in s: return 90
    if '西行' in s: return 270
    return -1
def num(s):
    m=re.search(r'\d+(\.\d+)?',s or ''); return float(m.group()) if m else None
def col(h,*keys):
    for i,x in enumerate(h):
        if any(k.lower() in x.lower() for k in keys): return i
    return None
out=[]; unparsed=collections.Counter(); stats={}
for ds in ('7320','13940'):
    try:
        u=dist_url(ds); rows=list(csv.reader(io.StringIO(text_of(get(u)))))
    except Exception as e:
        print('FAIL',ds,e); continue
    h=rows[0]
    ila=col(h,'Latitude','緯度'); ilo=col(h,'Longitude','經度'); ili=col(h,'limit','速限')
    idi=col(h,'direct','拍攝方向','方向'); ina=col(h,'Address','設置地點')
    n=0
    for r in rows[1:]:
        try: la,lo=float(r[ila]),float(r[ilo])
        except Exception: continue
        if la>lo: la,lo=lo,la
        if not (21<la<26.5 and 118<lo<122.5): continue
        lim=num(r[ili]) if ili is not None else None
        d=bearing(r[idi]) if idi is not None else -1
        if d==-1 and idi is not None and r[idi].strip() and '雙' not in r[idi]: unparsed[r[idi].strip()]+=1
        name=(r[ina] if ina is not None else '').replace(',',' ')[:30]
        out.append((round(la,6),round(lo,6),int(lim) if lim else 0,d,name)); n+=1
    stats[ds]=n
# 去除重複（同位置同方向）
seen=set(); final=[]
for row in out:
    k=(round(row[0],4),round(row[1],4),row[3])
    if k in seen: continue
    seen.add(k); final.append(row)
print('stats',stats,'final',len(final))
print('no-limit',sum(1 for r in final if r[2]==0),'no-dir',sum(1 for r in final if r[3]==-1))
print('top unparsed dirs',unparsed.most_common(25))
print('limits',collections.Counter(r[2] for r in final).most_common(12))
if len(final)<500: print('too few rows, keep old file'); sys.exit(1)
with open(sys.argv[1],'w',encoding='utf-8',newline='') as f:
    w=csv.writer(f); w.writerow(['lat','lon','limit','dir','name']); w.writerows(final)
