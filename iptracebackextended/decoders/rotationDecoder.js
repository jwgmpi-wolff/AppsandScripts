/**
 * Rotation Decoder
 * Handles ROT13, Caesar cipher, and other character rotation techniques
 */

function decodeROT13(encodedString) {
  return decodeCaesar(encodedString, 13);
}

function decodeCaesar(encodedString, shift = 1) {
  try {
    let decoded = '';
    
    for (let i = 0; i < encodedString.length; i++) {
      const charCode = encodedString.charCodeAt(i);
      
      // Handle uppercase letters
      if (charCode >= 65 && charCode <= 90) {
        decoded += String.fromCharCode(((charCode - 65 - shift) % 26 + 26) % 26 + 65);
      }
      // Handle lowercase letters
      else if (charCode >= 97 && charCode <= 122) {
        decoded += String.fromCharCode(((charCode - 97 - shift) % 26 + 26) % 26 + 97);
      }
      // Keep non-alphabetic characters as-is
      else {
        decoded += encodedString[i];
      }
    }
    
    return {
      success: true,
      decoded: decoded,
      method: `caesar-shift-${shift}`,
      shift: shift
    };
  } catch (error) {
    return {
      success: false,
      error: error.message,
      method: 'caesar'
    };
  }
}

function bruteForceRotation(encodedString) {
  const results = [];
  
  for (let shift = 1; shift <= 25; shift++) {
    const result = decodeCaesar(encodedString, shift);
    if (result.success) {
      results.push(result);
    }
  }
  
  return {
    success: true,
    results: results,
    method: 'brute-force-rotation'
  };
}

module.exports = {
  decodeROT13,
  decodeCaesar,
  bruteForceRotation
};
