/**
 * IP Geolocator
 * Retrieves geolocation data for IP addresses using free public APIs.
 * No API key required for ip-api.com (non-commercial, rate-limited to 45 req/min).
 */

const fetch = require('node-fetch');

const GEO_PROVIDERS = {
  ipapi:   (ip) => `http://ip-api.com/json/${ip}?fields=status,message,country,countryCode,region,regionName,city,zip,lat,lon,timezone,isp,org,as,asname,reverse,mobile,proxy,hosting,query`,
  ipwho:   (ip) => `https://ipwho.is/${ip}`,
  freegeo: (ip) => `https://freeipapi.com/api/json/${ip}`
};

class IPGeolocator {
  constructor(options = {}) {
    this.timeout   = options.timeout   || 8000;
    this.provider  = options.provider  || 'ipapi';   // primary provider
    this.fallback  = options.fallback  || true;       // try next provider on failure
  }

  async fetchWithTimeout(url) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeout);
    try {
      const response = await fetch(url, { signal: controller.signal });
      clearTimeout(timer);
      return response;
    } catch (err) {
      clearTimeout(timer);
      throw err;
    }
  }

  /**
   * Normalize raw provider responses into a common shape
   */
  normalize(provider, raw) {
    switch (provider) {
      case 'ipapi':
        if (raw.status !== 'success') {
          throw new Error(raw.message || 'ip-api.com returned failure status');
        }
        return {
          ip:          raw.query,
          country:     raw.country,
          countryCode: raw.countryCode,
          region:      raw.regionName,
          regionCode:  raw.region,
          city:        raw.city,
          zip:         raw.zip,
          lat:         raw.lat,
          lon:         raw.lon,
          timezone:    raw.timezone,
          isp:         raw.isp,
          org:         raw.org,
          asn:         raw.as,
          asnName:     raw.asname,
          reverseDNS:  raw.reverse,
          isMobile:    raw.mobile,
          isProxy:     raw.proxy,
          isHosting:   raw.hosting
        };

      case 'ipwho':
        if (!raw.success) {
          throw new Error(raw.message || 'ipwho.is returned failure status');
        }
        return {
          ip:          raw.ip,
          country:     raw.country,
          countryCode: raw.country_code,
          region:      raw.region,
          regionCode:  raw.region_code,
          city:        raw.city,
          zip:         raw.postal,
          lat:         raw.latitude,
          lon:         raw.longitude,
          timezone:    raw.timezone && raw.timezone.id,
          isp:         raw.connection && raw.connection.isp,
          org:         raw.connection && raw.connection.org,
          asn:         raw.connection && `AS${raw.connection.asn}`,
          asnName:     null,
          reverseDNS:  null,
          isMobile:    null,
          isProxy:     null,
          isHosting:   null
        };

      case 'freegeo':
        return {
          ip:          raw.ipAddress,
          country:     raw.countryName,
          countryCode: raw.countryCode,
          region:      raw.regionName,
          regionCode:  raw.regionCode,
          city:        raw.cityName,
          zip:         raw.zipCode,
          lat:         raw.latitude,
          lon:         raw.longitude,
          timezone:    raw.timeZone,
          isp:         null,
          org:         null,
          asn:         null,
          asnName:     null,
          reverseDNS:  null,
          isMobile:    null,
          isProxy:     null,
          isHosting:   null
        };

      default:
        return raw;
    }
  }

  /**
   * Look up geolocation for a single IP using the configured provider.
   * Falls back to alternative providers on error if fallback=true.
   * @param {string} ip
   * @returns {Promise<object>}
   */
  async lookup(ip) {
    const providers = [this.provider, ...Object.keys(GEO_PROVIDERS).filter(p => p !== this.provider)];

    for (const provider of providers) {
      try {
        const url      = GEO_PROVIDERS[provider](ip);
        const response = await this.fetchWithTimeout(url);
        const raw      = await response.json();
        const geo      = this.normalize(provider, raw);
        return {
          success:  true,
          provider,
          ...geo
        };
      } catch (err) {
        if (!this.fallback) {
          return {
            success:  false,
            provider,
            ip,
            error:    err.message
          };
        }
        // Try next provider
      }
    }

    return {
      success:  false,
      ip,
      error:    'All geolocation providers failed'
    };
  }

  /**
   * Batch geolocation for multiple IPs (sequential, respects rate limits)
   * @param {string[]} ips
   * @param {number} delayMs  Delay between requests (default 1350ms for ip-api free tier)
   * @returns {Promise<object[]>}
   */
  async lookupBatch(ips, delayMs = 1350) {
    const results = [];
    for (let i = 0; i < ips.length; i++) {
      const result = await this.lookup(ips[i]);
      results.push(result);
      if (i < ips.length - 1 && delayMs > 0) {
        await new Promise(resolve => setTimeout(resolve, delayMs));
      }
    }
    return results;
  }

  /**
   * Convenience: look up only public IPs from an array, skip private ones
   * @param {string[]} ips
   * @returns {Promise<object[]>}
   */
  async lookupPublicOnly(ips) {
    const { isPrivateIP, isValidIPv4 } = require('./ipValidator');
    const publicIPs = ips.filter(ip => isValidIPv4(ip) && !isPrivateIP(ip));
    return this.lookupBatch(publicIPs);
  }
}

module.exports = IPGeolocator;
