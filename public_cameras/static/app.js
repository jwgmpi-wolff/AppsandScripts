/* ── Public Camera Intelligence – Frontend ── */
"use strict";

const state = {
  cameras: [], page: 1, perPage: 48, total: 0,
  refreshTimer: null, modalRefreshTimer: null, viewMode: "grid", modalCam: null,
  hlsPlayer: null, mjpegIntervals: [], modalMediaType: null, mediaFilter: "all",
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
const modalVideo   = $("modal-video");
const modalMjpeg   = $("modal-mjpeg");
const modalMeta    = $("modal-meta");
const modalLink    = $("modal-link");

searchBtn.addEventListener("click", triggerSearch);
keywordInput.addEventListener("keydown", e => { if (e.key === "Enter") triggerSearch(); });

// Load popular searches on page load
async function loadPopularSearches() {
  try {
    const resp = await fetch("/api/searches");
    const searches = await resp.json();
    const suggestions = $('search-suggestions');
    
    // Extract unique non-empty keywords, limit to 8
    const keywords = new Set();
    for (const s of searches) {
      const q = (s.query || "").trim().toLowerCase();
      if (q && q !== "(all)") {
        keywords.add(q);
        if (keywords.size >= 8) break;
      }
    }
    
    // Add buttons for each keyword
    keywords.forEach(kw => {
      const btn = document.createElement("button");
      btn.className = "suggestion-label";
      btn.textContent = kw;
      btn.addEventListener("click", (e) => {
        e.preventDefault();
        keywordInput.value = kw;
        triggerSearch();
      });
      suggestions.appendChild(btn);
    });
  } catch (e) {
    console.debug("Failed to load popular searches", e);
  }
}

// Load on page ready
if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", () => {
    setupMediaFilterButtons();
    loadPopularSearches();
    loadFromDB();
  });
} else {
  setupMediaFilterButtons();
  loadPopularSearches();
  loadFromDB();
}

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

// Global delegated handler for stat filter buttons (source/state click-to-filter)
document.addEventListener("click", (e) => {
  const btn = e.target.closest(".stat-filter-btn");
  if (!btn) return;
  const type  = btn.dataset.type;
  const value = btn.dataset.value;
  if (type === "source") {
    dbQ.value = "";
    dbState.value = "";
    state.page = 1;
    loadFromDB({ source: value, include_offline: "1" });
  } else if (type === "state") {
    dbQ.value = "";
    dbState.value = value;
    state.page = 1;
    loadFromDB({ include_offline: "1" });
  }
  document.getElementById("grid-section").scrollIntoView({ behavior: "smooth" });
});

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

