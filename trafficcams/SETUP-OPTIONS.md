# 🎯 Complete Setup & Startup Guide

This guide explains all ways to set up automatic startup for your Traffic Cams server.

---

## 🚀 **RECOMMENDED: One-Click Complete Setup**

### **Double-click `complete-setup.bat` as Administrator**

This does everything:
✅ Adds `trafficcams.local` to hosts file  
✅ Installs Node.js dependencies  
✅ Registers with Windows Task Scheduler  
✅ Server starts automatically on next boot  

**That's it! Restart your PC and visit `http://trafficcams.local`**

---

## 📋 **Alternative Methods**

### **Method 1: PowerShell (Task Scheduler)**

Best for developers/power users who want full control:

```powershell
# Open PowerShell as Administrator
cd C:\.git\trafficcams
.\register-startup.ps1 -Enable
```

**Manage later:**
```powershell
.\register-startup.ps1 -Status      # Check status
.\register-startup.ps1 -Disable     # Stop automatic startup
.\register-startup.ps1 -Remove      # Delete task completely
```

**Pros:** Full control, automatic restarts on crash, highest privilege  
**Cons:** Requires PowerShell knowledge

---

### **Method 2: Startup Folder (Simplest)**

Double-click this file as Administrator:

```
create-startup-shortcut.ps1
```

Or via PowerShell:
```powershell
cd C:\.git\trafficcams
.\create-startup-shortcut.ps1 -Startup
```

**Manage later:**
```powershell
.\create-startup-shortcut.ps1 -Remove
```

**Pros:** Simple, easy to remove  
**Cons:** Only starts after user login (not before)

---

### **Method 3: Manual (If automated methods fail)**

1. **Open Task Scheduler** (search in Start menu)
2. **Create Basic Task:**
   - Name: `Traffic-Cams-Server`
   - Trigger: `At startup`
   - Action: `Run program: C:\.git\trafficcams\start-service.bat`
   - Advanced: Check "Run with highest privileges"
3. **Click OK**

---

## ✅ **Verify Setup**

After setup, check if it worked:

```powershell
# Open PowerShell as Administrator
cd C:\.git\trafficcams
.\register-startup.ps1 -Status
```

You should see:
```
✅ Task is ENABLED and ready to run on startup
```

---

## 🔍 **What Gets Started**

When your PC boots, automatically:

1. **Node.js/Express server** starts on port 80
2. **Runs silently** (no console window)
3. **Listens on** `http://trafficcams.local`
4. **Loads 29 traffic cameras** with search/filter
5. **Auto-refresh** every 90 seconds

---

## 🌐 **Access Your Server**

After startup completes (usually takes 5-10 seconds), visit:

```
http://trafficcams.local
```

Or direct access:
- `http://localhost`
- `http://127.0.0.1`

---

## 🆘 **Troubleshooting**

### Server not starting?

1. **Check Task Scheduler:**
   - Open Task Scheduler (search in Start)
   - Navigate to: `Microsoft → Windows → Custom`
   - Look for: `Traffic-Cams-Server`
   - Check if it's **Enabled**

2. **Check logs:**
   - Look at: `C:\.git\trafficcams\logs\startup.log`

3. **Port in use?**
   - Edit `server.js` line 6: change `PORT = 80` to `PORT = 8080`
   - Then visit: `http://localhost:8080`

4. **Reset everything:**
   ```powershell
   cd C:\.git\trafficcams
   .\register-startup.ps1 -Remove
   .\register-startup.ps1 -Enable
   ```

---

## 📊 **Files Reference**

| File | Purpose |
|------|---------|
| `complete-setup.bat` | ⭐ All-in-one setup |
| `register-startup.ps1` | Task Scheduler control |
| `create-startup-shortcut.ps1` | Startup folder method |
| `start-service.bat` | Silent batch starter |
| `start-service.vbs` | Alternative silent starter |
| `start.ps1` | Manual interactive starter |
| `server.js` | Express web server |

---

## 🎓 **How It Works**

When you select automatic startup:

1. **Windows Task Scheduler** registers a task
2. **Task triggers at system startup** with highest privileges
3. **Batch file runs silently** (`start-service.bat`)
4. **Node.js starts** without console window
5. **Server is immediately available** at `http://trafficcams.local`

---

## ⚙️ **Advanced Settings**

### Change port number
Edit `server.js`:
```javascript
const PORT = 8080;  // Change from 80
```

### Disable auto-restart on crash
Edit `register-startup.ps1` line ~85, comment out:
```powershell
# -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 5)
```

### View startup logs
```powershell
Get-Content C:\.git\trafficcams\logs\startup.log
```

---

## ✨ **Features Once Running**

- 📷 29 live traffic cameras
- 🔍 Search by location (e.g., "Broadway", "I-5")
- 🏷️ Filter by source (WSDOT, Snohomish, Everett)
- 🔎 Click to zoom images
- 📍 Location labels on each camera
- 🔄 Auto-refresh every 90 seconds
- 📱 Mobile-responsive design

---

**Need help?** Check:
- `README.md` - Full documentation
- `STARTUP-GUIDE.md` - Quick reference
