/**
 * HTML/DOM IP Extractor
 * Scrapes web pages for IP addresses and hidden data
 */

const fetch = require('node-fetch');
const cheerio = require('cheerio');

class HTMLIPExtractor {
  constructor() {
    this.timeout = 10000;
    this.ipRegex = /\b(?:\d{1,3}\.){3}\d{1,3}\b/g;
  }

  async fetchWithTimeout(url, timeout = this.timeout) {
    const controller = new AbortController();
    const id = setTimeout(() => controller.abort(), timeout);
    try {
      const response = await fetch(url, {
        signal: controller.signal,
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
        }
      });
      clearTimeout(id);
      return response;
    } catch (error) {
      clearTimeout(id);
      throw error;
    }
  }

  validateIP(ip) {
    const parts = ip.split('.');
    if (parts.length !== 4) return false;
    return parts.every(part => {
      const num = parseInt(part, 10);
      return !isNaN(num) && num >= 0 && num <= 255;
    });
  }

  async extractFromHTML(url) {
    try {
      const response = await this.fetchWithTimeout(url);
      const html = await response.text();
      const $ = cheerio.load(html);

      const ips = new Set();
      const data = {
        text: $.text(),
        allText: html
      };

      // Extract from visible text
      const matches = data.text.match(this.ipRegex) || [];
      matches.forEach(ip => {
        if (this.validateIP(ip)) {
          ips.add(ip);
        }
      });

      // Extract from data attributes
      $('[data-ip], [data-address], [data-host]').each((i, elem) => {
        const val = $(elem).attr('data-ip') || $(elem).attr('data-address') || $(elem).attr('data-host');
        if (val && this.validateIP(val)) {
          ips.add(val);
        }
      });

      // Extract from comments and scripts
      const comments = $.html().match(/<!--[\s\S]*?-->/g) || [];
      comments.forEach(comment => {
        const commentMatches = comment.match(this.ipRegex) || [];
        commentMatches.forEach(ip => {
          if (this.validateIP(ip)) {
            ips.add(ip);
          }
        });
      });

      return {
        success: true,
        ips: Array.from(ips),
        source: url,
        method: 'html-scraping'
      };
    } catch (error) {
      return {
        success: false,
        error: error.message,
        source: url,
        method: 'html-scraping'
      };
    }
  }

  async extractFromJSON(url) {
    try {
      const response = await this.fetchWithTimeout(url);
      const data = await response.json();
      const jsonString = JSON.stringify(data);

      const ips = new Set();
      const matches = jsonString.match(this.ipRegex) || [];
      matches.forEach(ip => {
        if (this.validateIP(ip)) {
          ips.add(ip);
        }
      });

      return {
        success: true,
        ips: Array.from(ips),
        source: url,
        method: 'json-extraction',
        rawData: data
      };
    } catch (error) {
      return {
        success: false,
        error: error.message,
        source: url,
        method: 'json-extraction'
      };
    }
  }
}

module.exports = HTMLIPExtractor;