async function loadFromDB(overrides = {}) {
  syncPerPageSelection();
  const baseParams = {
    q: dbQ.value.trim(),
    page: state.page,
    per_page: state.perPage,
    feed_type: dbFeed.value,
    state: dbState.value.trim(),
    include_offline: showOffline.checked ? "1" : "0",
  };
  Object.assign(baseParams, overrides);
  const params = new URLSearchParams(baseParams);
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

// Setup media filter buttons
function setupMediaFilterButtons() {
  const mediaFilterContainer = document.getElementById("media-filter-container");
  if (!mediaFilterContainer) return;
  const buttons = mediaFilterContainer.querySelectorAll(".media-filter-btn");
  buttons.forEach(btn => {
    btn.addEventListener("click", (e) => {
      e.preventDefault();
      state.mediaFilter = btn.dataset.filter;
      buttons.forEach(b => b.classList.toggle("active", b === btn));
      state.page = 1;
      renderView();
      renderPagination();
    });
  });
}

syncPerPageSelection();

viewMode.addEventListener("change", () => {
  state.viewMode = viewMode.value;
  gridSection.style.display  = state.viewMode === "grid"  ? "block" : "none";
  tableSection.style.display = state.viewMode === "table" ? "block" : "none";
  renderView();
});

function applyMediaFilter(cameras) {
  if (state.mediaFilter === "all") return cameras;
  return cameras.filter(cam => {
    const streamInfo = getStreamInfo(cam);
    if (state.mediaFilter === "images") {
      return !streamInfo.isLive;
    } else if (state.mediaFilter === "streams") {
      return streamInfo.isLive;
    }
    return true;
  });
}

function renderView() {
  const filtered = applyMediaFilter(state.cameras);
  state.viewMode === "table" ? renderTable(filtered) : renderGrid(filtered);
}

function statusPill(status) {
  if (status === 'online')  return '<span class="status-pill status-pill--online">&#9679; LIVE</span>';
  if (status === 'offline') return '<span class="status-pill status-pill--offline">&#10005; OFFLINE</span>';
  return '<span class="status-pill status-pill--unknown">? UNVERIFIED</span>';
}

function statusBadge(status) {
  if (status === "online")  return '<span class="tag" style="background:rgba(10,157,121,0.18);border-color:rgba(10,157,121,0.4);color:#0fd9a8">&#9679; online</span>';
  if (status === "offline") return '<span class="tag" style="background:rgba(220,50,50,0.15);border-color:rgba(220,50,50,0.35);color:#f07070">&#10005; offline</span>';
  return '<span class="tag" style="color:var(--muted)">? unverified</span>';
}

function directFeedLink(cam) {
  return cam.image_url || cam.stream_url || cam.url || "#";
}

// Detect feed type and characteristics
function getStreamInfo(cam) {
  const feedType = (cam.feed_type || "image").toLowerCase();
  const source = (cam.source || "").toLowerCase();
  const url = (cam.image_url || cam.url || "").toLowerCase();
  const hasStream = cam.stream_url ? true : false;
  
  // Live stream types
  if (feedType === "mjpeg") {
    return { type: "mjpeg", label: "🟢 Live Stream (MJPEG)", isLive: true, refreshMs: 1000, hasStream };
  }
  if (feedType === "hls") {
    return { type: "hls", label: "🟢 Live Stream (HLS)", isLive: true, refreshMs: 2000, hasStream };
  }
  if (feedType === "mp4") {
    return { type: "mp4", label: "🟢 Video Stream (MP4)", isLive: true, refreshMs: 2000, hasStream };
  }
  if (feedType === "mp2t") {
    return { type: "mp2t", label: "🟢 Video Stream (MPEG-TS)", isLive: true, refreshMs: 2000, hasStream };
  }
  if (feedType === "webm") {
    return { type: "webm", label: "🟢 Video Stream (WebM)", isLive: true, refreshMs: 2000, hasStream };
  }
  if (feedType === "embed") {
    return { type: "embed", label: "🎬 Embedded Content", isLive: false, refreshMs: 0, hasStream };
  }
  
  // Static image with refresh detection
  let refreshSecs = 5; // default
  
  // Infer refresh rate from source
  if (source.includes("dot") || source.includes("traffic") || source.includes("caltrans") || 
      source.includes("nyc") || source.includes("chicago") || source.includes("houston") || 
      source.includes("511") || source.includes("transtar")) {
    refreshSecs = 10; // Traffic cameras typically refresh every 5-10 seconds, use 10 to be conservative
  } else if (source.includes("alertwildfire") || source.includes("usgs")) {
    refreshSecs = 30; // Wildfire/USGS cams typically 30 second refresh
  } else if (source.includes("faa") || source.includes("aviation") || source.includes("airport")) {
    refreshSecs = 15; // Aviation typically 15 seconds
  } else if (source.includes("noaa") || source.includes("weather") || source.includes("buoy")) {
    refreshSecs = 60; // Weather/buoy cams often slower
  }
  
  return {
    type: "image",
    label: `📷 Static Image (Refreshes every ${refreshSecs}s)`,
    isLive: false,
    refreshMs: refreshSecs * 1000,
    refreshSecs: refreshSecs,
    hasStream
  };
}

// Build stream badge HTML
function streamBadge(cam) {
  const info = getStreamInfo(cam);
  const badgeColor = info.isLive ? "rgba(10, 157, 121, 0.7)" : "rgba(46, 118, 179, 0.6)";
  return `<span class="stream-badge" style="display:inline-block;padding:3px 8px;background:${badgeColor};border-radius:4px;font-size:.75rem;margin-right:4px">${info.label}</span>`;
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
  cameraGrid.innerHTML = cameras.map((cam, i) => {
    const streamInfo = getStreamInfo(cam);
    const streamTypeIndicator = streamInfo.isLive ? "🟢" : "📷";
    return `
    <div class="cam-card${cam.status === 'offline' ? ' cam-card--offline' : cam.status === 'online' ? ' cam-card--online' : ''}" data-feedtype="${esc(streamInfo.type)}" data-islive="${streamInfo.isLive}">  
      <div class="cam-thumb-wrap" onclick="openModal(${i})">
        ${cam.image_url
          ? `<img id="thumb-${i}" src="${cacheBust(proxiedMediaLink(cam))}" alt="${esc(cam.title)}" onerror="handleImgError(this)" loading="lazy" />`
          : `<div class="no-img-placeholder">&#128247;</div>`}
        <div class="thumb-overlay"><div class="play-icon">&#128269;</div></div>
        ${statusPill(cam.status)}
        <span class="stream-type-indicator" style="position:absolute;bottom:6px;left:6px;font-size:1rem">${streamTypeIndicator}</span>
      </div>
      <div class="cam-body">
        <div class="cam-title" title="${esc(cam.title)}">${esc(cam.title)}</div>
        <div class="cam-meta">&#128205; ${esc(cam.location || cam.city || cam.state || "Unknown")}</div>
        <div class="cam-meta">&#127991; ${esc(cam.site_name || cam.source || "")}${cam.state ? " &middot; " + cam.state : ""}</div>
        <div class="cam-tags">${statusBadge(cam.status)}<span class="tag" style="background:${streamInfo.isLive ? 'rgba(10,157,121,0.5)' : 'rgba(46,118,179,0.5)'};padding:2px 6px;font-size:.7rem">${streamInfo.type.toUpperCase()}</span>${renderTags(cam.tags)}</div>
        <div class="cam-actions">
          <button class="btn-secondary" onclick="refreshThumb(${i})">&#8634; Refresh</button>
          <a href="${esc(proxiedMediaLink(cam))}" target="_blank" rel="noopener"><button class="btn-secondary">&#8599; ${streamInfo.isLive ? 'Watch' : 'View'}</button></a>
          ${cam.image_url ? `<a href="${esc(proxiedMediaLink(cam))}" target="_blank" rel="noopener"><button class="btn-secondary">&#128247; Image</button></a>` : ""}
        </div>
      </div>
    </div>`;
  }).join("");
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
  camTableBody.innerHTML = cameras.map((cam, i) => {
    const streamInfo = getStreamInfo(cam);
    return `
    <tr>
      <td>${cam.image_url ? `<img class="table-thumb" src="${cacheBust(proxiedMediaLink(cam))}" onerror="handleImgError(this)" onclick="openModal(${i})" loading="lazy" />` : "&#128247;"}</td>
      <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap"><a href="${esc(proxiedMediaLink(cam))}" target="_blank" rel="noopener">${esc(cam.title)}</a></td>
      <td>${esc(cam.location||cam.city||"")}</td>
      <td>${esc(cam.state||cam.country||"")}</td>
      <td>${statusBadge(cam.status)} <span class="tag" style="background:${streamInfo.isLive ? 'rgba(10,157,121,0.5)' : 'rgba(46,118,179,0.5)'}">${streamInfo.type.toUpperCase()}</span></td>
      <td><a href="https://${esc(cam.source||"")}" target="_blank" rel="noopener">${esc(cam.source||cam.site_name||"")}</a></td>
      <td style="max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:.75rem;color:var(--muted)">${streamInfo.refreshSecs ? (streamInfo.isLive ? '⚡ Live' : `🔄 ${streamInfo.refreshSecs}s`) : ''}</td>
      <td style="font-size:.78rem">${esc((cam.discovered_at||"").substring(0,10))}</td>
      <td><div style="display:flex;gap:4px">
        <button class="btn-secondary" onclick="refreshThumb(${i})" style="font-size:.72rem;padding:3px 8px">&#8634;</button>
        <a href="${esc(proxiedMediaLink(cam))}" target="_blank" rel="noopener"><button class="btn-secondary" style="font-size:.72rem;padding:3px 8px">${streamInfo.isLive ? '🎥' : '&#128247;'}</button></a>
        <button class="btn-secondary" onclick="openModal(${i})" style="font-size:.72rem;padding:3px 8px">&#128269;</button>
      </div></td>
    </tr>`;
  }).join("");
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

// Stream playback functions
function stopAllStreams() {
  // Stop HLS player
  if (state.hlsPlayer) {
    state.hlsPlayer.destroy();
    state.hlsPlayer = null;
  }
  
  // Stop video playback
  if (modalVideo.src) {
    modalVideo.pause();
    modalVideo.src = "";
  }
  
  // Stop YouTube iframe
  const ytIframe = document.getElementById("modal-youtube");
  if (ytIframe) {
    ytIframe.src = "";
    ytIframe.style.display = "none";
  }
  
  // Stop MJPEG refresh intervals
  state.mjpegIntervals.forEach(id => clearInterval(id));
  state.mjpegIntervals = [];
  
  // Hide all media elements
  modalImg.style.display = "none";
  modalVideo.style.display = "none";
  modalMjpeg.style.display = "none";
}

function playYouTubeEmbed(videoId) {
  stopAllStreams();
  const ytIframe = document.getElementById("modal-youtube");
  if (ytIframe) {
    ytIframe.src = `https://www.youtube.com/embed/${videoId}?autoplay=1&mute=0&rel=0`;
    ytIframe.style.display = "block";
  }
}

function extractYouTubeId(cam) {
  // Try from url field (watch?v=ID)
  if (cam.url && cam.url.includes("youtube.com/watch?v=")) {
    return cam.url.split("?v=")[1].split("&")[0];
  }
  // Try from image_url (thumbnail: i.ytimg.com/vi/ID/...)
  if (cam.image_url && cam.image_url.includes("i.ytimg.com/vi/")) {
    return cam.image_url.split("/vi/")[1].split("/")[0];
  }
  // Try from stream_url (/id/ID.28/ pattern)
  if (cam.stream_url && cam.stream_url.includes("/id/")) {
    const part = cam.stream_url.split("/id/")[1];
    return part.split(".")[0];
  }
  return null;
}

function playHlsStream(url) {
  stopAllStreams();
  
  if (!window.Hls) {
    console.error("HLS.js not loaded");
    return;
  }
  
  modalVideo.style.display = "block";
  
  if (Hls.isSupported()) {
    state.hlsPlayer = new Hls({
      xhrSetup: (xhr) => { xhr.withCredentials = false; }
    });
    state.hlsPlayer.loadSource(url);
    state.hlsPlayer.attachMedia(modalVideo);
    state.hlsPlayer.on(Hls.Events.MANIFEST_PARSED, () => {
      modalVideo.play().catch(e => console.debug("Autoplay blocked:", e));
    });
    state.hlsPlayer.on(Hls.Events.ERROR, (event, data) => {
      console.error("HLS error:", data.type, data.details, data.fatal, url.substring(0, 80));
      if (data.fatal) {
        switch(data.type) {
          case Hls.ErrorTypes.NETWORK_ERROR:
            console.warn("Fatal network error, trying to recover...");
            state.hlsPlayer.startLoad();
            break;
          case Hls.ErrorTypes.MEDIA_ERROR:
            console.warn("Fatal media error, trying to recover...");
            state.hlsPlayer.recoverMediaError();
            break;
          default:
            console.error("Unrecoverable HLS error");
            state.hlsPlayer.destroy();
            break;
        }
      }
    });
  } else if (modalVideo.canPlayType('application/vnd.apple.mpegurl')) {
    // Safari native HLS support
    modalVideo.src = url;
    modalVideo.play().catch(e => console.debug("Autoplay blocked:", e));
  } else {
    console.error("HLS not supported in this browser");
  }
}

function playMp4Stream(url) {
  stopAllStreams();
  modalVideo.style.display = "block";
  modalVideo.src = url;
  modalVideo.play().catch(e => console.debug("Autoplay blocked:", e));
}

function playMjpegStream(url) {
  stopAllStreams();
  modalMjpeg.style.display = "block";
  modalMjpeg.src = url;
  
  // Auto-refresh MJPEG every 2 seconds to force updates
  const refreshId = setInterval(() => {
    if (modalMjpeg.style.display !== "none" && state.modalCam?.stream_url === url) {
      modalMjpeg.src = cacheBust(url);
    } else {
      clearInterval(refreshId);
      state.mjpegIntervals = state.mjpegIntervals.filter(id => id !== refreshId);
    }
  }, 2000);
  
  state.mjpegIntervals.push(refreshId);
}

function playStaticImage(url) {
  stopAllStreams();
  modalImg.style.display = "block";
  modalImg.src = cacheBust(url);
}

function openModal(idx) {
  const cam = state.cameras[idx];
  if (!cam) return;
  
  // Clear any existing modal refresh timer
  if (state.modalRefreshTimer) {
    clearInterval(state.modalRefreshTimer);
    state.modalRefreshTimer = null;
  }
  
  state.modalCam = cam;
  
  // Initialize button state - will update after stream load
  const openBtn = document.getElementById("modal-open-btn");
  const refreshBtn = document.getElementById("modal-refresh-toggle");
  
  // For YouTube sources, use embedded player (YouTube HLS has CORS restrictions)
  if (cam.source === "youtube") {
    const videoId = extractYouTubeId(cam);
    if (videoId) {
      playYouTubeEmbed(videoId);
      openBtn.textContent = "🎥 Watch on YouTube";
      refreshBtn.style.display = "none";
      modalLink.href = `https://www.youtube.com/watch?v=${videoId}`;
    } else {
      playStaticImage(cam.image_url ? proxiedMediaLink(cam) : "");
      openBtn.textContent = "📷 View Image";
      refreshBtn.style.display = "block";
      modalLink.href = cam.url || "#";
    }
    state.modalMediaType = "youtube";
  } else {
    // Detect and play appropriate media type
    const feedType = (cam.feed_type || "image").toLowerCase();
    
    // Play stream if available
    if (cam.stream_url && feedType !== "image") {
      if (feedType === "hls") {
        // HLS streams: play directly without proxying
        playHlsStream(cam.stream_url);
      } else if (feedType === "mjpeg") {
        // MJPEG streams: proxy through our endpoint
        playMjpegStream(proxiedMediaLink(cam));
      } else if (feedType === "mp4") {
        // MP4 streams: play directly
        playMp4Stream(cam.stream_url);
      } else {
        // Fallback to image if unknown stream type
        playStaticImage(cam.image_url ? proxiedMediaLink(cam) : "");
      }
      state.modalMediaType = feedType;
      
      // Update button labels for stream
      openBtn.textContent = "🎥 Watch Live";
      refreshBtn.style.display = "none";
      modalLink.href = cam.stream_url;
    } else if (cam.image_url) {
      // Play static image
      playStaticImage(proxiedMediaLink(cam));
      state.modalMediaType = "image";
      
      // Update button labels for image
      openBtn.textContent = "📷 View Image";
      refreshBtn.style.display = "block";
      refreshBtn.textContent = "↺ Refresh";
      modalLink.href = proxiedMediaLink(cam);
    }
  }
  
  const streamInfo = getStreamInfo(cam);
  const streamBadgeHtml = `<div style="margin-bottom:8px">${streamBadge(cam)}</div>`;
  
  let refreshInfo = "";
  if (streamInfo.refreshSecs) {
    const refreshLabel = streamInfo.isLive ? "⚡ Live Feed" : "🔄 Auto-Refreshes";
    refreshInfo = `<br><span style="font-size:.8rem;color:var(--muted)">${refreshLabel}: ${streamInfo.refreshSecs}s interval</span>`;
  }
  
  modalMeta.innerHTML = streamBadgeHtml + `
    <strong style="color:var(--text)">${esc(cam.title)}</strong> ${statusBadge(cam.status)}<br>
    &#128205; ${esc(cam.location||"")}${cam.state?" &middot; "+cam.state:""}${cam.country?" &middot; "+cam.country:""}<br>
    &#127991; ${esc(cam.site_name||"")} &middot; ${esc(cam.source||"")}<br>
    ${esc(cam.description||"")}<br>
    ${cam.latitude?`&#127758; ${cam.latitude.toFixed(4)}, ${cam.longitude.toFixed(4)}`:""}
    ${cam.discovered_at?"&nbsp;&middot; Discovered: "+cam.discovered_at.substring(0,10):""}
    ${cam.last_checked?"&nbsp;&middot; Checked: "+cam.last_checked.substring(0,10):""}
    ${refreshInfo}`;
  
  modal.style.display = "flex";
}


function closeModal() { 
  stopAllStreams();
  modal.style.display = "none"; 
  if (state.modalRefreshTimer) {
    clearInterval(state.modalRefreshTimer);
    state.modalRefreshTimer = null;
  }
  state.modalCam = null;
  state.modalMediaType = null;
}

function refreshModal() { 
  if (state.modalCam?.image_url) {
    modalImg.src = cacheBust(proxiedMediaLink(state.modalCam)); 
  }
}

function toggleModalAutoRefresh() {
  if (state.modalRefreshTimer) {
    clearInterval(state.modalRefreshTimer);
    state.modalRefreshTimer = null;
    document.getElementById("modal-refresh-toggle").textContent = "↺ Refresh";
  } else if (state.modalCam) {
    const streamInfo = getStreamInfo(state.modalCam);
    if (!streamInfo.isLive && streamInfo.refreshSecs) {
      state.modalRefreshTimer = setInterval(() => {
        refreshModal();
      }, streamInfo.refreshSecs * 1000);
      document.getElementById("modal-refresh-toggle").textContent = `⏸ Stop (${streamInfo.refreshSecs}s)`;
    }
  }
}

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
    $("stat-by-source").innerHTML = (data.by_source||[]).slice(0,12).map(s=>
      `<li><button class="stat-filter-btn" data-type="source" data-value="${esc(s.source||"")}">${esc(s.source||"?")} <span>${s.count}</span></button></li>`
    ).join("");
    $("stat-by-state").innerHTML  = (data.by_state||[]).slice(0,12).map(s=>
      `<li><button class="stat-filter-btn" data-type="state" data-value="${esc(s.state||"")}">${esc(s.state||"?")} <span>${s.count}</span></button></li>`
    ).join("");
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
