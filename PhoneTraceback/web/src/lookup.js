import { parsePhoneNumberFromString } from "libphonenumber-js/max";
import countries from "world-countries";

const DISCLAIMER =
  "Numbering-plan assignment only. Not a live caller, device, or network location. Caller ID may be spoofed.";
const METADATA_SOURCE = "https://github.com/catamphetamine/libphonenumber-js";
const COUNTRY_SOURCE = "https://github.com/mledoze/countries";
const NANPA_SOURCE = "https://www.nanpa.com/reports/reports-npa";
const COUNTRY_BY_CODE = new Map(countries.map((country) => [country.cca2, country]));

function createInvestigation(phone) {
  const exactNumber = `"${phone.number}"`;
  const encodedQuery = encodeURIComponent(`${exactNumber} spam scam`);

  return {
    authenticity: {
      status: "unverified",
      summary: "Number validity and numbering assignment do not verify the identity or network origin of the caller.",
      indicators: [
        "Caller ID can be spoofed even when the displayed number is valid.",
        "Only the originating and terminating providers can perform an authoritative call traceback.",
        "STIR/SHAKEN attestation, when exposed by a provider, is stronger evidence than caller ID but is not proof of identity.",
      ],
    },
    publicSearches: [
      { title: "Live Google abuse-report search", url: `https://www.google.com/search?q=${encodedQuery}` },
      { title: "Live Bing abuse-report search", url: `https://www.bing.com/search?q=${encodedQuery}` },
      { title: "Current NANPA area-code reports", url: NANPA_SOURCE },
      { title: "File an FCC complaint", url: "https://consumercomplaints.fcc.gov/hc/en-us" },
      { title: "Report fraud to the FTC", url: "https://reportfraud.ftc.gov/" },
    ],
  };
}

export class LookupError extends Error {
  constructor(message, status = 400) {
    super(message);
    this.name = "LookupError";
    this.status = status;
  }
}

export function createLookupService({ now = () => new Date() } = {}) {
  return async function lookup(rawNumber) {
    const input = typeof rawNumber === "string" ? rawNumber.trim() : "";
    if (!input || input.length > 64) {
      throw new LookupError("Enter a phone number in international format, such as +1 202 555 0123.");
    }

    const phone = parsePhoneNumberFromString(input);
    if (!phone?.isValid()) {
      throw new LookupError("The phone number is not valid. Include the country calling code.");
    }

    const retrievedAtUtc = now().toISOString();
    const country = COUNTRY_BY_CODE.get(phone.country) ?? null;
    const areaCode = phone.countryCallingCode === "1" ? phone.nationalNumber.slice(0, 3) : null;
    const investigation = createInvestigation(phone);

    return {
      input: phone.number,
      formattedInternational: phone.formatInternational(),
      callingCode: `+${phone.countryCallingCode}`,
      countryCode: phone.country ?? null,
      region: country?.name?.common ?? phone.country ?? "Unknown",
      numberType: phone.getType() ?? "UNKNOWN",
      numberingAssignment: {
        area: country?.name?.common ?? phone.country ?? "Non-geographic numbering plan",
        areaCode,
        basis: areaCode
          ? "NANP area code and country calling-code metadata"
          : phone.country ? "country calling-code metadata" : "non-geographic calling-code metadata",
        tracksCurrentLocation: false,
      },
      disclaimer: DISCLAIMER,
      investigation,
      sources: [
        { title: "libphonenumber-js metadata", url: METADATA_SOURCE, retrievedAtUtc },
        ...(country
          ? [{ title: "Countries dataset", url: COUNTRY_SOURCE, retrievedAtUtc }]
          : []),
      ],
    };
  };
}