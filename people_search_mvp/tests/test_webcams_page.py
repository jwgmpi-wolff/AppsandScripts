import sys, os
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
from app import app, get_conn

# insert a test row
conn = get_conn()
cur = conn.cursor()
cur.execute("INSERT INTO webcam_success(scan_id,url,status_code,content_type,note) VALUES(?,?,?,?,?)",(None,'http://example.com/cam.mjpg',200,'image/jpeg','camera'))
conn.commit()
conn.close()

with app.test_client() as c:
    rv = c.get('/webcams_page?q=example')
    print('status', rv.status_code)
    print(rv.data.decode('utf-8')[:1000])
