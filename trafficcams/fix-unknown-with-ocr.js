const fs = require('fs');
const path = require('path');
const https = require('https');

// Load Tesseract if available
let Tesseract = null;
try {
    Tesseract = require('tesseract.js');
    console.log('✅ Tesseract.js loaded for OCR\n');
} catch (e) {
    console.log('📝 Tesseract.js not installed. Using advanced heuristics...\n');
}

const cameraData = require('./camera-array.js');

// Enhanced location hints with multiple patterns
const ENHANCED_LOCATION_HINTS = {
    // WSDOT Highway patterns
    'WSDOT Highway': {
        likelyHighways: ['US-395', 'I-5', 'US-2', 'I-90'],
        primaryCity: 'Olympia',
        region: 'South Puget Sound',
        type: 'Highway Camera'
    },
    'Highway': {
        likelyHighways: ['I-5', 'I-90', 'I-405', 'WA-520'],
        primaryCity: 'Seattle',
        region: 'Central Puget Sound',
        type: 'Highway Camera'
    },
    
    // Regional Airport patterns
    'Regional Airport': {
        cities: ['Seattle', 'Tacoma', 'Spokane'],
        region: 'Central Puget Sound',
        type: 'Airport Camera'
    },
    'Sea-Tac': {
        city: 'Seattle',
        region: 'Central Puget Sound',
        type: 'Airport Camera'
    },
    'Tacoma International': {
        city: 'Tacoma',
        region: 'South Puget Sound',
        type: 'Airport Camera'
    },
    
    // City traffic patterns
    'Seattle Traffic': {
        city: 'Seattle',
        region: 'Central King County',
        type: 'City Traffic Camera'
    },
    'Renton': {
        city: 'Renton',
        region: 'Central King County',
        type: 'City Traffic Camera'
    },
    'Bellevue': {
        city: 'Bellevue',
        region: 'Northeast King County',
        type: 'City Traffic Camera'
    }
};

// Comprehensive heuristics for location code patterns
const LOCATION_CODE_PATTERNS = {
    // From location code prefix
    '002': { highway: 'US-2', cities: ['Monroe', 'Everett'], region: 'North Puget Sound' },
    '005': { highway: 'I-5', cities: ['Seattle', 'Renton', 'Olympia'], region: 'Central Puget Sound' },
    '009': { highway: 'US-395', cities: ['Olympia', 'Longview'], region: 'South Puget Sound' },
    '018': { highway: 'WA-018', cities: ['Longview', 'Kalama', 'Winlock'], region: 'Southwest Washington' },
    '090': { highway: 'I-90', cities: ['Seattle', 'Bellevue', 'Snoqualmie'], region: 'Central King County' },
    '099': { highway: 'WA-99', cities: ['Seattle', 'Renton', 'Edmonds'], region: 'Central Puget Sound' },
    '167': { highway: 'WA-167', cities: ['Renton', 'Kent'], region: 'South King County' },
    '202': { highway: 'WA-202', cities: ['Redmond', 'Duvall'], region: 'Northeast King County' },
    '405': { highway: 'I-405', cities: ['Seattle', 'Renton', 'Bothell'], region: 'Central Puget Sound' },
    '520': { highway: 'WA-520', cities: ['Seattle', 'Bellevue'], region: 'Central King County' },
    '522': { highway: 'WA-522', cities: ['Bothell', 'Edmonds'], region: 'North Puget Sound' },
    '526': { highway: 'WA-526', cities: ['Marysville', 'Arlington'], region: 'North Puget Sound' }
};

/**
 * Parse location code to extract city and highway
 */
function parseLocationCode(location) {
    if (!location) return null;
    
    // Extract code from patterns like "WSDOT 002VC00068"
    const match = location.match(/(\d{3})/);
    if (match) {
        const code = match[1];
        return LOCATION_CODE_PATTERNS[code] || null;
    }
    return null;
}

/**
 * Extract location from camera image using OCR
 */
async function extractLocationFromImage(url, cameraId) {
    if (!Tesseract) {
        return null;
    }

    try {
        console.log(`🔍 OCR Scanning camera ${cameraId}...`);
        
        // Download image
        const imageBuffer = await downloadImage(url);
        if (!imageBuffer) return null;

        // Use Tesseract to extract text
        const result = await Tesseract.recognize(imageBuffer, 'eng', {
            logger: m => {
                if (m.status === 'recognizing text' && Math.random() < 0.2) {
                    process.stdout.write(`   OCR Progress: ${Math.round(m.progress * 100)}%\r`);
                }
            }
        });

        const text = result.data.text.toUpperCase();
        if (text && text.length > 10) {
            const location = parseLocationFromText(text);
            if (location) return location;
        }
        return null;

    } catch (error) {
        console.log(`   ⚠️  OCR error: ${error.message}`);
        return null;
    }
}

