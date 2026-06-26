const fs = require('fs');
const path = require('path');

// Comprehensive location mapping for Puget Sound area cameras
const LOCATION_DATABASE = {
    // I-5 Corridor
    '002': {
        highway: 'US-2',
        corridor: 'US-2 Corridor',
        cities: ['Monroe', 'Everett', 'Snohomish', 'Marysville'],
        region: 'North Puget Sound',
        state: 'WA'
    },
    '005': {
        highway: 'I-5',
        corridor: 'I-5 Corridor',
        cities: ['Seattle', 'Tacoma', 'Olympia', 'Federal Way', 'Des Moines'],
        region: 'Central Puget Sound',
        state: 'WA'
    },
    '090': {
        highway: 'I-90',
        corridor: 'I-90 Corridor',
        cities: ['Seattle', 'Bellevue', 'Snoqualmie', 'North Bend', 'Spokane'],
        region: 'Central/Eastern WA',
        state: 'WA'
    },
    '099': {
        highway: 'WA-99',
        corridor: 'WA-99 Corridor',
        cities: ['Seattle', 'Shoreline', 'Edmonds', 'Lynnwood', 'Mountlake Terrace'],
        region: 'North Puget Sound',
        state: 'WA'
    },
    '167': {
        highway: 'WA-167',
        corridor: 'WA-167 Corridor (Valley Freeway)',
        cities: ['Renton', 'Kent', 'Auburn', 'Enumclaw'],
        region: 'South King County',
        state: 'WA'
    },
    '169': {
        highway: 'WA-169',
        corridor: 'WA-169 Corridor',
        cities: ['Kent', 'Enumclaw', 'Black Diamond'],
        region: 'Southeast King County',
        state: 'WA'
    },
    '181': {
        highway: 'WA-181',
        corridor: 'WA-181 Corridor',
        cities: ['Kent', 'Covington', 'Maple Valley'],
        region: 'South King County',
        state: 'WA'
    },
    '202': {
        highway: 'WA-202',
        corridor: 'WA-202 Corridor',
        cities: ['Snohomish', 'Monroe', 'Sultan'],
        region: 'Northeast King County',
        state: 'WA'
    },
    '204': {
        highway: 'WA-204',
        corridor: 'WA-204 Corridor (238th St SW)',
        cities: ['Shoreline', 'Edmonds', 'Lynnwood'],
        region: 'North Puget Sound',
        state: 'WA'
    },
    '405': {
        highway: 'I-405',
        corridor: 'I-405 Corridor',
        cities: ['Renton', 'Bellevue', 'Lynnwood', 'Bothell', 'Northgate'],
        region: 'King County',
        state: 'WA'
    },
    '509': {
        highway: 'WA-509',
        corridor: 'WA-509 Corridor (SeaTac Blvd)',
        cities: ['Seattle', 'Burien', 'Sea-Tac', 'Des Moines', 'Vashon'],
        region: 'Southwest King County',
        state: 'WA'
    },
    '515': {
        highway: 'WA-515',
        corridor: 'WA-515 Corridor',
        cities: ['Tacoma', 'Puyallup', 'Fife'],
        region: 'South Puget Sound',
        state: 'WA'
    },
    '516': {
        highway: 'WA-516',
        corridor: 'WA-516 Corridor',
        cities: ['Renton', 'Auburn', 'Kent'],
        region: 'South King County',
        state: 'WA'
    },
    '518': {
        highway: 'WA-518',
        corridor: 'WA-518 Corridor (South 188th)',
        cities: ['Sea-Tac', 'Burien', 'Tukwila'],
        region: 'Southwest King County',
        state: 'WA'
    },
    '520': {
        highway: 'WA-520',
        corridor: 'WA-520 Floating Bridge',
        cities: ['Seattle', 'Bellevue', 'Redmond', 'Medina'],
        region: 'Central King County',
        state: 'WA'
    },
    '522': {
        highway: 'WA-522',
        corridor: 'WA-522 Corridor',
        cities: ['Bothell', 'Monroe', 'Snohomish', 'Marysville'],
        region: 'North Puget Sound',
        state: 'WA'
    },
    '525': {
        highway: 'WA-525',
        corridor: 'WA-525 Corridor',
        cities: ['Edmonds', 'Marysville', 'Lynnwood'],
        region: 'North Puget Sound',
        state: 'WA'
    },
    '526': {
        highway: 'WA-526',
        corridor: 'WA-526 Corridor',
        cities: ['Marysville', 'Arlington', 'Stanwood'],
        region: 'North Puget Sound',
        state: 'WA'
    },
    '527': {
        highway: 'WA-527',
        corridor: 'WA-527 Corridor',
        cities: ['Everett', 'Marysville', 'Mill Creek'],
        region: 'North Puget Sound',
        state: 'WA'
    },
    '529': {
        highway: 'WA-529',
        corridor: 'WA-529 Corridor',
        cities: ['Marysville', 'Smokey Point', 'Arlington'],
        region: 'North Puget Sound',
        state: 'WA'
    },
    '531': {
        highway: 'WA-531',
        corridor: 'WA-531 Corridor',
        cities: ['Olympia', 'Lacey', 'Tumwater', 'Rochester'],
        region: 'South Puget Sound',
        state: 'WA'
    },
    '599': {
        highway: 'US-99',
        corridor: 'US-99 Corridor (Alternative)',
        cities: ['Longview', 'Centralia', 'Olympia'],
        region: 'Southwest Washington',
        state: 'WA'
    },
};

