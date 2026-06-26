/**
 * IPTracebackExtended — Test Suite
 * Run with: node test.js
 */

'use strict';

const IPTracebackExtended = require('./index');
const {
  isValidIP,
  isValidIPv4,
  isValidIPv6,
  isPrivateIP,
  isPublicIP,
  classifyIP,
  extractIPsFromText
} = require('./utils');

// ─── Minimal test harness ────────────────────────────────────────────────────
let passed = 0;
let failed = 0;

function assert(label, condition, detail = '') {
  if (condition) {
    console.log(`  ✓  ${label}`);
    passed++;
  } else {
    console.error(`  ✗  ${label}${detail ? '  →  ' + detail : ''}`);
    failed++;
  }
}

function section(name) {
  console.log(`\n${'─'.repeat(55)}`);
  console.log(`  ${name}`);
  console.log('─'.repeat(55));
}

// ─── ipValidator tests ───────────────────────────────────────────────────────
section('ipValidator — isValidIPv4');
assert('valid public IPv4',        isValidIPv4('8.8.8.8'));
assert('valid private IPv4',       isValidIPv4('192.168.1.1'));
assert('rejects IPv6 string',      !isValidIPv4('::1'));
assert('rejects out-of-range',     !isValidIPv4('256.0.0.1'));
assert('rejects partial',          !isValidIPv4('192.168'));

section('ipValidator — isValidIPv6');
assert('full IPv6',                isValidIPv6('2001:0db8:85a3:0000:0000:8a2e:0370:7334'));
assert('compressed IPv6',          isValidIPv6('::1'));
assert('rejects plain string',     !isValidIPv6('notanip'));

section('ipValidator — isPrivateIP / isPublicIP');
assert('10.0.0.1 is private',      isPrivateIP('10.0.0.1'));
assert('192.168.0.1 is private',   isPrivateIP('192.168.0.1'));
assert('172.16.5.5 is private',    isPrivateIP('172.16.5.5'));
assert('127.0.0.1 is private',     isPrivateIP('127.0.0.1'));
assert('8.8.8.8 is public',        isPublicIP('8.8.8.8'));
assert('1.1.1.1 is public',        isPublicIP('1.1.1.1'));
assert('non-IP not public',        !isPublicIP('not-an-ip'));

section('ipValidator — classifyIP');
const c1 = classifyIP('8.8.8.8');
assert('8.8.8.8 valid',            c1.valid);
assert('8.8.8.8 is IPv4',          c1.version === 4);
assert('8.8.8.8 class A',          c1.class === 'A', c1.class);
assert('8.8.8.8 not private',      !c1.isPrivate);
assert('8.8.8.8 has numeric',      typeof c1.numeric === 'number');
assert('8.8.8.8 has hex',          typeof c1.hex === 'string');
assert('8.8.8.8 has binary',       typeof c1.binary === 'string');

const c2 = classifyIP('::1');
assert('::1 valid',                c2.valid);
assert('::1 is IPv6',              c2.version === 6);
assert('::1 is loopback',          c2.isLoopback);

const c3 = classifyIP('not-an-ip');
assert('invalid IP flagged',       !c3.valid);

section('ipValidator — extractIPsFromText');
const txt = 'Server at 10.0.0.1 and public 8.8.8.8 also ::1 here';
const extracted = extractIPsFromText(txt);
assert('finds 10.0.0.1',           extracted.ipv4.includes('10.0.0.1'));
assert('finds 8.8.8.8',            extracted.ipv4.includes('8.8.8.8'));
assert('finds ::1 (IPv6)',         extracted.ipv6.includes('::1'));

// ─── Decoder tests ───────────────────────────────────────────────────────────
const ipt = new IPTracebackExtended();

section('Decoders — Base64');
const b64 = Buffer.from('192.168.1.100').toString('base64');
const b64Result = ipt.decode(b64, 'base64');
assert('Base64 decode succeeds',   b64Result.success);
assert('Base64 extracts IP',       b64Result.results[0].ips.includes('192.168.1.100'),
  JSON.stringify(b64Result.results[0]));

