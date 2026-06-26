/**
 * Report Generator
 * Builds structured text / JSON reports from IPTracebackExtended results
 */

/**
 * Format a single geolocation record into a readable block
 * @param {object} geo
 * @returns {string}
 */
function formatGeoEntry(geo) {
  if (!geo.success) {
    return `  IP: ${geo.ip}\n  Error: ${geo.error}\n`;
  }
  const lines = [
    `  IP:          ${geo.ip}`,
    `  Country:     ${geo.country || 'N/A'} (${geo.countryCode || '?'})`,
    `  Region:      ${geo.region || 'N/A'}`,
    `  City:        ${geo.city || 'N/A'}`,
    `  Coordinates: ${geo.lat != null ? geo.lat : 'N/A'}, ${geo.lon != null ? geo.lon : 'N/A'}`,
    `  Timezone:    ${geo.timezone || 'N/A'}`,
    `  ISP:         ${geo.isp || 'N/A'}`,
    `  Org:         ${geo.org || 'N/A'}`,
    `  ASN:         ${geo.asn || 'N/A'}`,
  ];
  if (geo.isProxy    != null) lines.push(`  Proxy:       ${geo.isProxy ? 'Yes' : 'No'}`);
  if (geo.isHosting  != null) lines.push(`  Hosting:     ${geo.isHosting ? 'Yes' : 'No'}`);
  if (geo.reverseDNS)          lines.push(`  Reverse DNS: ${geo.reverseDNS}`);
  lines.push(`  Provider:    ${geo.provider || 'N/A'}`);
  return lines.join('\n');
}

/**
 * Format a single IP classification entry
 * @param {object} info  - result of ipValidator.classifyIP()
 * @returns {string}
 */
function formatClassificationEntry(info) {
  if (!info.valid) {
    return `  ${info.ip}  [INVALID]`;
  }
  if (info.version === 4) {
    return [
      `  IP:      ${info.ip}`,
      `  Version: IPv4`,
      `  Class:   ${info.class}`,
      `  Private: ${info.isPrivate ? 'Yes' : 'No'}`,
      `  Numeric: ${info.numeric}`,
      `  Hex:     ${info.hex}`,
      `  Binary:  ${info.binary}`,
    ].join('\n');
  }
  return [
    `  IP:      ${info.ip}`,
    `  Version: IPv6`,
    `  Loopback: ${info.isLoopback ? 'Yes' : 'No'}`,
    `  Expanded: ${info.expanded}`,
  ].join('\n');
}

/**
 * Build a full text report from an IPTracebackExtended result object
 * @param {object} result - output from IPTracebackExtended.traceback()
 * @returns {string}
 */
function buildTextReport(result) {
  const sep  = '─'.repeat(60);
  const sep2 = '═'.repeat(60);
  const lines = [];

  lines.push(sep2);
  lines.push('  IPTRACEBACK EXTENDED — REPORT');
  lines.push(`  Generated: ${new Date(result.timestamp || Date.now()).toISOString()}`);
  lines.push(sep2);

  // ── Source IPs ──
  if (result.sourceIPs && result.sourceIPs.length > 0) {
    lines.push('\n[SOURCE IPs DISCOVERED]');
    lines.push(sep);
    result.sourceIPs.forEach(entry => {
      lines.push(`  • ${entry.ip}  (source: ${entry.source})`);
    });
  }

  // ── Decoded IPs ──
  if (result.decodedIPs && result.decodedIPs.length > 0) {
    lines.push('\n[DECODED IPs]');
    lines.push(sep);
    result.decodedIPs.forEach(entry => {
      lines.push(`  • Input: ${entry.input}`);
      lines.push(`    Method: ${entry.method}`);
      lines.push(`    Result: ${entry.decoded}`);
      if (entry.ips && entry.ips.length > 0) {
        lines.push(`    IPs found: ${entry.ips.join(', ')}`);
      }
    });
  }

  // ── IP Classifications ──
  if (result.classifications && result.classifications.length > 0) {
    lines.push('\n[IP CLASSIFICATIONS]');
    lines.push(sep);
    result.classifications.forEach(info => {
      lines.push(formatClassificationEntry(info));
      lines.push('');
    });
  }

  // ── Geolocation ──
  if (result.geolocations && result.geolocations.length > 0) {
    lines.push('\n[GEOLOCATION]');
    lines.push(sep);
    result.geolocations.forEach(geo => {
      lines.push(formatGeoEntry(geo));
      lines.push('');
    });
  }

  // ── DNS Results ──
  if (result.dnsResults) {
    const dns = result.dnsResults;
    lines.push('\n[DNS RECORDS]');
    lines.push(sep);
    if (dns.hostname) lines.push(`  Hostname: ${dns.hostname}`);
    if (dns.ips      && dns.ips.length > 0)         lines.push(`  A records:   ${dns.ips.join(', ')}`);
    if (dns.mxRecords && dns.mxRecords.length > 0) {
      lines.push(`  MX records:`);
      dns.mxRecords.forEach(mx => lines.push(`    priority=${mx.priority}  exchange=${mx.exchange}`));
    }
    if (dns.txtRecords && dns.txtRecords.length > 0) {
      lines.push(`  TXT records:`);
      dns.txtRecords.forEach(t => lines.push(`    ${Array.isArray(t) ? t.join('') : t}`));
    }
    if (dns.nsRecords && dns.nsRecords.length > 0)  lines.push(`  NS records:  ${dns.nsRecords.join(', ')}`);
    if (dns.reverse   && dns.reverse.length > 0)    lines.push(`  Reverse DNS: ${dns.reverse.join(', ')}`);
  }

  // ── Errors ──
  if (result.errors && result.errors.length > 0) {
    lines.push('\n[ERRORS / WARNINGS]');
    lines.push(sep);
    result.errors.forEach(e => lines.push(`  ⚠  ${e}`));
  }

  lines.push('\n' + sep2 + '\n');
  return lines.join('\n');
}

