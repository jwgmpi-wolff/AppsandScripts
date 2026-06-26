/**
 * Source Extractors Index
 * Provides unified interface for all IP extraction methods
 */

const PublicIPExtractor = require('./publicIPExtractor');
const HTMLIPExtractor = require('./htmlIPExtractor');
const DNSIPExtractor = require('./dnsIPExtractor');

class SourceExtractor {
  constructor() {
    this.publicIPExtractor = new PublicIPExtractor();
    this.htmlExtractor = new HTMLIPExtractor();
    this.dnsExtractor = new DNSIPExtractor();
  }

  /**
   * Extract IPs from public IP services
   */
  async extractFromPublicServices() {
    return await this.publicIPExtractor.extractAll();
  }

  /**
   * Extract IPs from a web page
   */
  async extractFromWebpage(url) {
    return await this.htmlExtractor.extractFromHTML(url);
  }

  /**
   * Extract IPs from JSON endpoint
   */
  async extractFromJSON(url) {
    return await this.htmlExtractor.extractFromJSON(url);
  }

  /**
   * Resolve hostname to IP
   */
  async resolveHostname(hostname) {
    return await this.dnsExtractor.resolveHostname(hostname);
  }

  /**
   * Reverse DNS lookup
   */
  async reverseLookup(ip) {
    return await this.dnsExtractor.reverseLookup(ip);
  }

  /**
   * Resolve MX records
   */
  async resolveMXRecords(domain) {
    return await this.dnsExtractor.resolveMXRecords(domain);
  }

  /**
   * Resolve TXT records
   */
  async resolveTXTRecords(domain) {
    return await this.dnsExtractor.resolveTXTRecords(domain);
  }

  /**
   * Resolve NS records
   */
  async resolveNSRecords(domain) {
    return await this.dnsExtractor.resolveNSRecords(domain);
  }

  /**
   * Full DNS lookup
   */
  async lookupAll(hostname) {
    return await this.dnsExtractor.lookupAll(hostname);
  }
}

module.exports = SourceExtractor;
