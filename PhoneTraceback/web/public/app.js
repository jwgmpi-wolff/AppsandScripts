const form = document.querySelector("#lookup-form");
const input = document.querySelector("#phone-number");
const button = document.querySelector("#lookup-button");
const status = document.querySelector("#status");
const result = document.querySelector("#result");
const sourceList = document.querySelector("#sources");
const liveSearchList = document.querySelector("#live-searches");

function setLoading(loading) {
  button.disabled = loading;
  button.querySelector("span").textContent = loading ? "Inspecting..." : "Inspect";
}

function renderSources(sources) {
  sourceList.replaceChildren(
    ...sources.map((source) => {
      const item = document.createElement("li");
      const link = document.createElement("a");
      const time = document.createElement("span");
      link.href = source.url;
      link.target = "_blank";
      link.rel = "noreferrer";
      link.textContent = source.title;
      time.className = "source-time";
      time.textContent = `Retrieved ${new Date(source.retrievedAtUtc).toLocaleString()}`;
      item.append(link, time);
      return item;
    }),
  );
}

function renderLiveSearches(searches) {
  liveSearchList.replaceChildren(
    ...searches.map((search) => {
      const item = document.createElement("li");
      const link = document.createElement("a");
      link.href = search.url;
      link.target = "_blank";
      link.rel = "noreferrer";
      link.textContent = search.title;
      item.append(link);
      return item;
    }),
  );
}

function renderResult(data) {
  document.querySelector("#result-title").textContent = data.region;
  document.querySelector("#country-code").textContent = data.countryCode ?? "N/A";
  document.querySelector("#formatted-number").textContent = data.formattedInternational;
  document.querySelector("#calling-code").textContent = data.callingCode;
  const areaCodeFact = document.querySelector("#area-code-fact");
  areaCodeFact.hidden = !data.numberingAssignment.areaCode;
  document.querySelector("#area-code").textContent = data.numberingAssignment.areaCode ?? "";
  document.querySelector("#number-type").textContent = data.numberType.replaceAll("_", " ");
  document.querySelector("#disclaimer").textContent = data.disclaimer;
  renderLiveSearches(data.investigation.publicSearches);
  renderSources(data.sources);
  result.hidden = false;
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  status.textContent = "";
  result.hidden = true;
  setLoading(true);

  try {
    const response = await fetch(`/api/lookup?number=${encodeURIComponent(input.value)}`, {
      headers: { accept: "application/json" },
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error ?? "The lookup could not be completed.");
    renderResult(data);
  } catch (error) {
    status.textContent = error.message;
  } finally {
    setLoading(false);
  }
});