# 🚗 Traffic Camera Dashboard

A modern, interactive web application for viewing and filtering Washington State traffic cameras from WSDOT, regional airports, and city traffic systems. Browse **596 live traffic cameras** with intelligent location classification, clickable city/highway filters, responsive filtering, and full-screen image preview modal.

**Latest Updates:**
- ✅ All 596 cameras fully classified (0 Unknown)
- ✅ 28 highway corridors mapped (includes WA-018, WA-096, WA-104, WA-161, Ferry-Service)
- ✅ 18 cities with camera counts
- ✅ City & Highway filter links working independently
- ✅ Real-time statistics and filtering
- ✅ Live camera previews with lazy loading

![Traffic Camera Dashboard - Split Screen View](https://via.placeholder.com/1200x600?text=Traffic+Camera+Dashboard)

## ✨ Features

### 📊 Comprehensive Camera Database
- **596 traffic cameras** from WSDOT highways, regional airports, and city systems
- **100% classified**: All cameras assigned to city, highway, corridor, and region (0 Unknown)
- **28 highway corridors**: I-5, I-90, I-405, US-2, US-99, US-395, WA-018 through WA-531, Ferry-Service
- **18 cities mapped**: Seattle (286), Renton (102), Everett (45), Olympia (44), Longview (27), and more
- **Real-time metadata**: Camera ID, type, location, corridor, county, and traffic region

### 🎯 Interactive Dashboard
- **Split-screen layout**: Responsive filter sidebar (350px on desktop) + live camera gallery
- **Top Cities Quick-Click**: 10 most-monitored cities - click to instantly view all cameras
- **Top Highways Quick-Click**: 10 busiest corridors - click to filter and view route cameras
- **Independent filters**: City and highway links clear other filters to prevent conflicts
- **Multi-criteria filtering**: Search by city name, highway, camera type, or geographic region
- **Live camera feed**: Display up to 24 real-time traffic camera images per view
- **Full-screen modal**: Click any camera to view enlarged with city/camera ID info

### 🔍 Smart Filtering
- **Search box**: Real-time city name search with auto-completion
- **Highway dropdown**: Select from 28 highway corridors (I-5, WA-520, WA-018, etc.)
- **Camera type filter**: Highway Camera, City Traffic Camera, Airport Camera
- **Region filter**: 13 geographic regions (Central King County, North Puget Sound, etc.)
- **Clear selection**: One-click reset to view all 596 cameras
- **Live stats**: Counter updates showing selected cameras count in real-time

### 📱 Responsive Design
- **Desktop-optimized**: Full split-screen layout on wide displays
- **Tablet-friendly**: Responsive grid layout
- **Mobile-compatible**: Single-column layout below 768px breakpoint
- **Lazy loading**: Images load efficiently on demand

### 🎨 User Experience
- **Smooth animations**: Fade-in modals and hover effects
- **Visual feedback**: Selection indicators and stat updates
- **Keyboard support**: ESC to close modals, intuitive navigation
- **Real-time updates**: Stats refresh as you filter

---

## 📝 Recent Updates & Fixes

### Version 1.2.0 - Complete Classification & Filter Independence (Latest)
- ✅ **WA-018 Highway Restored**: 19 cameras in Longview corridor now displaying correctly
- ✅ **Filter Independence**: City and highway links now clear other filters automatically
  - Prevents "0 cameras found" when multiple filters conflict
  - Clicking city link clears highway/type/region filters
  - Clicking highway link clears city/type/region filters
- ✅ **Database Sync**: Ensured camera data synchronized between root and public folders
- ✅ **OCR/Heuristic Fixes**: Classified all remaining Unknown cameras
  - 18 "WSDOT Highway" → Olympia (US-395)
  - 1 "Regional Airport" → Seattle (Airport)

### Version 1.1.0 - Full Location Classification
- ✅ **All 596 Cameras Classified**: Zero Unknown cameras
- ✅ **Highway Expansion**: Added 6 new highway mappings
  - WA-018 (19 cameras - Longview)
  - WA-096 (5 cameras)
  - WA-104 (5 cameras)
  - WA-161 (1 camera)
  - Ferry-Service (4 cameras)
- ✅ **Database Regeneration**: Created enhanced camera array with complete metadata

### Version 1.0.0 - Initial Release
- ✅ Interactive dashboard with 596 cameras
- ✅ Split-screen layout with filters
- ✅ Real-time camera gallery with lazy loading
- ✅ Full-screen image modal with metadata
- ✅ City and highway filtering
- ✅ Responsive design for desktop/tablet/mobile

---

## 📋 Quick Start

### Option 1: Local Deployment (Windows - Recommended)

#### 1️⃣ Prerequisites
- Node.js 14+ ([Download](https://nodejs.org))
- Git (optional, for cloning)
- Windows with admin access (for hosts file modification)

#### 2️⃣ One-Click Setup
```powershell
# Right-click start.ps1 and select "Run with PowerShell"
# OR run in PowerShell:
.\start.ps1
```

This automatically:
- ✅ Adds `127.0.0.1 trafficcams.local` to hosts file
- ✅ Installs npm dependencies
- ✅ Starts the Express server on port 80

#### 3️⃣ Access the Dashboard
```
http://trafficcams.local
```

---

### Option 2: Manual Setup

#### Step 1: Add Local Domain to Hosts File

**Windows (Administrator required):**
1. Open Notepad as Administrator
2. Open: `C:\Windows\System32\drivers\etc\hosts`
3. Add this line at the end:
   ```
   127.0.0.1 trafficcams.local
   ```
4. Save and close

**macOS/Linux:**
```bash
sudo nano /etc/hosts
# Add: 127.0.0.1 trafficcams.local
# Save: Ctrl+X, Y, Enter
```

#### Step 2: Install Dependencies
```bash
npm install
```

#### Step 3: Start the Server
```bash
npm start
```

You should see:
```
✅ Traffic Camera Server is running!
📍 Access at: http://trafficcams.local
🔗 Or direct: http://localhost:80
```

#### Step 4: Open Dashboard
Visit `http://trafficcams.local` in your browser

---

## 🚀 Deploy from GitHub to Webserver

### Prerequisites for GitHub Deployment
- GitHub account (create at [github.com](https://github.com))
- Hosting provider (Azure, AWS, Heroku, DigitalOcean, etc.)
- Domain name (optional)

### Step 1️⃣: Create GitHub Repository

```bash
# Clone this repo or initialize new repo
cd trafficcams

# Initialize git (if not already done)
git init

# Add all files
git add .

# Create initial commit
git commit -m "Initial commit: Traffic Camera Dashboard with 596 cameras"

# Add GitHub remote (replace with your repo URL)
git remote add origin https://github.com/YOUR-USERNAME/trafficcams.git

# Push to GitHub
git branch -M main
git push -u origin main
```

### Step 2️⃣: Deploy to Azure App Service (Recommended)

**Option A: Automatic Deployment via GitHub**

1. Create Azure App Service:
   ```bash
   az group create --name trafficcams-rg --location eastus
   az appservice plan create --name trafficcams-plan --resource-group trafficcams-rg --sku B1 --is-linux
   az webapp create --resource-group trafficcams-rg --plan trafficcams-plan --name trafficcams-app --runtime "node|18-lts"
   ```

2. Configure GitHub Deployment in Azure Portal:
   - Azure Portal → App Service → Deployment Center
   - Select GitHub as source
   - Authorize GitHub
   - Select your repository and branch
   - Click Save → Auto-deploys on every push

3. Access your app:
   ```
   https://trafficcams-app.azurewebsites.net
   ```

**Option B: Manual Push Deployment**

```bash
# Install Azure CLI: https://aka.ms/cli

az account show

# Configure deployment credentials
az webapp deployment user set --user-name <username> --password <password>

# Add Azure remote
git remote add azure https://<username>@trafficcams-app.scm.azurewebsites.net/trafficcams.git

# Deploy via git push
git push azure main
```

### Step 3️⃣: Deploy to Heroku

```bash
# Install Heroku CLI: https://devcenter.heroku.com/articles/heroku-cli

# Create app
heroku create trafficcams-app

# Set buildpack
heroku buildpacks:set heroku/nodejs

# Deploy
git push heroku main

# View app
heroku open

# View logs
heroku logs --tail
```

### Step 4️⃣: Deploy to AWS EC2

```bash
# Create EC2 instance (Ubuntu 20.04 LTS)
# 1. Launch EC2 instance
# 2. Connect via SSH

# Update system
sudo apt update && sudo apt upgrade -y

# Install Node.js
curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash -
sudo apt-get install -y nodejs

# Install Git
sudo apt install git

# Clone repository
git clone https://github.com/YOUR-USERNAME/trafficcams.git
cd trafficcams

# Install dependencies
npm install --production

# Install PM2 (process manager)
sudo npm install -g pm2

# Start app with PM2
pm2 start server.js --name "trafficcams"
pm2 startup
pm2 save

# Install and configure Nginx (reverse proxy)
sudo apt install nginx
sudo nano /etc/nginx/sites-available/default

# Add to Nginx config:
# location / {
#     proxy_pass http://127.0.0.1:80;
# }

sudo systemctl restart nginx
```

### Step 5️⃣: Deploy to Docker Container

```dockerfile
# Dockerfile
FROM node:18-alpine

WORKDIR /app

COPY package*.json ./
RUN npm install --production

COPY . .

EXPOSE 80

CMD ["npm", "start"]
```

```bash
# Build image
docker build -t trafficcams .

# Run container
docker run -p 80:80 -d trafficcams

# Or push to Docker Hub
docker tag trafficcams YOUR-USERNAME/trafficcams
docker push YOUR-USERNAME/trafficcams
```

### Step 6️⃣: Configure Custom Domain

After deploying to your hosting provider:

1. **Update DNS Records** at your domain registrar:
   ```
   Type: CNAME (or A)
   Name: trafficcams (or @)
   Value: your-app-url.azurewebsites.net
   ```

2. **Configure HTTPS** (SSL/TLS):
   - Azure: Automatic free SSL via managed certificate
   - Heroku: Free SSL included
   - AWS: Use AWS Certificate Manager or Let's Encrypt

---

## 📂 Project Structure

```
trafficcams/
├── server.js                      # Express.js HTTP server
├── public/
│   ├── dashboard.htm              # Main interactive dashboard
│   ├── trafficpage_dynamic.htm    # Alternative full-page viewer
│   └── camera-array-enhanced.js   # Camera database (596 cameras)
├── camera-array-enhanced.js       # Camera metadata
├── enhance-locations.js           # Location classification generator
├── location-summary.json          # City/highway statistics
├── package.json                   # Dependencies & scripts
├── .gitignore                     # Git ignore rules
└── README.md                      # This file
```

---

## 🎮 Usage Guide

### Basic Navigation

1. **Select a City**
   - Click any city in the "Top Cities" list
   - Table updates to show all cameras in that city
   - Gallery shows live camera feeds

2. **Select a Highway**
   - Click any highway in the "Top Highways" list
   - View all cameras along that route

3. **Use Dropdowns for Additional Filters**
   - Filter by Camera Type (Highway, City Traffic, Airport)
   - Filter by Region (North Puget Sound, King County, etc.)
   - Combine filters for precise results

4. **Click Camera Images**
   - Click any camera thumbnail to open full-screen modal
   - See large, high-quality traffic image
   - Shows camera location and ID

5. **Clear Filters**
   - Click "✕ Clear Selection" button to reset

### Example Workflows

**Find all Seattle cameras:**
1. Type "Seattle" in search box OR click "Seattle" in city list
2. View 281 cameras in that city
3. Click any image to zoom in

**View I-5 corridor:**
1. Select "I-5" from highway dropdown OR click in list
2. See 162 cameras along Interstate 5
3. Scroll through live feeds

**Find Seattle airport cameras:**
1. Filter by City: Seattle
2. Filter by Type: Airport Camera
3. See Sea-Tac runway and terminal cameras

---

## 🛠️ API Endpoints

### GET /
Returns the interactive dashboard HTML

### GET /api/cameras-enhanced
Returns JSON array of all 596 cameras with metadata

**Response format:**
```json
[
  {
    "id": "1",
    "name": "WSDOT Camera 1",
    "location": "WSDOT 526VC00358",
    "url": "http://images.wsdot.wa.gov/nw/526vc00358.jpg",
    "city": "Marysville",
    "state": "WA",
    "highway": "WA-526",
    "region": "North Puget Sound",
    "type": "Highway Camera",
    "county": "Multiple"
  }
]
```

### GET /health
Health check endpoint

**Response:**
```json
{
  "status": "Server is running",
  "timestamp": "2026-06-25T10:30:00.000Z"
}
```

---

## 📊 Camera Database Statistics

| Metric | Value |
|--------|-------|
| Total Cameras | 596 |
| WSDOT Highways | 552 |
| City Traffic | 34 |
| Airports | 10 |
| **Unique Cities** | 17 |
| **Highways** | 22 |
| **Regions** | 10 |
| **States** | 1 (WA) |

### Top 10 Cities by Camera Count
1. Seattle - 281 cameras
2. Renton - 102 cameras
3. Unknown - 73 cameras
4. Everett - 45 cameras
5. Bothell - 23 cameras
6. Edmonds - 15 cameras
7. Monroe - 11 cameras
8. Kent - 8 cameras
9. Snohomish - 8 cameras
10. Sea-Tac - 7 cameras

### Top 10 Highways by Camera Count
1. I-5 - 162 cameras
2. I-405 - 69 cameras
3. I-90 - 47 cameras
4. WA-520 - 45 cameras
5. WA-99 - 25 cameras
6. WA-167 - 24 cameras
7. WA-522 - 23 cameras
8. WA-525 - 15 cameras
9. WA-527 - 11 cameras
10. US-2 - 11 cameras

---

## 🔧 Configuration

### Environment Variables

```bash
# Port (default: 80)
export PORT=3000

# Host (default: 127.0.0.1, use 0.0.0.0 for external access)
export HOST=0.0.0.0
```

### Modify Server Port

Edit `server.js`:
```javascript
const PORT = process.env.PORT || 8080;  // Change default
const HOST = process.env.HOST || '127.0.0.1';
```

---

## 🐛 Troubleshooting

### Dashboard shows "Loading cameras..."
- **Check server is running**: `npm start` in terminal
- **Verify port 80 is available**: Port may be in use by another app
- **Check browser console**: F12 → Console tab for errors

### Can't access trafficcams.local
- **Windows**: Verify hosts file entry: `ipconfig /flushdns` to refresh DNS
- **macOS/Linux**: Run `sudo dscacheutil -flushcache`
- **Alternative**: Use `http://localhost` directly

### Camera images not loading
- **CORS issue**: Some camera hosts block requests from browsers
- **Network issue**: Check if images.wsdot.wa.gov is accessible
- **Expected behavior**: Some images show 📷 placeholder if unavailable

### Port 80 already in use (Windows)
```powershell
# Find process using port 80
netstat -ano | findstr :80

# Kill process (replace PID)
taskkill /PID <PID> /F

# Restart server
npm start
```

---

## 📝 Development

### Project Dependencies

```json
{
  "express": "^4.18.2",
  "cors": "^2.8.5"
}
```

### Scripts

```bash
npm start          # Start server on port 80
```

### Generate Camera Database

```bash
# Regenerate location classifications
node enhance-locations.js
```

---

## 📄 License

This project is provided as-is for educational and traffic monitoring purposes.

### Camera Data Source
All camera data sourced from:
- WSDOT (Washington State Department of Transportation)
- Regional airport systems
- City traffic management systems

**WSDOT Camera Terms**: [WSDOT Traffic Cameras](https://www.wsdot.wa.gov/)

---

## 🤝 Contributing

### Report Issues
1. Open an issue with:
   - Specific camera ID (if applicable)
   - Error messages
   - Steps to reproduce
   - Browser/OS information

### Submit Improvements
1. Fork the repository
2. Create feature branch: `git checkout -b feature/amazing-feature`
3. Commit changes: `git commit -m "Add amazing feature"`
4. Push to branch: `git push origin feature/amazing-feature`
5. Open Pull Request

---

## 📞 Support

### Server Not Starting?
1. Verify Node.js installed: `node --version`
2. Check port 80 is free
3. View logs for errors: `npm start`

### Images Not Loading?
1. Check internet connection
2. Verify WSDOT server is online
3. Try direct URL: `http://images.wsdot.wa.gov/nw/005vc00001.jpg`

### Dashboard Slow?
1. Reduce filters to smaller dataset
2. Clear browser cache
3. Check internet speed
4. Server uses lazy loading - images load on scroll

---

## 🎯 Roadmap

- [ ] Historical image archive
- [ ] Camera alerts & notifications
- [ ] Mobile app
- [ ] Advanced analytics
- [ ] Multi-state expansion
- [ ] Scheduled snapshots
- [ ] Google Maps integration

---

## 📚 Additional Resources

- [Express.js Documentation](https://expressjs.com/)
- [Node.js Best Practices](https://nodejs.org/en/docs/)
- [WSDOT Traffic](https://www.wsdot.wa.gov/)
- [Azure App Service Deployment](https://docs.microsoft.com/azure/app-service/)
- [GitHub Pages](https://pages.github.com/)
- [Heroku Deployment](https://devcenter.heroku.com/)

---

**Last Updated**: June 25, 2026  
**Version**: 2.0  
**Status**: Production Ready ✅  
**Live Demo**: http://trafficcams.local
