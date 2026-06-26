/**
 * DNS/Network IP Extractor
 * Resolves hostnames and extracts IP information from DNS records
 */

const dns = require('dns').promises;
const net = require('net');

class DNSIPExtractor {
  async resolveHostname(hostname) {
    try {
      const addresses = await dns.resolve4(hostname);
      return {
        success: true,
        hostname: hostname,
        ips: addresses,
        method: 'dns-a-record'
      };
    } catch (error) {
      return {
        success: false,
        error: error.message,
        hostname: hostname,
        method: 'dns-a-record'
      };
    }
  }

  async reverseLookup(ip) {
    try {
      const hostnames = await dns.reverse(ip);
      return {
        success: true,
        ip: ip,
        hostnames: hostnames,
        method: 'reverse-dns'
      };
    } catch (error) {
      return {
        success: false,
        error: error.message,
        ip: ip,
        method: 'reverse-dns'
      };
    }
  }

  async resolveMXRecords(domain) {
    try {
      const mxRecords = await dns.resolveMx(domain);
      return {
        success: true,
        domain: domain,
        mxRecords: mxRecords,
        method: 'mx-records'
      };
    } catch (error) {
      return {
        success: false,
        error: error.message,
        domain: domain,
        method: 'mx-records'
      };
    }
  }

  async resolveTXTRecords(domain) {
    try {
      const txtRecords = await dns.resolveTxt(domain);
      return {
        success: true,
        domain: domain,
        txtRecords: txtRecords,
        method: 'txt-records'
      };
    } catch (error) {
      return {
        success: false,
        error: error.message,
        domain: domain,
        method: 'txt-records'
      };
    }
  }

  async resolveNSRecords(domain) {
    try {
      const nsRecords = await dns.resolveNs(domain);
      return {
        success: true,
        domain: domain,
        nsRecords: nsRecords,
        method: 'ns-records'
      };
    } catch (error) {
      return {
        success: false,
        error: error.message,
        domain: domain,
        method: 'ns-records'
      };
    }
  }

  async lookupAll(hostname) {
    try {
      const result = await dns.lookup(hostname);
      return {
        success: true,
        hostname: hostname,
        address: result.address,
        family: result.family,
        method: 'dns-lookup'
      };
    } catch (error) {
      return {
        success: false,
        error: error.message,
        hostname: hostname,
        method: 'dns-lookup'
      };
    }
  }
}

module.exports = DNSIPExtractor;
