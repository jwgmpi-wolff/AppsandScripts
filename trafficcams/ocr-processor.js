const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

// Try to require tesseract, install if needed
let Tesseract = null;
try {
    Tesseract = require('tesseract.js');
} catch (e) {
    console.log('⚠️  tesseract.js not installed. Run: npm install tesseract.js');
}

const cameras = require('./camera-array.js');

// Mapping of WSDOT camera codes to known locations (based on highway patterns)
const WSDOT_LOCATIONS = {
    '002': { highway: 'US-2', cities: ['Monroe', 'Everett', 'Snohomish'] },
    '005': { highway: 'I-5', cities: ['Seattle', 'Tacoma', 'Olympia'] },
    '099': { highway: 'WA-99', cities: ['Seattle', 'Shoreline', 'Edmonds'] },
    '167': { highway: 'WA-167', cities: ['Renton', 'Kent', 'Auburn'] },
    '169': { highway: 'WA-169', cities: ['Kent', 'Enumclaw'] },
    '181': { highway: 'WA-181', cities: ['Kent', 'Covington'] },
    '202': { highway: 'WA-202', cities: ['Snohomish', 'Monroe'] },
    '204': { highway: 'WA-204', cities: ['Shoreline', 'Edmonds'] },
    '405': { highway: 'I-405', cities: ['Renton', 'Bellevue', 'Lynnwood'] },
    '509': { highway: 'WA-509', cities: ['Seattle', 'Burien', 'Olympia'] },
    '515': { highway: 'WA-515', cities: ['Tacoma', 'Puyallup'] },
    '516': { highway: 'WA-516', cities: ['Renton', 'Auburn'] },
    '518': { highway: 'WA-518', cities: ['Sea-Tac', 'Burien'] },
    '520': { highway: 'WA-520', cities: ['Seattle', 'Bellevue'] },
    '522': { highway: 'WA-522', cities: ['Bothell', 'Monroe', 'Snohomish'] },
    '525': { highway: 'WA-525', cities: ['Edmonds', 'Marysville'] },
    '526': { highway: 'WA-526', cities: ['Marysville', 'Arlington'] },
    '527': { highway: 'WA-527', cities: ['Everett', 'Marysville'] },
    '529': { highway: 'WA-529', cities: ['Marysville', 'Smokey Point'] },
    '531': { highway: 'WA-531', cities: ['Olympia', 'Lacey', 'Tumwater'] },
    '599': { highway: 'US-99', cities: ['Longview', 'Centr alia', 'Olympia'] },
    '090': { highway: 'I-90', cities: ['Seattle', 'Snoqualmie', 'Spokane'] },
};

const AIRPORT_LOCATIONS = {
    'arlington': { city: 'Arlington', state: 'WA', airport: 'Arlington Municipal' },
    'auburn': { city: 'Auburn', state: 'WA', airport: 'Auburn-Gnaw Bone' },
    'renton': { city: 'Renton', state: 'WA', airport: 'Renton Municipal' },
    'seattle': { city: 'Seattle', state: 'WA', airport: 'Seattle-Tacoma Intl' },
};

/**
 * Download image from URL
 */
async function downloadImage(url, outputPath) {
    return new Promise((resolve, reject) => {
        const protocol = url.startsWith('https') ? https : http;
        protocol.get(url, (res) => {
            if (res.statusCode === 200) {
                const fileStream = fs.createWriteStream(outputPath);
                res.pipe(fileStream);
                fileStream.on('finish', () => {
                    fileStream.close();
                    resolve(outputPath);
                });
            } else {
                reject(new Error(`HTTP ${res.statusCode}`));
            }
        }).on('error', reject);
    });
}

/**
 * Extract text from image using Tesseract OCR
 */
async function extractTextFromImage(imagePath) {
    if (!Tesseract) {
        console.log('❌ Tesseract.js not available. Skipping OCR.');
        return '';
    }

    try {
        const { data: { text } } = await Tesseract.recognize(imagePath, 'eng', {
            logger: m => { /* silent */ }
        });
        return text;
    } catch (error) {
        console.error(`OCR failed for ${imagePath}:`, error.message);
        return '';
    }
}

/**
 * Parse address from OCR text
 */