section('Decoders — Hex');
const hexStr = Buffer.from('10.0.0.5').toString('hex');
const hexResult = ipt.decode(hexStr, 'hex');
assert('Hex decode succeeds',      hexResult.success);
assert('Hex extracts IP',          hexResult.results[0].ips.includes('10.0.0.5'),
  JSON.stringify(hexResult.results[0]));

section('Decoders — URL Encoding');
const urlEnc = '192%2E168%2E2%2E1';
const urlResult = ipt.decode(urlEnc, 'url');
assert('URL decode succeeds',      urlResult.success);
assert('URL extracts IP',          urlResult.results[0].ips.includes('192.168.2.1'),
  JSON.stringify(urlResult.results[0]));

section('Decoders — Binary');
const binaryStr = '00110001'  // '1'
                + '00110000'  // '0'
                + '00101110'  // '.'
                + '00110000'  // '0'
                + '00101110'  // '.'
                + '00110000'  // '0'
                + '00101110'  // '.'
                + '00110001'; // '1'
const binResult = ipt.decode(binaryStr, 'binary');
assert('Binary decode succeeds',   binResult.success, JSON.stringify(binResult));
assert('Binary extracts IP',       binResult.results[0].ips.includes('10.0.0.1')
                                || binResult.results[0].decoded === '10.0.0.1',
  JSON.stringify(binResult.results[0]));

section('Decoders — ROT13');
const rot13Input = rotateStr('8.8.8.8 is the dns', 13);
const rotResult  = ipt.decode(rot13Input, 'rot13');
assert('ROT13 decode succeeds',    rotResult.success, JSON.stringify(rotResult));
assert('ROT13 extracts IP',        rotResult.results[0].ips.includes('8.8.8.8'),
  JSON.stringify(rotResult.results[0]));

section('Decoders — Auto (tryAll)');
const autoResult = ipt.decodeAll(b64);
assert('Auto mode returns array',  Array.isArray(autoResult));
assert('Auto finds at least one',  autoResult.length > 0);

// ─── Classification pipeline ─────────────────────────────────────────────────
section('classifyAll');
const classified = ipt.classifyAll(['8.8.8.8', '10.0.0.1', '::1', 'bad']);
assert('classifyAll returns 4',    classified.length === 4);
assert('8.8.8.8 public',           classified[0].isPublic);
assert('10.0.0.1 private',         classified[1].isPrivate);
assert("'bad' invalid",            !classified[3].valid);

// ─── Report generation ───────────────────────────────────────────────────────
section('Report generation');
const mockResult = {
  timestamp:       Date.now(),
  sourceIPs:       [{ ip: '8.8.8.8', source: 'ipify' }],
  decodedIPs:      [{ input: b64, method: 'base64', decoded: '192.168.1.100', ips: ['192.168.1.100'] }],
  classifications: ipt.classifyAll(['8.8.8.8', '192.168.1.100']),
  geolocations:    [],
  dnsResults:      null,
  allIPs:          ['8.8.8.8', '192.168.1.100'],
  errors:          []
};

const textReport = ipt.generateReport(mockResult, 'text');
assert('Text report is non-empty', textReport.length > 50);
assert('Text report has header',   textReport.includes('IPTRACEBACK EXTENDED'));

const jsonReport = ipt.generateReport(mockResult, 'json');
assert('JSON report is valid JSON', (() => { try { JSON.parse(jsonReport); return true; } catch { return false; } })());

const mdReport   = ipt.generateReport(mockResult, 'markdown');
assert('Markdown report has #',    mdReport.startsWith('# IPTraceback'));

// ─── Summary ─────────────────────────────────────────────────────────────────
console.log(`\n${'═'.repeat(55)}`);
console.log(`  Results: ${passed} passed, ${failed} failed`);
console.log('═'.repeat(55) + '\n');
process.exit(failed > 0 ? 1 : 0);

// ─── Helpers ─────────────────────────────────────────────────────────────────
function rotateStr(str, n) {
  return str.split('').map(ch => {
    const code = ch.charCodeAt(0);
    if (code >= 65 && code <= 90) return String.fromCharCode(((code - 65 + n) % 26) + 65);
    if (code >= 97 && code <= 122) return String.fromCharCode(((code - 97 + n) % 26) + 97);
    return ch;
  }).join('');
}
