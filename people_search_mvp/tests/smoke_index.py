from app import app
rv = app.test_client().get('/')
data = rv.data.decode('utf-8')
print('status', rv.status_code)
print('tab-people', 'id="tab-people"' in data)
print('tab-webcam', 'id="tab-webcam"' in data)
print('history-list', 'id="history-list"' in data)
