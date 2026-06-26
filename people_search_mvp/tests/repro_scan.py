from app import app


def run_once(client, idx):
    rv = client.post('/scan_webcams', data={'urls':'seattle','timeout':'8'})
    print(f'Run {idx} status', rv.status_code)
    try:
        j = rv.get_json()
    except Exception as e:
        print('json err', e)
        print(rv.data.decode()[:400])
        return
    print('count', j.get('count'))
    print('success len', len(j.get('results',{}).get('success',[])))
    print('failure len', len(j.get('results',{}).get('failure',[])))
    print('fail sample', j.get('results',{}).get('failure')[:2])

with app.test_client() as c:
    for i in range(1,4):
        run_once(c, i)
