import json, urllib.request
def get(u):
    r=urllib.request.Request(u,headers={'User-Agent':'Mozilla/5.0'})
    return urllib.request.urlopen(r,timeout=60).read()
for ds in ('7320','13940'):
    try:
        j=json.loads(get(f'https://data.gov.tw/api/v2/rest/dataset/{ds}')); r=j.get('result',j)
        print('==',ds,r.get('title'),r.get('modifiedDate'))
        for d in r.get('distribution',[]):
            print('  ',{k:d.get(k) for k in d})
            u=d.get('resourceDownloadUrl') or d.get('resourceAccessUrl')
            if not u: continue
            try:
                data=get(u); print('   bytes',len(data)); t=None
                for enc in ('utf-8-sig','big5','cp950'):
                    try: t=data.decode(enc); break
                    except Exception: pass
                print('   head:',(t or '')[:700].replace('\n',' | '))
            except Exception as e: print('   fetch fail',e)
    except Exception as e: print('ds fail',ds,e)