const AIRPORT_LOCATIONS = {
    'arlington': {
        city: 'Arlington',
        state: 'WA',
        county: 'Snohomish County',
        airport: 'Arlington Municipal Airport',
        region: 'North Puget Sound'
    },
    'auburn': {
        city: 'Auburn',
        state: 'WA',
        county: 'King County',
        airport: 'Auburn-Gnaw Bone Airport',
        region: 'South King County'
    },
    'renton': {
        city: 'Renton',
        state: 'WA',
        county: 'King County',
        airport: 'Renton Municipal Airport',
        region: 'South King County'
    },
    'seattle': {
        city: 'Seattle',
        state: 'WA',
        county: 'King County',
        airport: 'Seattle-Tacoma International',
        region: 'Central Puget Sound'
    },
};

const EVERETT_LOCATIONS = {
    'downtown': { city: 'Everett', area: 'Downtown', county: 'Snohomish County' },
    'broadway': { city: 'Everett', area: 'Broadway', county: 'Snohomish County' },
    'marine': { city: 'Everett', area: 'Marine Drive', county: 'Snohomish County' },
    'colby': { city: 'Everett', area: 'Colby/112th', county: 'Snohomish County' },
    'evergreen': { city: 'Everett', area: 'Evergreen', county: 'Snohomish County' },
};

/**
 * Extract location info from camera data
 */
function extractLocationFromCamera(camera) {
    const location = {};
    const locationStr = camera.location.toLowerCase();
    const url = camera.url.toLowerCase();

    if (camera.source === 'wsdot') {
        // Extract highway code from location string
        const codeMatch = camera.location.match(/(\d{3})/);
        if (codeMatch) {
            const code = codeMatch[1];
            const info = LOCATION_DATABASE[code];
            if (info) {
                location.highway = info.highway;
                location.corridor = info.corridor;
                location.primary_city = info.cities[0];
                location.all_cities = info.cities;
                location.region = info.region;
                location.state = info.state;
                location.county = 'Multiple';
                location.type = 'Highway Camera';
            }
        }
    } else if (camera.source === 'airport') {
        for (const [key, info] of Object.entries(AIRPORT_LOCATIONS)) {
            if (url.includes(key) || locationStr.includes(key)) {
                location.city = info.city;
                location.state = info.state;
                location.county = info.county;
                location.airport = info.airport;
                location.region = info.region;
                location.type = 'Airport Camera';
                break;
            }
        }
    } else if (camera.source === 'everett') {
        location.city = 'Everett';
        location.state = 'WA';
        location.county = 'Snohomish County';
        location.region = 'North Puget Sound';
        location.type = 'City Traffic Camera';
        
        for (const [key, info] of Object.entries(EVERETT_LOCATIONS)) {
            if (locationStr.includes(key) || url.includes(key)) {
                location.area = info.area;
                break;
            }
        }
    }

    return location;
}

/**
 * Generate enhanced camera database with location classification
 */