function parseAddress(text) {
    if (!text) return null;

    // Look for common patterns: Street names, Mile markers, intersections
    const addressPatterns = [
        /(\d+\s+(?:Street|St|Avenue|Ave|Road|Rd|Highway|Hwy|Mile\s+(?:marker|post)).*)/gi,
        /(?:at|near|@)\s+([A-Z][^,\n]+)/g,
        /(?:MP|Milepost)\s+(\d+\.?\d*)/g,
    ];

    let addresses = [];
    addressPatterns.forEach(pattern => {
        let match;
        while ((match = pattern.exec(text)) !== null) {
            addresses.push(match[1].trim());
        }
    });

    return addresses.length > 0 ? addresses[0] : null;
}

/**
 * Geocode address to city/state (simple lookup)
 */
async function geocodeAddress(address, cameraId, source) {
    // For WSDOT cameras, try code-based lookup
    if (source === 'wsdot') {
        const codeMatch = address.match(/(\d{3})/);
        if (codeMatch) {
            const code = codeMatch[1];
            const info = WSDOT_LOCATIONS[code];
            if (info) {
                return {
                    city: info.cities[0],
                    state: 'WA',
                    highway: info.highway,
                    confidence: 'high'
                };
            }
        }
    }

    // For airport cameras
    if (source === 'airport') {
        for (const [key, info] of Object.entries(AIRPORT_LOCATIONS)) {
            if (address.toLowerCase().includes(key)) {
                return { ...info, confidence: 'high' };
            }
        }
    }

    // For Everett cameras
    if (source === 'everett') {
        return { city: 'Everett', state: 'WA', confidence: 'high' };
    }

    return { city: 'Unknown', state: 'WA', confidence: 'low' };
}

/**
 * Process a single camera
 */
async function processCamera(camera, batchDir) {
    try {
        const imagePath = path.join(batchDir, `cam_${camera.id}.jpg`);
        
        // Download image
        console.log(`[${camera.id}] Downloading...`);
        await downloadImage(camera.url, imagePath).catch(e => {
            console.log(`[${camera.id}] Download failed: ${e.message}`);
            throw e;
        });

        // Extract text
        console.log(`[${camera.id}] Running OCR...`);
        const text = await extractTextFromImage(imagePath);
        const address = parseAddress(text);

        // Geocode
        console.log(`[${camera.id}] Geocoding...`);
        const location = await geocodeAddress(address || camera.location, camera.id, camera.source);

        // Clean up
        fs.unlinkSync(imagePath);

        return {
            id: camera.id,
            address: address || camera.location,
            city: location.city,
            state: location.state,
            highway: location.highway || null,
            confidence: location.confidence,
            raw_ocr: text.substring(0, 200)
        };
    } catch (error) {
        console.error(`[${camera.id}] Error:`, error.message);
        return {
            id: camera.id,
            address: camera.location,
            city: 'Unknown',
            state: 'WA',
            error: error.message
        };
    }
}

/**
 * Main processing function
 */
async function processAllCameras(sampleSize = 10) {
    console.log(`\n🚀 Starting OCR & Geocoding for ${sampleSize} cameras...`);
    
    const batchDir = path.join(__dirname, 'ocr_temp');
    if (!fs.existsSync(batchDir)) {
        fs.mkdirSync(batchDir, { recursive: true });
    }

    const results = [];
    const camerasToProcess = cameras.slice(0, sampleSize);

    for (const camera of camerasToProcess) {
        const result = await processCamera(camera, batchDir);
        results.push(result);
        console.log(`✓ [${result.id}] ${result.city}, ${result.state}`);
    }

    // Save results
    const outputPath = path.join(__dirname, 'ocr_results.json');
    fs.writeFileSync(outputPath, JSON.stringify(results, null, 2));
    console.log(`\n✓ Results saved to: ${outputPath}`);

    // Summary
    console.log('\n📊 SUMMARY:');
    console.log(`Total processed: ${results.length}`);
    console.log(`Successful: ${results.filter(r => !r.error).length}`);
    console.log(`Failed: ${results.filter(r => r.error).length}`);
    
    const cityCounts = {};
    results.forEach(r => {
        cityCounts[r.city] = (cityCounts[r.city] || 0) + 1;
    });
    console.log('\n📍 Cameras by City:');
    Object.entries(cityCounts).sort((a, b) => b[1] - a[1]).forEach(([city, count]) => {
        console.log(`  ${city}: ${count}`);
    });

    // Cleanup
    fs.rmSync(batchDir, { recursive: true });
}

// Run
if (require.main === module) {
    const sampleSize = process.argv[2] ? parseInt(process.argv[2]) : 10;
    processAllCameras(sampleSize).catch(console.error);
}

module.exports = { processAllCameras, geocodeAddress, parseAddress };
