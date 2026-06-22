/* ── Public Camera Intelligence – Frontend ── */
"use strict";

const state = {
  cameras: [], page: 1, perPage: 48, total: 0,
  refreshTimer: null, viewMode: "grid", modalCam: null,
};

const MAX_PER_PAGE = 1000;

const $ = id => document.getElementById(id);
const searchBtn    = $("search-btn");
const keywordInput = $("keyword-input");
const searchStatus = $("search-status");
const searchMeta   = $("search-meta");
const dbQ          = $("db-q");
const dbState      = $("db-state");
const dbFeed       = $("db-feed");
const dbLimit      = $("db-limit");
const dbLimitCustom = $("db-limit-custom");
const filterBtn    = $("filter-btn");
const dbTotal      = $("db-total");
const showOffline  = $("show-offline");
const autoRefresh  = $("auto-refresh");
const viewMode     = $("view-mode");
const cameraGrid   = $("camera-grid");
const camTableBody = $("cam-table-body");
const pagination   = $("pagination");
const gridSection  = $("grid-section");
const tableSection = $("table-section");
const modal        = $("modal");
const modalImg     = $("modal-img");
const modalMeta    = $("modal-meta");
const modalLink    = $("modal-link");

searchBtn.addEventListener("click", triggerSearch);
keywordInput.addEventListener("keydown", e => { if (e.key === "Enter") triggerSearch(); });

async function triggerSearch() {
  const keyword = keywordInput.value.trim();
  searchBtn.disabled = true;
  searchStatus.textContent = "Searching + validating image links (20-60s first run)...";
  searchMeta.textContent = "";
  try {
    const resp = await fetch("/api/search", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ keyword }),
    });
    const data = await resp.json();
    if (!data.ok) throw new Error(data.error || "Search failed");
    searchStatus.textContent = `Found ${data.total} cameras (${data.filtered} match keyword). Image links validated.`;
    searchMeta.textContent = `Sources: ${(data.sources || []).join(", ")} | Elapsed: ${data.elapsed_s}s`;
    dbQ.value = keyword;
    state.page = 1;
    await loadFromDB();
    loadStats();
    loadHistory();
  } catch (err) {
    searchStatus.textContent = `Error: ${err.message}`;
  } finally {
    searchBtn.disabled = false;
  }
}

filterBtn.addEventListener("click", () => { state.page = 1; loadFromDB(); });
dbQ.addEventListener("keydown", e => {
  if (e.key === "Enter") {
    state.page = 1;
    loadFromDB();
  }
});
showOffline.addEventListener("change", () => { state.page = 1; loadFromDB(); });
if (dbLimit) {
  dbLimit.addEventListener("change", () => {
    syncPerPageSelection();
    state.page = 1;
    loadFromDB();
  });
}
if (dbLimitCustom) {
  dbLimitCustom.addEventListener("change", () => {
    if (dbLimit) dbLimit.value = "custom";
    syncPerPageSelection();
    state.page = 1;
    loadFromDB();
  });
}

function syncPerPageSelection() {
  const fromCustom = dbLimit && dbLimit.value === "custom" && dbLimitCustom
    ? dbLimitCustom.value
    : (dbLimit ? dbLimit.value : String(state.perPage));
  state.perPage = Math.min(MAX_PER_PAGE, Math.max(10, parseInt(fromCustom || String(state.perPage), 10)));
  if (dbLimitCustom) dbLimitCustom.disabled = !(dbLimit && dbLimit.value === "custom");
  if (dbLimitCustom && dbLimit && dbLimit.value !== "custom") {
    dbLimitCustom.value = String(state.perPage);
  }
}

async function loadFromDB() {
  syncPerPageSelection();
  const params = new URLSearchParams({
    q: dbQ.value.trim(), page: state.page, per_page: state.perPage,
    feed_type: dbFeed.value, state: dbState.value.trim(),
    include_offline: showOffline.checked ? "1" : "0",
  });
  try {
    const resp = await fetch(`/api/cameras?${params}`);
    const data = await resp.json();
    state.cameras = data.cameras || [];
    state.total   = data.total   || 0;
    state.page    = data.page    || 1;
    dbTotal.textContent = `${state.total} cameras in DB`;
    renderView();
    renderPagination();
  } catch (err) {
    cameraGrid.innerHTML = `<div class="empty-state">Error: ${err.message}</div>`;
  }
}

syncPerPageSelection();

viewMode.addEventListener("change", () => {
  state.viewMode = viewMode.value;
  gridSection.style.display  = state.viewMode === "grid"  ? "block" : "none";
  tableSection.style.display = state.viewMode === "table" ? "block" : "none";
  renderView();
});

