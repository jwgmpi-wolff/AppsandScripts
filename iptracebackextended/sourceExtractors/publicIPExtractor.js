/**
 * IP Extractor from Common Services
 * Queries public IP lookup services and extracts IP information
 */

const fetch = require('node-fetch');

class PublicIPExtractor {
  constructor() {
    this.services = {
      'ipify': 'https://api.ipify.org?format=json',
      'icanhazip': 'http://icanhazip.com',
      'ident': 'https://ident.me',
      'myip': 'https://myip.wtf/json',
      'jsonip': 'https://jsonip.com'
    };
    this.timeout = 5000;
  }

  async fetchWithTimeout(url, timeout = this.timeout) {
    const controller = new AbortController();
    const id = setTimeout(() => controller.abort(), timeout);
    try {
      const response = await fetch(url, { signal: controller.signal });
      clearTimeout(id);
      return response;
    } catch (error) {
      clearTimeout(id);
      throw error;
    }
  }

  async extractFromIPify() {
    try {
      const response = await this.fetchWithTimeout(this.services.ipify);
      const data = await response.json();
      return {
        success: true,
        ip: data.ip,
        source: 'ipify'
      };
    } catch (error) {
      return {
        success: false,
        error: error.message,
        source: 'ipify'
      };
    }
  }

  async extractFromIcanhazIP() {
    try {
      const response = await this.fetchWithTimeout(this.services.icanhazip);
      const ip = await response.text();
      return {
        success: true,
        ip: ip.trim(),
        source: 'icanhazip'
      };
    } catch (error) {
      return {
        success: false,
        error: error.message,
        source: 'icanhazip'
      };
    }
  }

  async extractFromIdent() {
    try {
      const response = await this.fetchWithTimeout(this.services.ident);
      const ip = await response.text();
      return {
        success: true,
        ip: ip.trim(),
        source: 'ident'
      };
    } catch (error) {
      return {
        success: false,
        error: error.message,
        source: 'ident'
      };
    }
  }

  async extractFromMyIP() {
    try {
      const response = await this.fetchWithTimeout(this.services.myip);
      const data = await response.json();
      return {
        success: true,
        ip: data.YourFuckingIPAddress || data.ip,
        source: 'myip'
      };
    } catch (error) {
      return {
        success: false,
        error: error.message,
        source: 'myip'
      };
    }
  }

  async extractAll() {
    const results = [];
    const extractors = [
      this.extractFromIPify(),
      this.extractFromIcanhazIP(),
      this.extractFromIdent(),
      this.extractFromMyIP()
    ];

    const outcomes = await Promise.allSettled(extractors);
    for (const outcome of outcomes) {
      if (outcome.status === 'fulfilled') {
        results.push(outcome.value);
      }
    }

    return results;
  }
}

module.exports = PublicIPExtractor;
