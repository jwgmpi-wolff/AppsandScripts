/**
 * Main Decoder Index
 * Provides unified interface for all decoding methods
 */

const base64Decoder = require('./base64Decoder');
const hexDecoder = require('./hexDecoder');
const urlEncodingDecoder = require('./urlEncodingDecoder');
const binaryDecoder = require('./binaryDecoder');
const rotationDecoder = require('./rotationDecoder');

class IPPDecoder {
  constructor() {
    this.decoders = {
      base64: base64Decoder,
      hex: hexDecoder,
      urlEncoding: urlEncodingDecoder,
      binary: binaryDecoder,
      rotation: rotationDecoder
    };
  }

  /**
   * Try all decoding methods on the given input
   * @param {string} input - The encoded string to decode
   * @returns {array} Array of successful decodings
   */
  tryAllDecoders(input) {
    const results = [];

    // Try Base64
    if (base64Decoder.isValidBase64(input)) {
      const result = base64Decoder.decodeBase64(input);
      if (result.success) results.push(result);
    }

    // Try Hex
    const hexResult = hexDecoder.decodeHex(input);
    if (hexResult.success) results.push(hexResult);

    // Try URL Encoding
    if (input.includes('%')) {
      const urlResult = urlEncodingDecoder.decodeURLEncoding(input);
      if (urlResult.success) results.push(urlResult);
    }

    // Try Binary
    if (/^[01\s]+$/.test(input)) {
      const binaryResult = binaryDecoder.decodeBinary(input);
      if (binaryResult.success) results.push(binaryResult);
    }

    // Try Decimal
    if (/^[\d\s,]+$/.test(input)) {
      const decimalResult = binaryDecoder.decodeDecimal(input);
      if (decimalResult.success) results.push(decimalResult);
    }

    // Try ROT13
    const rot13Result = rotationDecoder.decodeROT13(input);
    if (rot13Result.success && rot13Result.decoded !== input) {
      results.push(rot13Result);
    }

    return results;
  }

  /**
   * Decode using a specific method
   * @param {string} input - The encoded string
   * @param {string} method - The decoding method to use
   * @returns {object} Decoding result
   */
  decode(input, method = 'auto') {
    if (method === 'auto') {
      return this.tryAllDecoders(input);
    }

    switch (method.toLowerCase()) {
      case 'base64':
        return base64Decoder.decodeBase64(input);
      case 'hex':
        return hexDecoder.decodeHex(input);
      case 'hex-numeric':
        return hexDecoder.decodeHexNumeric(input);
      case 'url':
      case 'url-encoding':
        return urlEncodingDecoder.decodeURLEncoding(input);
      case 'double-url':
        return urlEncodingDecoder.decodeDoubleURLEncoding(input);
      case 'binary':
        return binaryDecoder.decodeBinary(input);
      case 'decimal':
        return binaryDecoder.decodeDecimal(input);
      case 'rot13':
        return rotationDecoder.decodeROT13(input);
      case 'caesar':
        const shift = arguments[2] || 1;
        return rotationDecoder.decodeCaesar(input, shift);
      case 'brute-force':
        return rotationDecoder.bruteForceRotation(input);
      default:
        return {
          success: false,
          error: `Unknown decoding method: ${method}`
        };
    }
  }
}

module.exports = IPPDecoder;
