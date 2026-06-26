/**
 * URL Encoding Decoder
 * Handles URL-encoded IP addresses and special characters
 */

function decodeURLEncoding(encodedString) {
  try {
    const decoded = decodeURIComponent(encodedString);
    return {
      success: true,
      decoded: decoded,
      method: 'url-encoding'
    };
  } catch (error) {
    return {
      success: false,
      error: error.message,
      method: 'url-encoding'
    };
  }
}

function decodeDoubleURLEncoding(encodedString) {
  try {
    let decoded = decodeURIComponent(encodedString);
    decoded = decodeURIComponent(decoded);
    return {
      success: true,
      decoded: decoded,
      method: 'double-url-encoding'
    };
  } catch (error) {
    return {
      success: false,
      error: error.message,
      method: 'double-url-encoding'
    };
  }
}

module.exports = {
  decodeURLEncoding,
  decodeDoubleURLEncoding
};
