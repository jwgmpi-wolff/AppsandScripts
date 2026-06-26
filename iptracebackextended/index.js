/**
 * IPTracebackExtended
 * Main entry point — orchestrates decoders, source extractors, and utils
 * to discover and analyse hidden or obfuscated IP addresses.
 */

'use strict';

const IPPDecoder     = require('./decoders');
const SourceExtractor = require('./sourceExtractors');
const {
  isValidIP,
  isValidIPv4,
  extractIPsFromText,
  classifyIP,
  IPGeolocator,
  buildTextReport,
  buildJSONReport,
  buildMarkdownReport
} = require('./utils');

class IPTracebackExtended {
  /**
   * @param {object} options
   * @param {boolean} [options.geolocate=false]      Auto-geolocate discovered IPs
   * @param {string}  [options.geoProvider='ipapi']  Geolocation provider
   * @param {boolean} [options.skipPrivate=true]      Skip private IPs during geolocation
   * @param {number}  [options.geoTimeout=8000]       Geolocation request timeout (ms)
   */
  constructor(options = {}) {
    this.options = {
      geolocate:   options.geolocate   ?? false,
      geoProvider: options.geoProvider ?? 'ipapi',
      skipPrivate: options.skipPrivate ?? true,
      geoTimeout:  options.geoTimeout  ?? 8000
    };

    this.decoder   = new IPPDecoder();
    this.extractor = new SourceExtractor();
    this.geolocator = new IPGeolocator({
      provider: this.options.geoProvider,
      timeout:  this.options.geoTimeout
    });
  }

  // ─────────────────────────────────────────────
  //  DECODING
  // ─────────────────────────────────────────────

  /**
   * Decode an encoded string and extract any valid IP addresses from the result.
   * @param {string} input
   * @param {string} [method='auto']
   * @returns {object}
   */
  decode(input, method = 'auto') {
    const rawResults = Array.isArray(this.decoder.decode(input, method))
      ? this.decoder.decode(input, method)
      : [this.decoder.decode(input, method)];

    const enriched = rawResults
      .filter(r => r.success)
      .map(r => {
        const { ipv4, ipv6 } = extractIPsFromText(r.decoded);
        return {
          input,
          method:  r.method,
          decoded: r.decoded,
          ips:     [...ipv4, ...ipv6],
          ...(r.shift !== undefined ? { shift: r.shift } : {})
        };
      });

    return {
      success: enriched.length > 0,
      input,
      results: enriched
    };
  }

  /**
   * Brute-force all decoders on the input
   * @param {string} input
   * @returns {object[]}
   */
  decodeAll(input) {
    return this.decode(input, 'auto').results;
  }

  // ─────────────────────────────────────────────
  //  SOURCE EXTRACTION
  // ─────────────────────────────────────────────

  /**
   * Query public IP discovery services (ipify, icanhazip, ident.me, etc.)
   * @returns {Promise<object[]>}
   */
  async discoverPublicIPs() {
    return this.extractor.extractFromPublicServices();
  }

  /**
   * Scrape a URL for IP addresses embedded in HTML / comments / data attributes
   * @param {string} url
   * @returns {Promise<object>}
   */
  async scrapeURL(url) {
    return this.extractor.extractFromWebpage(url);
  }

  /**
   * Extract IPs from a JSON endpoint
   * @param {string} url
   * @returns {Promise<object>}
   */
  async scrapeJSON(url) {
    return this.extractor.extractFromJSON(url);
  }

  // ─────────────────────────────────────────────
  //  DNS
  // ─────────────────────────────────────────────

  /**
   * Resolve a hostname to IP addresses (A records)
   * @param {string} hostname
   * @returns {Promise<object>}
   */
  async resolveHostname(hostname) {
    return this.extractor.resolveHostname(hostname);
  }

  /**
   * Reverse-DNS lookup
   * @param {string} ip
   * @returns {Promise<object>}
   */
  async reverseLookup(ip) {
    return this.extractor.reverseLookup(ip);
  }

  /**
   * Full DNS record lookup (A, MX, TXT, NS, reverse)
   * @param {string} hostname
   * @returns {Promise<object>}
   */
  async dnsLookupAll(hostname) {
    const [a, mx, txt, ns] = await Promise.allSettled([
      this.extractor.resolveHostname(hostname),
      this.extractor.resolveMXRecords(hostname),
      this.extractor.resolveTXTRecords(hostname),
      this.extractor.resolveNSRecords(hostname)
    ]);

    const aResult   = a.status   === 'fulfilled' ? a.value   : { success: false };
    const mxResult  = mx.status  === 'fulfilled' ? mx.value  : { success: false };
    const txtResult = txt.status === 'fulfilled' ? txt.value : { success: false };
    const nsResult  = ns.status  === 'fulfilled' ? ns.value  : { success: false };

    return {
      hostname,
      ips:       aResult.success   ? aResult.ips           : [],
      mxRecords: mxResult.success  ? mxResult.mxRecords    : [],
      txtRecords: txtResult.success ? txtResult.txtRecords  : [],
      nsRecords: nsResult.success  ? nsResult.nsRecords    : [],
      errors: [
        ...(!aResult.success   ? [`A:   ${aResult.error}`]   : []),
        ...(!mxResult.success  ? [`MX:  ${mxResult.error}`]  : []),
        ...(!txtResult.success ? [`TXT: ${txtResult.error}`] : []),
        ...(!nsResult.success  ? [`NS:  ${nsResult.error}`]  : [])
      ]
    };
  }