/**
 * Download image from URL
 */
function downloadImage(url) {
    return new Promise((resolve) => {
        const chunks = [];
        const request = https.get(url, { timeout: 5000 }, (res) => {
            if (res.statusCode !== 200) {
                resolve(null);
                return;
            }
            res.on('data', chunk => chunks.push(chunk));
            res.on('end', () => resolve(Buffer.concat(chunks)));
        }).on('error', () => resolve(null));

        request.setTimeout(5000, () => {
            request.destroy();
            resolve(null);
        });
    });
}

/**
 * Parse location keywords from OCR text
 */
function parseLocationFromText(text) {
    const locationMap = {
        'SEATTLE': { city: 'Seattle', highway: 'I-5', region: 'Central Puget Sound' },
        'TACOMA': { city: 'Tacoma', highway: 'I-5', region: 'South Puget Sound' },
        'OLYMPIA': { city: 'Olympia', highway: 'I-5', region: 'South Puget Sound' },
        'BELLEVUE': { city: 'Bellevue', highway: 'I-90', region: 'King County' },
        'RENTON': { city: 'Renton', highway: 'I-405', region: 'South King County' },
        'KENT': { city: 'Kent', highway: 'WA-167', region: 'South King County' },
        'AUBURN': { city: 'Auburn', highway: 'WA-167', region: 'South King County' },
        'EVERETT': { city: 'Everett', highway: 'I-5', region: 'North Puget Sound' },
        'LYNNWOOD': { city: 'Lynnwood', highway: 'I-405', region: 'North Puget Sound' },
        'BOTHELL': { city: 'Bothell', highway: 'WA-522', region: 'North Puget Sound' },
        'MARYSVILLE': { city: 'Marysville', highway: 'US-2', region: 'North Puget Sound' },
        'MONROE': { city: 'Monroe', highway: 'US-2', region: 'North Puget Sound' },
        'SNOQUALMIE': { city: 'Snoqualmie', highway: 'I-90', region: 'Central WA' },
        'LONGVIEW': { city: 'Longview', highway: 'US-395', region: 'Southwest WA' },
        'EDMONDS': { city: 'Edmonds', highway: 'WA-99', region: 'North Puget Sound' },
        'SHORELINE': { city: 'Shoreline', highway: 'WA-99', region: 'North Puget Sound' },
        'SEA-TAC': { city: 'Sea-Tac', highway: 'WA-509', region: 'Southwest King County' },
        'AIRPORT': { city: 'Seattle', type: 'Airport Camera', region: 'Central Puget Sound' },
    };

    for (const [keyword, location] of Object.entries(locationMap)) {
        if (text.includes(keyword)) {
            return location;
        }
    }

    return null;
}

/**
 * Apply enhanced heuristic fixes with intelligent location matching
 */
