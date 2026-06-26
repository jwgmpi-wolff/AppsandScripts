/**
 * Base64 Decoder
 * Handles Base64 encoded IP addresses
 */

const { Base64 } = require('js-base64');

function decodeBase64(encodedString) {
  try {
    const decoded = Base64.decode(encodedString);
    return {
      success: true,
      decoded: decoded,
      method: 'base64'
    };
  } catch (error) {
    return {
      success: false,
      error: error.message,
      method: 'base64'
    };
  }
}

function isValidBase64(str) {
  try {
    return Base64.isValid(str);
  } catch {
    return false;
  }
}

module.exports = {
  decodeBase64,
  isValidBase64
};