/**
 * Build a compact JSON report (deep-clones result, adds metadata)
 * @param {object} result
 * @returns {string} Formatted JSON string
 */
function buildJSONReport(result) {
  const report = {
    reportVersion: '1.0',
    generatedAt:   new Date(result.timestamp || Date.now()).toISOString(),
    ...result
  };
  return JSON.stringify(report, null, 2);
}

/**
 * Build a Markdown report
 * @param {object} result
 * @returns {string}
 */
function buildMarkdownReport(result) {
  const lines = [];

  lines.push('# IPTraceback Extended — Report');
  lines.push(`> Generated: ${new Date(result.timestamp || Date.now()).toISOString()}\n`);

  if (result.sourceIPs && result.sourceIPs.length > 0) {
    lines.push('## Source IPs Discovered');
    lines.push('| IP | Source |');
    lines.push('|---|---|');
    result.sourceIPs.forEach(e => lines.push(`| \`${e.ip}\` | ${e.source} |`));
    lines.push('');
  }

  if (result.decodedIPs && result.decodedIPs.length > 0) {
    lines.push('## Decoded IPs');
    result.decodedIPs.forEach(e => {
      lines.push(`- **Input:** \`${e.input}\``);
      lines.push(`  - Method: ${e.method}`);
      lines.push(`  - Result: \`${e.decoded}\``);
      if (e.ips && e.ips.length > 0) lines.push(`  - IPs: ${e.ips.map(ip => `\`${ip}\``).join(', ')}`);
    });
    lines.push('');
  }

  if (result.classifications && result.classifications.length > 0) {
    lines.push('## IP Classifications');
    lines.push('| IP | Version | Class | Private |');
    lines.push('|---|---|---|---|');
    result.classifications.forEach(info => {
      if (!info.valid) {
        lines.push(`| \`${info.ip}\` | — | — | INVALID |`);
      } else if (info.version === 4) {
        lines.push(`| \`${info.ip}\` | IPv4 | ${info.class} | ${info.isPrivate ? 'Yes' : 'No'} |`);
      } else {
        lines.push(`| \`${info.ip}\` | IPv6 | — | — |`);
      }
    });
    lines.push('');
  }

  if (result.geolocations && result.geolocations.length > 0) {
    lines.push('## Geolocation');
    lines.push('| IP | Country | City | ISP | ASN | Proxy |');
    lines.push('|---|---|---|---|---|---|');
    result.geolocations.forEach(geo => {
      if (!geo.success) {
        lines.push(`| \`${geo.ip}\` | — | — | — | — | Error: ${geo.error} |`);
      } else {
        const proxy = geo.isProxy != null ? (geo.isProxy ? 'Yes' : 'No') : '?';
        lines.push(`| \`${geo.ip}\` | ${geo.country || '?'} (${geo.countryCode || '?'}) | ${geo.city || '?'} | ${geo.isp || '?'} | ${geo.asn || '?'} | ${proxy} |`);
      }
    });
    lines.push('');
  }

  if (result.errors && result.errors.length > 0) {
    lines.push('## Errors / Warnings');
    result.errors.forEach(e => lines.push(`- ⚠ ${e}`));
    lines.push('');
  }

  return lines.join('\n');
}

module.exports = {
  buildTextReport,
  buildJSONReport,
  buildMarkdownReport
};
