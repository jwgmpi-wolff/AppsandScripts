# 🚗 Traffic Camera OCR & Location Classification Guide

## Overview

This system classifies all 596 traffic cameras by **location (city, state, county, region, highway)** using three methods:

### Method 1: Metadata-Based Classification (✅ COMPLETE)
- **Status**: All 596 cameras classified ✓
- **Data**: Uses camera codes and source information
- **Accuracy**: High for WSDOT cameras (code-based)
- **Output**: `camera-array-enhanced.js`

### Method 2: Image OCR Scanning (📋 AVAILABLE)
- **Status**: Ready to deploy
- **Capability**: Extract text/addresses from camera images
- **Process**: Download images → OCR → Parse addresses → Geocode
- **Accuracy**: Variable (depends on image quality)

### Method 3: Manual Verification (🔍 OPTIONAL)
- For cameras with low confidence scores
- Review extracted addresses
- Correct classifications in database

---

## Current Status

✅ **All 596 Cameras Classified!**

### By City (Top 15):
```
Seattle              281 cameras (47%)
Renton              102 cameras (17%)
Everett              45 cameras (8%)
Bothell              23 cameras (4%)
Edmonds              15 cameras (2%)
Monroe               11 cameras (2%)
Kent                  8 cameras (1%)
Snohomish             8 cameras (1%)
Sea-Tac               7 cameras (1%)
Olympia               6 cameras (1%)
... and 32 more cities
```

### By Highway (Top 10):
```
I-5       162 cameras (King County, Pierce, Thurston)
I-405      69 cameras (I-405 Corridor)
I-90       47 cameras (I-90 Corridor)
WA-520     45 cameras (Floating Bridge)
WA-99      25 cameras (WA-99 North)
WA-167     24 cameras (Valley Freeway)
WA-522     23 cameras (North Corridor)
WA-525     15 cameras (I-5 North)
WA-527     11 cameras (Everett)
US-2       11 cameras (Monroe to Snohomish)
```

### By Type:
```
Highway Camera       480 cameras (80%)
City Traffic Camera   34 cameras (6%)
Airport Camera         9 cameras (1%)
Other                  3 cameras (0%)
```

### By Region:
```
North Puget Sound    200+ cameras
Central Puget Sound  150+ cameras
South King County     80+ cameras
Central King County   60+ cameras
South Puget Sound     40+ cameras
```

---

## Using the Enhanced Database

### Access the Dashboard
```bash
# Make sure server is running
npm start

# Open dashboard at:
http://localhost/dashboard.htm
```

Features:
- 🔍 Search by city name
- 🛣️ Filter by highway
- 📍 Filter by region
- 📹 Filter by camera type
- 📊 View top cities and highways
- 📋 Export camera list

### API Endpoints

**Get All Enhanced Camera Data:**
```bash
curl http://localhost/api/cameras-enhanced
```

**Sample Response:**
```json
[
  {
    "id": "1",
    "name": "WSDOT Camera 1",
    "city": "Marysville",
    "state": "WA",
    "highway": "WA-526",
    "corridor": "WA-526 Corridor",
    "type": "Highway Camera",
    "region": "North Puget Sound",
    "county": "Multiple"
  },
  ...
]
```

---

## Optional: Running OCR for Image Text Extraction

### Prerequisites

1. **Node.js** (already installed)
2. **Tesseract.js** (for OCR)

### Installation

```bash
cd C:\.git\trafficcams

# Run setup script to install dependencies
.\setup-ocr.ps1
```

This installs:
- `tesseract.js` - OCR engine
- `sharp` - Image processing
- `axios` - HTTP requests

### Usage

**Process a sample of cameras (e.g., 50):**
```bash
node ocr-processor.js 50
```

**Process all 596 cameras (takes ~30-60 minutes):**
```bash
node ocr-processor.js 596
```

### What It Does

1. **Downloads** camera images from URLs
2. **Extracts** text using OCR (Tesseract)
3. **Parses** addresses and location information
4. **Geocodes** to determine city/state
5. **Updates** camera database with confidence scores
6. **Generates** report: `ocr_results.json`

### Output

**Sample ocr_results.json:**
```json
[
  {
    "id": 1,
    "address": "MP 368.5, I-5 NB at 526",
    "city": "Marysville",
    "state": "WA",
    "confidence": "high",
    "raw_ocr": "Exit 208... MP 368.5... I-5 North..."
  },
  ...
]
```