function generateEnhancedDatabase(inputFile, outputFile) {
    // Read original camera array
    const content = fs.readFileSync(inputFile, 'utf8');
    const cameraArrayMatch = content.match(/const cameras = \[([\s\S]*?)\];/);
    
    if (!cameraArrayMatch) {
        console.error('❌ Could not parse camera array');
        return;
    }

    // Parse cameras
    const camerasStr = '[' + cameraArrayMatch[1] + ']';
    const cameras = eval(camerasStr);

    // Enhance each camera with location data
    const enhanced = cameras.map(cam => {
        const location = extractLocationFromCamera(cam);
        return {
            ...cam,
            ...location,
            city: location.primary_city || location.city || 'Unknown',
            state: location.state || 'WA',
            county: location.county || 'Unknown'
        };
    });

    // Generate new JavaScript file
    let output = 'const cameras = [\n';
    enhanced.forEach((cam, idx) => {
        const props = Object.entries(cam)
            .map(([k, v]) => `"${k}":"${String(v).replace(/"/g, '\\"')}"`)
            .join(',');
        output += `    {${props}}${idx < enhanced.length - 1 ? ',' : ''}\n`;
    });
    output += '];\n';

    // Add module exports
    output += 'if (typeof module !== "undefined" && module.exports) { module.exports = cameras; }\n';

    fs.writeFileSync(outputFile, output);
    console.log(`✅ Enhanced database written to: ${outputFile}`);

    // Generate summary
    generateSummary(enhanced);

    return enhanced;
}

/**
 * Generate location summary report
 */
function generateSummary(cameras) {
    const cityCounts = {};
    const highwayCounts = {};
    const typeCounts = {};

    cameras.forEach(cam => {
        // City count
        if (cam.city && cam.city !== 'Unknown') {
            cityCounts[cam.city] = (cityCounts[cam.city] || 0) + 1;
        }
        
        // Highway count
        if (cam.highway) {
            highwayCounts[cam.highway] = (highwayCounts[cam.highway] || 0) + 1;
        }
        
        // Type count
        if (cam.type) {
            typeCounts[cam.type] = (typeCounts[cam.type] || 0) + 1;
        }
    });

    console.log('\n📊 LOCATION SUMMARY');
    console.log('═'.repeat(50));
    
    console.log('\n🏙️  TOP CITIES:');
    Object.entries(cityCounts)
        .sort((a, b) => b[1] - a[1])
        .slice(0, 15)
        .forEach(([city, count]) => {
            console.log(`   ${city.padEnd(20)} ${count.toString().padStart(3)} cameras`);
        });

    console.log('\n🛣️  HIGHWAYS:');
    Object.entries(highwayCounts)
        .sort((a, b) => b[1] - a[1])
        .forEach(([hwy, count]) => {
            console.log(`   ${hwy.padEnd(10)} ${count.toString().padStart(3)} cameras`);
        });

    console.log('\n📹 CAMERA TYPES:');
    Object.entries(typeCounts)
        .forEach(([type, count]) => {
            console.log(`   ${type.padEnd(25)} ${count.toString().padStart(3)} cameras`);
        });

    console.log('\n═'.repeat(50));

    // Save JSON report
    const report = {
        timestamp: new Date().toISOString(),
        total_cameras: cameras.length,
        by_city: cityCounts,
        by_highway: highwayCounts,
        by_type: typeCounts,
        regions: {
            'North Puget Sound': cameras.filter(c => c.region === 'North Puget Sound').length,
            'Central Puget Sound': cameras.filter(c => c.region === 'Central Puget Sound').length,
            'South Puget Sound': cameras.filter(c => c.region === 'South Puget Sound').length,
            'South King County': cameras.filter(c => c.region === 'South King County').length,
            'Central King County': cameras.filter(c => c.region === 'Central King County').length,
            'Southeast King County': cameras.filter(c => c.region === 'Southeast King County').length,
        }
    };

    fs.writeFileSync(
        path.join(__dirname, 'location-summary.json'),
        JSON.stringify(report, null, 2)
    );

    console.log('\n✅ Summary saved to: location-summary.json');
}

// Run if called directly
if (require.main === module) {
    const inputFile = process.argv[2] || path.join(__dirname, 'camera-array.js');
    const outputFile = process.argv[3] || path.join(__dirname, 'camera-array-enhanced.js');
    
    console.log(`\n🔄 Enhancing camera database with location classification...`);
    console.log(`   Input:  ${inputFile}`);
    console.log(`   Output: ${outputFile}\n`);
    
    try {
        generateEnhancedDatabase(inputFile, outputFile);
    } catch (error) {
        console.error('❌ Error:', error.message);
    }
}

module.exports = {
    generateEnhancedDatabase,
    extractLocationFromCamera,
    LOCATION_DATABASE,
    AIRPORT_LOCATIONS,
    EVERETT_LOCATIONS
};
