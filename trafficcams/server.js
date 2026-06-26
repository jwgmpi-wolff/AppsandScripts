const express = require('express');
const path = require('path');
const cors = require('cors');

const app = express();
const PORT = 80; // Port for trafficcams.local

// Middleware
app.use(cors());
app.use(express.static(path.join(__dirname, 'public')));

// Serve the dynamic traffic page
app.get('/', (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'trafficpage_dynamic.htm'));
});

// API endpoint for enhanced camera data with location classification
app.get('/api/cameras-enhanced', (req, res) => {
    try {
        const enhancedPath = path.join(__dirname, 'camera-array-enhanced.js');
        const fs = require('fs');
        const content = fs.readFileSync(enhancedPath, 'utf8');
        
        // Extract camera array from the file
        const match = content.match(/const cameras = \[([\s\S]*?)\];/);
        if (match) {
            // Parse the JSON array
            const camerasStr = '[' + match[1] + ']';
            const cameras = JSON.parse(camerasStr);
            res.json(cameras);
        } else {
            res.status(404).json({ error: 'Camera data not found' });
        }
    } catch (error) {
        res.status(500).json({ error: error.message });
    }
});

// Health check endpoint
app.get('/health', (req, res) => {
    res.json({ status: 'Server is running', timestamp: new Date().toISOString() });
});

// Start server
app.listen(PORT, '127.0.0.1', () => {
    console.log(`\n✅ Traffic Camera Server is running!`);
    console.log(`📍 Access at: http://trafficcams.local`);
    console.log(`🔗 Or direct: http://localhost:${PORT}`);
    console.log(`\nMake sure you've added the following to C:\\Windows\\System32\\drivers\\etc\\hosts:`);
    console.log(`127.0.0.1 trafficcams.local\n`);
    console.log(`Press Ctrl+C to stop the server\n`);
});

// Error handling
app.use((err, req, res, next) => {
    console.error(err.stack);
    res.status(500).send('Server error');
});
