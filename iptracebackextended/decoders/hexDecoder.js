/**
 * Hex Decoder
 * Handles hexadecimal encoded IP addresses
 */

function decodeHex(hexString) {
  try {
    // Remove common hex prefixes
    let cleanHex = hexString.replace(/^(0x|0X)/, '').replace(/\s+/g, '');
    
    // Check if valid hex
    if (!/^[0-9a-fA-F]*$/.test(cleanHex)) {
      return {
        success: false,
        error: 'Invalid hexadecimal format',
        method: 'hex'
      };
    }

    // Decode hex to ASCII
    let decoded = '';
    for (let i = 0; i < cleanHex.length; i += 2) {
      const hex = cleanHex.substr(i, 2);
      decoded += String.fromCharCode(parseInt(hex, 16));
    }

    return {
      success: true,
      decoded: decoded,
      method: 'hex'
    };
  } catch (error) {
    return {
      success: false,
      error: error.message,
      method: 'hex'
    };
  }
}

function decodeHexNumeric(hexValues) {
  try {
    // Handle space-separated or comma-separated hex values
    const values = hexValues.split(/[\s,]+/).filter(v => v);
    let decoded = '';
    
    for (const val of values) {
      const clean = val.replace(/^(0x|0X)/, '');
      if (!/^[0-9a-fA-F]+$/.test(clean)) {
        throw new Error(`Invalid hex value: ${val}`);
      }
      decoded += String.fromCharCode(parseInt(clean, 16));
    }

    return {
      success: true,
      decoded: decoded,
      method: 'hex-numeric'
    };
  } catch (error) {
    return {
      success: false,
      error: error.message,
      method: 'hex-numeric'
    };
  }
}

module.exports = {
  decodeHex,
  decodeHexNumeric
};
