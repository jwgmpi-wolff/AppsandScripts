const fs = require('fs');
const path = require('path');
const https = require('https');

// Load Tesseract if available
let Tesseract = null;
try {
    Tesseract = require('tesseract.js');
    console.log('✅ Tesseract.js loaded for OCR\n');
} catch (e) {
    console.log('❌ Tesseract.js not installed. Run: npm install tesseract.js');
    console.log('📝 For now, applying heuristic fixes...\n');
}

const cameraData = require('./camera-array.js');

// Heuristic mappings based on camera location patterns
const LOCATION_HINTS = {
    'WSDOT Highway': {
        cities: ['Olympia', 'Salem', 'Portland'],
        region: 'South Puget Sound',
        highway: 'I-5'
    },
    'Regional Airport': {
        cities: ['Seattle', 'Tacoma', 'Portland'],
        region: 'Central Puget Sound',
        type: 'Airport Camera'
    }
};

/**
 * Extract location from camera image using OCR
 */
async function extractLocationFromImage(url, cameraId) {
    if (!Tesseract) {
        console.log(`⏭️  Camera ${cameraId}: Skipping OCR (tesseract not installed)`);
        return null;
    }

    try {
        console.log(`🔍 Scanning camera ${cameraId} for location text...`);
        
        // Download image
        const imageBuffer = await downloadImage(url);
        if (!imageBuffer) return null;

        // Use Tesseract to extract text
        const result = await Tesseract.recognize(imageBuffer, 'eng', {
            logger: m => {
                if (m.status === 'recognizing text') {
                    process.stdout.write(`\r   Progress: ${Math.round(m.progress * 100)}%`);
                }
            }
        });

        const text = result.data.text.toUpperCase();
        console.log(`\n   Extracted text: "${text.substring(0, 100)}..."`);

        // Parse for location clues
        const location = parseLocationFromText(text);
        if (location) {
            console.log(`   ✅ Found: ${JSON.stringify(location)}\n`);
            return location;
        }
        console.log('   ⚠️  No clear location found in text\n');
        return null;

    } catch (error) {
        console.log(`\n   ❌ Error scanning camera ${cameraId}: ${error.message}`);
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
 * Apply heuristic fixes to Unknown cameras
 */
function applyHeuristicFixes(cameras) {
    const fixed = [];
    cameras.forEach((cam, idx) => {
        const updated = { ...cam };

        if (updated.city === 'Unknown') {
            const location = updated.location || '';
            
            // Rule 1: "WSDOT Highway" without clear code -> Central Washington
            if (location.includes('WSDOT Highway')) {
                updated.city = 'Olympia';
                updated.highway = updated.highway || 'US-395';
                updated.region = updated.region || 'South Puget Sound';
                console.log(`✅ Fixed camera ${updated.id}: ${location} -> Olympia`);
            }
            // Rule 2: "Regional Airport" -> Assign to nearest airport
            else if (location.includes('Regional Airport')) {
                updated.city = 'Seattle';
                updated.type = 'Airport Camera';
                updated.region = updated.region || 'Central Puget Sound';
                console.log(`✅ Fixed camera ${updated.id}: ${location} -> Seattle (Airport)`);
            }
        }
        fixed.push(updated);
    });
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