function renderView() {
  state.viewMode === "table" ? renderTable(state.cameras) : renderGrid(state.cameras);
}

function statusBadge(status) {
  if (status === "online")  return '<span class="tag" style="background:rgba(10,157,121,0.18);border-color:rgba(10,157,121,0.4);color:#0fd9a8">&#9679; online</span>';
  if (status === "offline") return '<span class="tag" style="background:rgba(220,50,50,0.15);border-color:rgba(220,50,50,0.35);color:#f07070">&#10005; offline</span>';
  return '<span class="tag" style="color:var(--muted)">? unverified</span>';
}

function directFeedLink(cam) {
  return cam.image_url || cam.stream_url || cam.url || "#";
}

function proxiedMediaLink(cam) {
  if (!cam || !cam.id) return directFeedLink(cam);
  return `/api/camera/${cam.id}/media`;
}

function renderGrid(cameras) {
  if (!cameras.length) {
    cameraGrid.innerHTML = '<div class="empty-state">No cameras found. Run a search or adjust filters.</div>';
    return;
  }
  cameraGrid.innerHTML = cameras.map((cam, i) => `
    <div class="cam-card">
      <div class="cam-thumb-wrap" onclick="openModal(${i})">
        ${cam.image_url
          ? `<img id="thumb-${i}" src="${cacheBust(proxiedMediaLink(cam))}" alt="${esc(cam.title)}" onerror="handleImgError(this)" loading="lazy" />`
          : `<div class="no-img-placeholder">&#128247;</div>`}
        <div class="thumb-overlay"><div class="play-icon">&#128269;</div></div>
      </div>
      <div class="cam-body">
        <div class="cam-title" title="${esc(cam.title)}">${esc(cam.title)}</div>
        <div class="cam-meta">&#128205; ${esc(cam.location || cam.city || cam.state || "Unknown")}</div>
        <div class="cam-meta">&#127991; ${esc(cam.site_name || cam.source || "")}${cam.state ? " &middot; " + cam.state : ""}</div>
        <div class="cam-tags">${statusBadge(cam.status)}${renderTags(cam.tags)}</div>
        <div class="cam-actions">
          <button class="btn-secondary" onclick="refreshThumb(${i})">&#8634; Refresh</button>
          <a href="${esc(proxiedMediaLink(cam))}" target="_blank" rel="noopener"><button class="btn-secondary">&#8599; Open</button></a>
          ${cam.image_url ? `<a href="${esc(proxiedMediaLink(cam))}" target="_blank" rel="noopener"><button class="btn-secondary">&#128247; Image</button></a>` : ""}
        </div>
      </div>
    </div>`).join("");
}

function renderTags(t) {
  if (!t) return "";
  return t.split(",").slice(0,4).map(x => `<span class="tag">${esc(x.trim())}</span>`).join("");
}

function cacheBust(url) {
  if (!url) return "";
  return url + (url.includes("?") ? "&" : "?") + "_t=" + Date.now();
}

function handleImgError(img) {
  img.style.display = "none";
  const w = img.closest(".cam-thumb-wrap");
  if (w && !w.querySelector(".no-img-placeholder"))
    w.insertAdjacentHTML("beforeend", '<div class="no-img-placeholder">&#128247;</div>');
}

function refreshThumb(idx) {
  const img = document.getElementById(`thumb-${idx}`);
  const cam = state.cameras[idx];
  if (img && cam && cam.image_url) { img.style.display = "block"; img.src = cacheBust(proxiedMediaLink(cam)); }
}

function renderTable(cameras) {
  if (!cameras.length) { camTableBody.innerHTML = '<tr><td colspan="9" class="empty-state">No cameras found.</td></tr>'; return; }
  camTableBody.innerHTML = cameras.map((cam, i) => `
    <tr>
      <td>${cam.image_url ? `<img class="table-thumb" src="${cacheBust(proxiedMediaLink(cam))}" onerror="handleImgError(this)" onclick="openModal(${i})" loading="lazy" />` : "&#128247;"}</td>
      <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap"><a href="${esc(proxiedMediaLink(cam))}" target="_blank" rel="noopener">${esc(cam.title)}</a></td>
      <td>${esc(cam.location||cam.city||"")}</td>
      <td>${esc(cam.state||cam.country||"")}</td>
      <td>${statusBadge(cam.status)} <span class="tag">${esc(cam.feed_type||"image")}</span></td>
      <td><a href="https://${esc(cam.source||"")}" target="_blank" rel="noopener">${esc(cam.source||cam.site_name||"")}</a></td>
      <td style="max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:.75rem;color:var(--muted)">${esc((cam.tags||"").split(",").slice(0,4).join(", "))}</td>
      <td style="font-size:.78rem">${esc((cam.discovered_at||"").substring(0,10))}</td>
      <td><div style="display:flex;gap:4px">
        <button class="btn-secondary" onclick="refreshThumb(${i})" style="font-size:.72rem;padding:3px 8px">&#8634;</button>
        <a href="${esc(proxiedMediaLink(cam))}" target="_blank" rel="noopener"><button class="btn-secondary" style="font-size:.72rem;padding:3px 8px">&#128247;</button></a>
        <button class="btn-secondary" onclick="openModal(${i})" style="font-size:.72rem;padding:3px 8px">&#128269;</button>
      </div></td>
    </tr>`).join("");
}

function renderPagination() {
  const tp = Math.ceil(state.total / state.perPage);
  if (tp <= 1) { pagination.innerHTML = ""; return; }
  pagination.innerHTML = `
    <button ${state.page<=1?"disabled":""} onclick="goPage(${state.page-1})">&#8592; Prev</button>
    <span class="page-info">Page ${state.page} of ${tp} (${state.total} cameras)</span>
    <button ${state.page>=tp?"disabled":""} onclick="goPage(${state.page+1})">Next &#8594;</button>`;
}

function goPage(p) { state.page = p; loadFromDB(); window.scrollTo({top:0,behavior:"smooth"}); }

function openModal(idx) {
  const cam = state.cameras[idx];
  if (!cam) return;
  state.modalCam = cam;
  if (cam.image_url) { modalImg.style.display = "block"; modalImg.src = cacheBust(proxiedMediaLink(cam)); }
  else modalImg.style.display = "none";
  modalMeta.innerHTML = `
    <strong style="color:var(--text)">${esc(cam.title)}</strong> ${statusBadge(cam.status)}<br>
    &#128205; ${esc(cam.location||"")}${cam.state?" &middot; "+cam.state:""}${cam.country?" &middot; "+cam.country:""}<br>
    &#127991; ${esc(cam.site_name||"")} &middot; ${esc(cam.source||"")}<br>
    ${esc(cam.description||"")}<br>
    ${cam.latitude?`&#127758; ${cam.latitude.toFixed(4)}, ${cam.longitude.toFixed(4)}`:""}
    ${cam.discovered_at?"&nbsp;&middot; Discovered: "+cam.discovered_at.substring(0,10):""}
    ${cam.last_checked?"&nbsp;&middot; Checked: "+cam.last_checked.substring(0,10):""}`;
  modalLink.href = proxiedMediaLink(cam);
  modal.style.display = "flex";
}

function closeModal() { modal.style.display = "none"; state.modalCam = null; }
function refreshModal() { if (state.modalCam?.image_url) modalImg.src = cacheBust(proxiedMediaLink(state.modalCam)); }
document.addEventListener("keydown", e => { if (e.key === "Escape") closeModal(); });

autoRefresh.addEventListener("change", () => {
  if (autoRefresh.checked) {
    state.refreshTimer = setInterval(() => {
      if (state.viewMode === "grid") state.cameras.forEach((cam,i) => { if (cam.image_url) refreshThumb(i); });
    }, 30000);
  } else { clearInterval(state.refreshTimer); state.refreshTimer = null; }
});

async function loadStats() {
  try {
    const data = await fetch("/api/stats").then(r => r.json());
    $("stat-total").textContent   = data.total  || 0;
    $("stat-online").textContent  = data.online || 0;
    $("stat-sources").textContent = (data.by_source||[]).length;
    $("stat-by-source").innerHTML = (data.by_source||[]).slice(0,12).map(s=>`<li>${esc(s.source||"?")} <span>${s.count}</span></li>`).join("");
    $("stat-by-state").innerHTML  = (data.by_state||[]).slice(0,12).map(s=>`<li>${esc(s.state||"?")} <span>${s.count}</span></li>`).join("");
  } catch(_){}
}

async function loadHistory() {
  try {
    const rows = await fetch("/api/searches").then(r => r.json());
    if (!rows.length) return;
    $("history-body").innerHTML = rows.map(r=>`
      <tr>
        <td>${esc(r.query||"")}</td><td>${r.result_count||0}</td>
        <td style="font-size:.78rem;color:var(--muted)">${esc(r.sources_used||"")}</td>
        <td style="font-size:.78rem">${(r.created_at||"").substring(0,16)}</td>
      </tr>`).join("");
  } catch(_){}
}

function esc(s) {
  return String(s||"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;");
}

(async () => { await loadFromDB(); await loadStats(); await loadHistory(); })();
