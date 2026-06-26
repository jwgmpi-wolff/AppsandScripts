import sys, os
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
from app import app
with app.test_client() as c:
    rv = c.post('/search', data={'query':'example seattle','max_results':'8','entity':'public','source':'all'})
    print('status', rv.status_code)
    try:
        j = rv.get_json()
        print('results', len(j.get('results',[])))
        print('errors', j.get('fetch_errors'))
    except Exception as e:
        print('json err', e)
        print(rv.data.decode()[:400])