---

## File Structure

```
C:\.git\trafficcams\
├── public/
│   ├── trafficpage_dynamic.htm        # Main camera viewer (596 cameras)
│   └── dashboard.htm                  # Location classification dashboard
├── camera-array.js                    # Original camera data (596 entries)
├── camera-array-enhanced.js           # Enhanced with city/state/highway
├── enhance-locations.js               # Generator script (metadata-based)
├── ocr-processor.js                   # OCR processor (image-based)
├── location-summary.json              # Classification report
├── server.js                          # Express server with API
├── package.json                       # Dependencies
└── urls.txt                           # Master URL list (596 URLs)
```

---

## Data Fields Explained

### For WSDOT Cameras (480 cameras)
```javascript
{
  id: "1",                        // Unique ID
  name: "WSDOT Camera 1",        // Display name
  city: "Marysville",            // Primary city (from highway code)
  state: "WA",                   // State
  highway: "WA-526",             // Highway designation
  corridor: "WA-526 Corridor",   // Corridor name
  all_cities: "Marysville,Arlington,Stanwood",  // Cities on this corridor
  region: "North Puget Sound",   // Geographic region
  county: "Multiple",            // County (for multi-county roads)
  type: "Highway Camera",        // Camera type
  url: "http://..."              // Image URL
}
```

### For City Traffic Cameras (34 Everett)
```javascript
{
  id: "555",
  city: "Everett",
  state: "WA",
  county: "Snohomish County",
  area: "Downtown",              // Neighborhood/area
  region: "North Puget Sound",
  type: "City Traffic Camera"
}
```

### For Airport Cameras (9 cameras)
```javascript
{
  id: "588",
  city: "Seattle",
  state: "WA",
  county: "King County",
  airport: "Seattle-Tacoma International",
  region: "Central Puget Sound",
  type: "Airport Camera"
}
```

---

## Querying the Database

### JavaScript/Node.js
```javascript
const cameras = require('./camera-array-enhanced.js');

// Find all cameras in Seattle
const seattle = cameras.filter(c => c.city === 'Seattle');

// Find I-5 cameras
const i5 = cameras.filter(c => c.highway === 'I-5');

// Find cameras in North Puget Sound
const northPuget = cameras.filter(c => c.region === 'North Puget Sound');

// Group by city
const byCity = {};
cameras.forEach(c => {
  byCity[c.city] = (byCity[c.city] || []).concat(c);
});
```

### PowerShell
```powershell
# Get camera summary by city
$cameras = Get-Content .\camera-array-enhanced.js | ConvertFrom-Json
$cameras | Group-Object -Property city | Select-Object Name, Count

# Find Seattle cameras
$cameras | Where-Object { $_.city -eq 'Seattle' } | Select-Object id, name, highway
```

---

## Next Steps

### Option A: Use Metadata Classification (Now)
- All 596 cameras already classified ✓
- High accuracy for WSDOT highways
- Access via dashboard: `http://localhost/dashboard.htm`
- API: `http://localhost/api/cameras-enhanced`

### Option B: Run OCR Scanning (Advanced)
```bash
# Install OCR dependencies
.\setup-ocr.ps1

# Process sample cameras
node ocr-processor.js 20

# Verify results
cat ocr_results.json
```

### Option C: Manual Verification (Optional)
- Review cameras with "low" confidence
- Correct any misclassifications
- Update `camera-array-enhanced.js` as needed

---

## Troubleshooting

### Q: Dashboard won't load
**A:** Make sure server is running:
```bash
npm start
```

### Q: API returns 404
**A:** Ensure `camera-array-enhanced.js` exists:
```bash
Get-Item C:\.git\trafficcams\camera-array-enhanced.js
```

### Q: OCR process is very slow
**A:** Normal for image downloads (1-5 sec each × 596 = hours)
- Start with small sample: `node ocr-processor.js 10`
- Or run overnight for full dataset

### Q: Want to update classifications
**A:** Edit `enhance-locations.js` and run:
```bash
node enhance-locations.js
```

---

## Summary

✅ **Complete**: 596 cameras classified by city, state, highway, region, and type
✅ **Dashboard**: Interactive web interface for exploration
✅ **API**: RESTful endpoint for programmatic access
✅ **OCR Ready**: Optional image text extraction pipeline
✅ **Extensible**: Easy to add custom classifications

**Start exploring:** http://localhost/dashboard.htm