  // ─────────────────────────────────────────────
  //  GEOLOCATION
  // ─────────────────────────────────────────────

  /**
   * Geolocate a single IP address
   * @param {string} ip
   * @returns {Promise<object>}
   */
  async geolocate(ip) {
    return this.geolocator.lookup(ip);
  }

  /**
   * Geolocate multiple IP addresses (rate-limited batch)
   * @param {string[]} ips
   * @returns {Promise<object[]>}
   */
  async geolocateBatch(ips) {
    if (this.options.skipPrivate) {
      return this.geolocator.lookupPublicOnly(ips);
    }
    return this.geolocator.lookupBatch(ips);
  }

  // ─────────────────────────────────────────────
  //  CLASSIFICATION
  // ─────────────────────────────────────────────

  /**
   * Classify an IP address (version, class, private/public, hex, binary)
   * @param {string} ip
   * @returns {object}
   */
  classify(ip) {
    return classifyIP(ip);
  }

  /**
   * Classify multiple IPs
   * @param {string[]} ips
   * @returns {object[]}
   */
  classifyAll(ips) {
    return ips.map(ip => classifyIP(ip));
  }

  // ─────────────────────────────────────────────
  //  FULL TRACEBACK PIPELINE
  // ─────────────────────────────────────────────

  /**
   * Run the complete traceback pipeline:
   *   1. Decode any encoded inputs to extract hidden IPs
   *   2. Query public IP discovery services
   *   3. Optionally scrape additional URLs
   *   4. Classify all discovered IPs
   *   5. Optionally geolocate public IPs
   *
   * @param {object} params
   * @param {string[]}  [params.encodedInputs=[]]  Encoded strings to decode
   * @param {string[]}  [params.scrapeURLs=[]]     URLs to scrape for IPs
   * @param {string[]}  [params.hostnames=[]]      Hostnames to resolve via DNS
   * @param {boolean}   [params.discoverPublic=true] Query public IP services
   * @param {boolean}   [params.geolocate]         Override instance-level setting
   * @returns {Promise<object>}  Full result object
   */
  async traceback(params = {}) {
    const {
      encodedInputs  = [],
      scrapeURLs     = [],
      hostnames      = [],
      discoverPublic = true,
      geolocate      = this.options.geolocate
    } = params;

    const errors       = [];
    const sourceIPs    = [];
    const decodedIPs   = [];
    const allIPs       = new Set();

    // 1. Decode encoded inputs
    for (const input of encodedInputs) {
      try {
        const decoded = this.decodeAll(input);
        decoded.forEach(r => {
          decodedIPs.push(r);
          r.ips.forEach(ip => allIPs.add(ip));
        });
      } catch (err) {
        errors.push(`Decode error for "${input}": ${err.message}`);
      }
    }

    // 2. Public IP discovery
    if (discoverPublic) {
      try {
        const publicResults = await this.discoverPublicIPs();
        publicResults.forEach(r => {
          if (r.success && r.ip) {
            sourceIPs.push({ ip: r.ip, source: r.source });
            allIPs.add(r.ip);
          }
        });
      } catch (err) {
        errors.push(`Public IP discovery error: ${err.message}`);
      }
    }

    // 3. URL scraping
    for (const url of scrapeURLs) {
      try {
        const scraped = await this.scrapeURL(url);
        if (scraped.success) {
          scraped.ips.forEach(ip => {
            sourceIPs.push({ ip, source: url });
            allIPs.add(ip);
          });
        } else {
          errors.push(`Scrape error for "${url}": ${scraped.error}`);
        }
      } catch (err) {
        errors.push(`Scrape error for "${url}": ${err.message}`);
      }
    }

    // 4. DNS resolution
    const dnsResults = [];
    for (const hostname of hostnames) {
      try {
        const dns = await this.dnsLookupAll(hostname);
        dnsResults.push(dns);
        dns.ips.forEach(ip => {
          sourceIPs.push({ ip, source: `dns:${hostname}` });
          allIPs.add(ip);
        });
        if (dns.errors.length > 0) {
          dns.errors.forEach(e => errors.push(`DNS(${hostname}): ${e}`));
        }
      } catch (err) {
        errors.push(`DNS error for "${hostname}": ${err.message}`);
      }
    }

    // 5. Classify all IPs
    const ipList = Array.from(allIPs).filter(ip => isValidIP(ip));
    const classifications = this.classifyAll(ipList);

    // 6. Geolocation
    let geolocations = [];
    if (geolocate && ipList.length > 0) {
      try {
        geolocations = await this.geolocateBatch(ipList);
      } catch (err) {
        errors.push(`Geolocation error: ${err.message}`);
      }
    }

    return {
      timestamp:       Date.now(),
      sourceIPs,
      decodedIPs,
      dnsResults:      dnsResults.length === 1 ? dnsResults[0] : (dnsResults.length > 1 ? dnsResults : null),
      classifications,
      geolocations,
      allIPs:          ipList,
      errors
    };
  }

  // ─────────────────────────────────────────────
  //  REPORTING
  // ─────────────────────────────────────────────

  /**
   * Generate a human-readable text report from a traceback result
   * @param {object} result  - return value of traceback()
   * @returns {string}
   */
  generateReport(result, format = 'text') {
    switch (format.toLowerCase()) {
      case 'json':     return buildJSONReport(result);
      case 'markdown':
      case 'md':       return buildMarkdownReport(result);
      case 'text':
      default:         return buildTextReport(result);
    }
  }
}

module.exports = IPTracebackExtended;
