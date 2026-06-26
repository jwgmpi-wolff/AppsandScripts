# 🚀 QUICK START - Automatic Startup

## 1️⃣ **EASIEST METHOD** (One Command - Recommended)

Open PowerShell as Administrator and run:

```powershell
cd C:\.git\trafficcams
.\register-startup.ps1 -Enable
```

✅ Done! Your server will now start automatically on every boot.

---

## 📍 Access Your Server

```
http://trafficcams.local
```

---

## 🔧 Management Commands

**Check if setup:**
```powershell
.\register-startup.ps1 -Status
```

**Disable automatic startup:**
```powershell
.\register-startup.ps1 -Disable
```

**Remove completely:**
```powershell
.\register-startup.ps1 -Remove
```

---

## ⚡ What's Running in the Background

- ✅ Node.js/Express server on port 80
- ✅ 29 live traffic cameras
- ✅ Search & filter functionality  
- ✅ Auto-refresh every 90 seconds
- ✅ Silent (no console window)
- ✅ Auto-restarts on crash

---

## 📝 Notes

- Requires Administrator privileges to set up
- Server runs silently in background
- Logs stored in `logs\startup.log`
- Visit `http://trafficcams.local` anytime after boot
