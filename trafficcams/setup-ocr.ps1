# Install OCR and geocoding dependencies
Write-Host "📦 Installing OCR dependencies..."

# Check if npm is available
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    Write-Host "❌ npm not found. Please install Node.js first."
    exit 1
}

cd C:\.git\trafficcams

# Install tesseract.js for OCR
Write-Host "`n📦 Installing tesseract.js..."
npm install tesseract.js --save

# Install sharp for image processing (alternative/faster)
Write-Host "`n📦 Installing image processing libraries..."
npm install sharp --save

# Optional: Install axios for better HTTP requests
Write-Host "`n📦 Installing axios..."
npm install axios --save

Write-Host "`n✅ Dependencies installed!"
Write-Host "`n🚀 To process cameras with OCR, run:"
Write-Host "   node ocr-processor.js [count]"
Write-Host "`n   Example: node ocr-processor.js 50  (processes 50 cameras)"
