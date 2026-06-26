/**
 * Binary Decoder
 * Handles binary encoded IP addresses
 */

function decodeBinary(binaryString) {
  try {
    // Remove spaces and validate
    let cleanBinary = binaryString.replace(/\s+/g, '');
    
    if (!/^[01]+$/.test(cleanBinary)) {
      return {
        success: false,
        error: 'Invalid binary format',
        method: 'binary'
      };
    }

    // Split into 8-bit chunks if needed
    let chunks = [];
    if (cleanBinary.length % 8 !== 0) {
      return {
        success: false,
        error: 'Binary string length must be multiple of 8',
        method: 'binary'
      };
    }

    for (let i = 0; i < cleanBinary.length; i += 8) {
      chunks.push(cleanBinary.substr(i, 8));
    }

    let decoded = '';
    for (const chunk of chunks) {
      decoded += String.fromCharCode(parseInt(chunk, 2));
    }

    return {
      success: true,
      decoded: decoded,
      method: 'binary'
    };
  } catch (error) {
    return {
      success: false,
      error: error.message,
      method: 'binary'
    };
  }
}

function decodeDecimal(decimalString) {
  try {
    // Handle space or comma-separated decimal values
    const values = decimalString.split(/[\s,]+/).filter(v => v);
    let decoded = '';
    
    for (const val of values) {
      const num = parseInt(val, 10);
      if (isNaN(num) || num < 0 || num > 255) {
        throw new Error(`Invalid decimal value: ${val} (must be 0-255)`);
      }
      decoded += String.fromCharCode(num);
    }

    return {
      success: true,
      decoded: decoded,
      method: 'decimal'
    };
  } catch (error) {
    return {
      success: false,
      error: error.message,
      method: 'decimal'
    };
  }
}

module.exports = {
  decodeBinary,
  decodeDecimal
};
