$urls = Get-Content C:\.git\trafficcams\urls.txt

$htmlTemplate = @'
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="X-UA-Compatible" content="IE=edge">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="description" content="Puget Sound Traffic Cameras with search and filter">
    <meta http-equiv="REFRESH" content="90">
    <title>Puget Sound Traffic Cameras - 596 Cameras</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            padding: 20px;
        }
        .container { max-width: 1400px; margin: 0 auto; }
        .header {
            background: white; border-radius: 12px; padding: 30px;
            margin-bottom: 30px; box-shadow: 0 4px 6px rgba(0,0,0,0.1);
            text-align: center;
        }
        .header h1 { color: #667eea; margin-bottom: 10px; font-size: 2.5em; }
        .header p { color: #666; font-size: 1.1em; margin-bottom: 5px; }
        .subtitle { color: #999; font-size: 0.95em; margin-top: 10px; }
        .controls {
            background: white; border-radius: 12px; padding: 25px;
            margin-bottom: 30px; box-shadow: 0 4px 6px rgba(0,0,0,0.1);
        }
        .search-section { margin-bottom: 25px; }
        .search-section label { display: block; font-weight: 600; color: #667eea; margin-bottom: 10px; }
        .search-box {
            width: 100%; padding: 12px 16px; border: 2px solid #e0e0e0;
            border-radius: 8px; font-size: 1em;
            transition: border-color 0.3s;
        }
        .search-box:focus { outline: none; border-color: #667eea; box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1); }
        .filter-section { margin-top: 20px; }
        .filter-section label { display: block; font-weight: 600; color: #667eea; margin-bottom: 12px; }
        .filter-buttons { display: flex; flex-wrap: wrap; gap: 10px; }
        .filter-btn {
            padding: 10px 20px; border: 2px solid #e0e0e0; background: white;
            border-radius: 8px; cursor: pointer; font-weight: 600;
            transition: all 0.3s;
        }
        .filter-btn:hover { border-color: #667eea; color: #667eea; }
        .filter-btn.active { background: #667eea; color: white; border-color: #667eea; }
        .stats {
            margin-top: 20px; padding: 15px; background: #f5f7fa;
            border-radius: 8px; color: #666; font-weight: 500;
        }
        .gallery {
            display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
            gap: 20px; margin-bottom: 30px;
        }
        .camera-card {
            background: white; border-radius: 12px; overflow: hidden;
            box-shadow: 0 4px 6px rgba(0,0,0,0.1); cursor: pointer;
            display: flex; flex-direction: column;
            transition: all 0.3s;
        }
        .camera-card:hover { transform: translateY(-8px); box-shadow: 0 12px 16px rgba(0,0,0,0.15); }
        .image-container {
            position: relative; width: 100%; overflow: hidden;
            background: #f0f0f0; aspect-ratio: 1;
        }
        .camera-card img {
            width: 100%; height: 100%; object-fit: cover;
            transition: transform 0.3s; cursor: zoom-in;
        }
        .camera-card:hover img { transform: scale(1.05); }
        .camera-info { padding: 16px; flex-grow: 1; display: flex; flex-direction: column; }
        .camera-location { font-weight: 700; color: #667eea; font-size: 1.1em; margin-bottom: 8px; }
        .source-badge {
            display: inline-block; padding: 3px 8px; background: #f0f0f0;
            border-radius: 4px; font-size: 0.8em; font-weight: 600; color: #666; width: fit-content;
        }
        .source-badge.wsdot { background: #e8f4f8; color: #0066cc; }
        .source-badge.airport { background: #f3e5f5; color: #7b1fa2; }
        .source-badge.everett { background: #e8f5e9; color: #2e7d32; }
        .modal { display: none; position: fixed; z-index: 1000; left: 0; top: 0;
            width: 100%; height: 100%; background-color: rgba(0,0,0,0.9);
        }
        .modal.show { display: flex; align-items: center; justify-content: center; }
        .modal-content {
            position: relative; background: black; max-width: 90vw;
            max-height: 90vh; border-radius: 8px; overflow: hidden;
        }
        .modal-content img { width: 100%; height: 100%; object-fit: contain; }
        .modal-info { background: rgba(0,0,0,0.95); color: white; padding: 16px; }
        .close-modal {
            position: absolute; top: 20px; right: 30px; color: white;
            font-size: 40px; cursor: pointer; background: rgba(0,0,0,0.5);
            width: 50px; height: 50px; border-radius: 50%;
            display: flex; align-items: center; justify-content: center;
        }
        .no-results {
            grid-column: 1 / -1; text-align: center; padding: 60px 20px;
            background: white; border-radius: 12px; color: #999;
        }
        @media (max-width: 768px) {
            .header h1 { font-size: 1.8em; }
            .gallery { grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); }
            .filter-buttons { flex-direction: column; }
            .filter-btn { width: 100%; }
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>🚗 Puget Sound Traffic Cameras</h1>
            <p>596 live traffic camera feeds from WSDOT highways, airports, City of Everett, and Snohomish County</p>
            <div class="subtitle">⏱️ Auto-refresh every 90 sec | 🔍 Click image to zoom</div>
            <div class="timestamp">Updated: <span id="lastUpdate"></span></div>
        </div>

        <div class="controls">
            <div class="search-section">
                <label for="searchInput">🔍 Search by location</label>
                <input type="text" id="searchInput" class="search-box" 
                    placeholder="Search cameras..." autocomplete="off">
            </div>
            <div class="filter-section">
                <label>🏷️ Filter by source</label>
                <div class="filter-buttons">
                    <button class="filter-btn active" data-source="all">All (596)</button>
                    <button class="filter-btn" data-source="wsdot">WSDOT (552)</button>
                    <button class="filter-btn" data-source="airport">Airports (10)</button>
                    <button class="filter-btn" data-source="everett">Everett (34)</button>
                </div>
            </div>
            <div class="stats">Showing <span id="visibleCount">0</span> of <span id="totalCount">0</span> cameras</div>
        </div>

        <div class="gallery" id="gallery"></div>
    </div>

    <div id="modal" class="modal">
        <div class="modal-content">
            <span class="close-modal">&times;</span>
            <img id="modalImage" src="" alt="Camera">
            <div class="modal-info">
                <div style="font-weight: 700; color: #667eea;" id="modalLocation"></div>
                <div id="modalSource" style="font-size: 0.9em; margin-top: 8px;"></div>
            </div>
        </div>
    </div>

    <script>
        const cameras = [
'@

# Build camera array
$id = 1
foreach ($url in $urls) {
    # Categorize
    if ($url -match 'wsdot') { $source = 'wsdot'; $location = 'WSDOT' }
    elseif ($url -match 'airport') { $source = 'airport'; $location = 'Airport' }
    elseif ($url -match 'everett') { $source = 'everett'; $location = 'Everett' }
    else { $source = 'other'; $location = 'Traffic Camera' }
    
    # Escape quotes in URL
    $urlEscaped = $url.Replace('"', '\"')
    
    $htmlTemplate += "{id:$id,name:'Cam $id',location:'$location',source:'$source',url:'$urlEscaped'},"
    $id++
}

# Close array and add JavaScript
$htmlTemplate += @'
        ];

        let currentFilter = 'all';
        let searchTerm = '';

        function init() {
            document.getElementById('lastUpdate').textContent = new Date().toLocaleString();
            document.getElementById('totalCount').textContent = cameras.length;
            renderGallery();
            setupEventListeners();
        }

        function setupEventListeners() {
            document.getElementById('searchInput').addEventListener('input', (e) => {
                searchTerm = e.target.value.toLowerCase();
                renderGallery();
            });
            document.querySelectorAll('.filter-btn').forEach(btn => {
                btn.addEventListener('click', (e) => {
                    document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
                    e.target.classList.add('active');
                    currentFilter = e.target.dataset.source;
                    renderGallery();
                });
            });
            const modal = document.getElementById('modal');
            document.querySelector('.close-modal').addEventListener('click', () => modal.classList.remove('show'));
            modal.addEventListener('click', (e) => { if (e.target === modal) modal.classList.remove('show'); });
            document.addEventListener('keydown', (e) => { if (e.key === 'Escape') modal.classList.remove('show'); });
        }

        function filterCameras() {
            return cameras.filter(cam => {
                const matchFilter = currentFilter === 'all' || cam.source === currentFilter;
                const matchSearch = !searchTerm || cam.location.toLowerCase().includes(searchTerm);
                return matchFilter && matchSearch;
            });
        }

        function renderGallery() {
            const gallery = document.getElementById('gallery');
            const filtered = filterCameras();
            gallery.innerHTML = '';

            if (!filtered.length) {
                gallery.innerHTML = '<div class="no-results"><h2>No cameras found</h2></div>';
                document.getElementById('visibleCount').textContent = '0';
                return;
            }

            filtered.forEach(cam => {
                const card = document.createElement('div');
                card.className = 'camera-card';
                const badge = cam.source === 'wsdot' ? 'WSDOT' : cam.source === 'airport' ? 'Airport' : 'Everett';
                const badgeClass = `source-badge ${cam.source}`;
                
                card.innerHTML = `
                    <div class="image-container">
                        <img src="${cam.url}" alt="${cam.location}" 
                            onerror="this.src='data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%22300%22 height=%22300%22%3E%3Crect fill=%22%23f0f0f0%22 width=%22300%22 height=%22300%22/%3E%3Ctext x=%2250%25%22 y=%2250%25%22 text-anchor=%22middle%22 dy=%22.3em%22 fill=%22%23999%22 font-family=%22sans-serif%22 font-size=%2216%22%3EOffline%3C/text%3E%3C/svg%3E'" />
                    </div>
                    <div class="camera-info">
                        <div class="camera-location">📍 ${cam.location}</div>
                        <span class="${badgeClass}">${badge}</span>
                    </div>
                `;
                
                card.onclick = () => {
                    document.getElementById('modalImage').src = cam.url;
                    document.getElementById('modalLocation').textContent = cam.location;
                    document.getElementById('modalSource').textContent = `Camera ${cam.id} • ${badge}`;
                    document.getElementById('modal').classList.add('show');
                };
                
                gallery.appendChild(card);
            });

            document.getElementById('visibleCount').textContent = filtered.length;
        }

        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', init);
        } else {
            init();
        }
    </script>
</body>
</html>
'@

$htmlTemplate | Out-File C:\.git\trafficcams\public\trafficpage_dynamic.htm -Encoding UTF8 -Force
Write-Host "✓ Created HTML with $($urls.Count) cameras"
