/**
 * IP Validator
 * Utilities for validating and classifying IP addresses (IPv4 and IPv6)
 */

const net = require('net');

const PRIVATE_RANGES_V4 = [
  { start: '10.0.0.0',     end: '10.255.255.255'   },
  { start: '172.16.0.0',   end: '172.31.255.255'   },
  { start: '192.168.0.0',  end: '192.168.255.255'  },
  { start: '127.0.0.0',    end: '127.255.255.255'  }, // loopback
  { start: '169.254.0.0',  end: '169.254.255.255'  }, // link-local
  { start: '0.0.0.0',      end: '0.255.255.255'    }, // this network
  { start: '100.64.0.0',   end: '100.127.255.255'  }, // shared address (RFC 6598)
  { start: '192.0.0.0',    end: '192.0.0.255'      }, // IETF protocol
  { start: '192.0.2.0',    end: '192.0.2.255'      }, // TEST-NET-1
  { start: '198.51.100.0', end: '198.51.100.255'   }, // TEST-NET-2
  { start: '203.0.113.0',  end: '203.0.113.255'    }, // TEST-NET-3
  { start: '240.0.0.0',    end: '255.255.255.254'  }, // reserved
  { start: '255.255.255.255', end: '255.255.255.255' } // broadcast
];

function ipToLong(ip) {
  return ip.split('.').reduce((acc, octet) => (acc << 8) + parseInt(octet, 10), 0) >>> 0;
}

/**
 * Validate an IPv4 address
 * @param {string} ip
 * @returns {boolean}
 */
function isValidIPv4(ip) {
  return net.isIPv4(ip);
}

/**
 * Validate an IPv6 address
 * @param {string} ip
 * @returns {boolean}
 */
function isValidIPv6(ip) {
  return net.isIPv6(ip);
}

/**
 * Validate any IP address (IPv4 or IPv6)
 * @param {string} ip
 * @returns {boolean}
 */
function isValidIP(ip) {
  return net.isIP(ip) !== 0;
}

/**
 * Check if an IPv4 address falls within a private/reserved range
 * @param {string} ip
 * @returns {boolean}
 */
function isPrivateIP(ip) {
  if (!isValidIPv4(ip)) return false;
  const long = ipToLong(ip);
  return PRIVATE_RANGES_V4.some(range => {
    return long >= ipToLong(range.start) && long <= ipToLong(range.end);
  });
}

/**
 * Check if an IPv4 is a public (routable) address
 * @param {string} ip
 * @returns {boolean}
 */
function isPublicIP(ip) {
  return isValidIPv4(ip) && !isPrivateIP(ip);
}

/**
 * Return the IP version (4, 6, or null)
 * @param {string} ip
 * @returns {number|null}
 */
function getIPVersion(ip) {
  const version = net.isIP(ip);
  return version === 0 ? null : version;
}

/**
 * Classify an IPv4 address by its class (A/B/C/D/E)
 * @param {string} ip
 * @returns {string|null}
 */
function getIPv4Class(ip) {
  if (!isValidIPv4(ip)) return null;
  const firstOctet = parseInt(ip.split('.')[0], 10);
  if (firstOctet >= 1   && firstOctet <= 126)  return 'A';
  if (firstOctet === 127)                       return 'loopback';
  if (firstOctet >= 128 && firstOctet <= 191)  return 'B';
  if (firstOctet >= 192 && firstOctet <= 223)  return 'C';
  if (firstOctet >= 224 && firstOctet <= 239)  return 'D (multicast)';
  if (firstOctet >= 240 && firstOctet <= 255)  return 'E (reserved)';
  return null;
}

/**
 * Extract all valid IP addresses from a free-form string
 * @param {string} text
 * @returns {{ ipv4: string[], ipv6: string[] }}
 */
function extractIPsFromText(text) {
  const ipv4Regex = /\b(?:\d{1,3}\.){3}\d{1,3}\b/g;
  // Simplified IPv6 pattern (covers full and compressed forms)
  const ipv6Regex = /(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{1,4}|::(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4}|[0-9a-fA-F]{1,4}::(?:[0-9a-fA-F]{1,4}:){0,5}[0-9a-fA-F]{1,4}/g;

  const ipv4Raw = text.match(ipv4Regex) || [];
  const ipv4 = ipv4Raw.filter(isValidIPv4);

  const ipv6Raw = text.match(ipv6Regex) || [];
  const ipv6 = ipv6Raw.filter(isValidIPv6);

  return {
    ipv4: [...new Set(ipv4)],
    ipv6: [...new Set(ipv6)]
  };
}

/**
 * Full classification info for an IP address
 * @param {string} ip
 * @returns {object}
 */
function classifyIP(ip) {
  const version = getIPVersion(ip);
  if (!version) {
    return { valid: false, ip };
  }

  const info = {
    valid: true,
    ip,
    version,
  };

  if (version === 4) {
    info.isPrivate = isPrivateIP(ip);
    info.isPublic  = !info.isPrivate;
    info.class     = getIPv4Class(ip);
    const octets   = ip.split('.').map(Number);
    info.octets    = octets;
    info.numeric   = ipToLong(ip);
    info.hex       = octets.map(o => o.toString(16).padStart(2, '0')).join(':');
    info.binary    = octets.map(o => o.toString(2).padStart(8, '0')).join('.');
  } else {
    info.isLoopback = ip === '::1';
    info.expanded   = expandIPv6(ip);
  }

  return info;
}

/**
 * Expand a compressed IPv6 address to full form
 * @param {string} ip
 * @returns {string}
 */
function expandIPv6(ip) {
  if (!isValidIPv6(ip)) return ip;
  // Handle :: expansion
  const halves = ip.split('::');
  let full;
  if (halves.length === 2) {
    const left  = halves[0] ? halves[0].split(':') : [];
    const right = halves[1] ? halves[1].split(':') : [];
    const missing = 8 - left.length - right.length;
    const middle = Array(missing).fill('0000');
    full = [...left, ...middle, ...right];
  } else {
    full = ip.split(':');
  }
  return full.map(g => g.padStart(4, '0')).join(':');
}

module.exports = {
  isValidIP,
  isValidIPv4,
  isValidIPv6,
  isPrivateIP,
  isPublicIP,
  getIPVersion,
  getIPv4Class,
  extractIPsFromText,
  classifyIP,
  expandIPv6,
  ipToLong
};