function applyHeuristicFixes(cameras) {
    console.log('🧠 APPLYING ENHANCED HEURISTICS\n');
    
    const fixed = [];
    let fixedCount = 0;
    
    cameras.forEach((cam) => {
        const updated = { ...cam };
        const location = (updated.location || '').toUpperCase();

        // If city is Unknown, try to classify
        if (updated.city === 'Unknown') {
            let classified = false;

            // Try 1: Match location code pattern
            const codePattern = parseLocationCode(location);
            if (codePattern) {
                updated.highway = codePattern.highway;
                updated.city = codePattern.cities[0]; // Use first city as primary
                updated.region = codePattern.region;
                updated.type = 'Highway Camera';
                console.log(`   ✅ Camera ${updated.id}: ${location} -> ${updated.city} (${updated.highway}) via code`);
                classified = true;
                fixedCount++;
            }

            // Try 2: Match location hints
            if (!classified) {
                for (const [hint, data] of Object.entries(ENHANCED_LOCATION_HINTS)) {
                    if (location.includes(hint)) {
                        if (data.city) {
                            updated.city = data.city;
                        } else if (data.cities && data.cities.length > 0) {
                            updated.city = data.cities[0];
                        } else if (data.primaryCity) {
                            updated.city = data.primaryCity;
                        }
                        if (data.highway) updated.highway = data.highway;
                        if (data.region) updated.region = data.region;
                        if (data.type) updated.type = data.type;
                        
                        console.log(`   ✅ Camera ${updated.id}: ${location} -> ${updated.city} via hint "${hint}"`);
                        classified = true;
                        fixedCount++;
                        break;
                    }
                }
            }

            // Try 3: Fallback generic rules
            if (!classified) {
                if (location.includes('HIGHWAY')) {
                    updated.city = 'Seattle';
                    updated.highway = 'I-5';
                    updated.type = 'Highway Camera';
                    updated.region = 'Central Puget Sound';
                    console.log(`   ⚠️  Camera ${updated.id}: Classified as Seattle/I-5 (generic highway)`);
                    classified = true;
                    fixedCount++;
                } else if (location.includes('AIRPORT')) {
                    updated.city = 'Seattle';
                    updated.type = 'Airport Camera';
                    updated.region = 'Central Puget Sound';
                    console.log(`   ⚠️  Camera ${updated.id}: Classified as Seattle Airport`);
                    classified = true;
                    fixedCount++;
                } else if (location.includes('TRAFFIC')) {
                    updated.type = 'City Traffic Camera';
                    updated.region = 'Central Puget Sound';
                    console.log(`   ⚠️  Camera ${updated.id}: Classified as City Traffic`);
                    classified = true;
                    fixedCount++;
                }
            }

            // If still Unknown, log for review
            if (!classified) {
                console.log(`   ❌ Camera ${updated.id}: Still Unknown - "${location}"`);
            }
        } else {
            // Verify existing classification using location code
            const codePattern = parseLocationCode(location);
            if (codePattern && !updated.highway) {
                updated.highway = codePattern.highway;
                updated.region = codePattern.region;
                fixedCount++;
            }
        }

        fixed.push(updated);
    });

    console.log(`\n📊 Enhanced: ${fixedCount} cameras with heuristic improvements\n`);
    return fixed;
}

/**
 * Main: Fix Unknown cameras
 */
async function main() {
    console.log('🔧 UNKNOWN CAMERA LOCATION FIX\n');
    console.log('━'.repeat(50) + '\n');

    // Load current enhanced database
    const enhancedPath = './camera-array-enhanced.js';
    const enhancedContent = fs.readFileSync(enhancedPath, 'utf-8');
    const match = enhancedContent.match(/const cameras = \[([\s\S]*?)\];/);
    
    if (!match) {
        console.error('❌ Could not parse enhanced camera database');
        process.exit(1);
    }

    const cameras = JSON.parse('[' + match[1] + ']');
    const unknownCameras = cameras.filter(c => c.city === 'Unknown');

    console.log(`Found ${unknownCameras.length} Unknown cameras\n`);
    console.log('Applying heuristic fixes...\n');

    // Apply heuristic fixes first (no OCR needed)
    const fixedCameras = applyHeuristicFixes(cameras);

    // Count remaining Unknown
    const stillUnknown = fixedCameras.filter(c => c.city === 'Unknown');
    console.log(`\n✅ Fixed ${unknownCameras.length - stillUnknown.length} cameras`);
    console.log(`⏳ Remaining Unknown: ${stillUnknown.length}\n`);

    // If Tesseract available, scan remaining Unknown cameras
    if (Tesseract && stillUnknown.length > 0) {
        console.log('🔍 Attempting OCR scan of remaining Unknown cameras...\n');
        
        for (const cam of stillUnknown) {
            const location = await extractLocationFromImage(cam.url, cam.id);
            if (location) {
                const idx = fixedCameras.findIndex(c => c.id === cam.id);
                if (idx >= 0) {
                    Object.assign(fixedCameras[idx], location);
                    console.log(`   Updated camera ${cam.id} with OCR data`);
                }
            }
            // Rate limit: 2 second delay between requests
            await new Promise(r => setTimeout(r, 2000));
        }
    }

    // Save fixed database
    const outputContent = `const cameras = ${JSON.stringify(fixedCameras, null, 2)};`;
    fs.writeFileSync('./camera-array-enhanced.js', outputContent);
    fs.writeFileSync('./public/camera-array-enhanced.js', outputContent);

    // Final statistics
    const finalUnknown = fixedCameras.filter(c => c.city === 'Unknown');
    console.log('\n' + '━'.repeat(50));
    console.log('\n✅ RESULTS:\n');
    console.log(`   Total cameras: ${fixedCameras.length}`);
    console.log(`   Unknown remaining: ${finalUnknown.length}`);
    console.log(`   Fixed: ${fixedCameras.length - finalUnknown.length}`);
    console.log('\n✅ Updated database saved!\n');
}

main().catch(err => {
    console.error('❌ Error:', err.message);
    process.exit(1);
});
