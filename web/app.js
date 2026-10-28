// 网页每 5 秒向 Java 后端要一次数据（后端自己每 10 秒更新一次）
const REFRESH_MS = 5000;
const NM_TO_M = 1852;

const KIND_LABELS = {
  "emergency": "紧急",
  "go-around": "复飞",
  "holding": "盘旋等待",
  "rare": "稀有机型",
};

// 机头朝上的飞机图标，按航向旋转
const PLANE_SVG = '<svg viewBox="0 0 24 24"><path d="M12 1.5c.8 0 1.4.9 1.4 2.2v5.6l8.1 4.6v2.2l-8.1-2.3v4.6l2.3 1.7v1.7L12 21l-3.7.8v-1.7l2.3-1.7v-4.6l-8.1 2.3v-2.2l8.1-4.6V3.7c0-1.3.6-2.2 1.4-2.2z"/></svg>';

const state = {
  aircraft: new Map(),   // hex -> 飞机数据
  markers: new Map(),    // hex -> 地图标记
  alertKinds: new Map(), // hex -> 最近一次提醒的类型（用来给飞机上色）
  selected: null,        // 当前选中的飞机 hex
  trackLine: null,
  lastUpdate: 0,
};

const map = L.map("map", { zoomControl: true, attributionControl: true });
// OpenStreetMap 免费底图，在 style.css 里用滤镜调成暗色
L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
  maxZoom: 18,
  attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
}).addTo(map);

// ---------- 格式化 ----------

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

// 美国过渡高度 18,000 ft：以上用 FL，以下用英尺
function fmtAlt(a) {
  if (a.ground || a.alt == null) return "地面";
  if (a.alt >= 18000) return "FL" + String(Math.round(a.alt / 100)).padStart(3, "0");
  return a.alt.toLocaleString("en-US") + " ft";
}

function fmtRate(r) {
  if (r == null) return "—";
  if (Math.abs(r) < 100) return "平飞";
  return (r > 0 ? "↑ " : "↓ ") + Math.abs(r).toLocaleString("en-US");
}

function fmtTime(ms) {
  return new Date(ms).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false });
}

// 没有呼号的飞机（直升机、军机常见）显示注册号
function nameOf(a) {
  return a.callsign && a.callsign !== "(none)" ? a.callsign : a.reg ?? a.hex;
}

function isEmergencySquawk(sq) {
  return sq === "7500" || sq === "7600" || sq === "7700";
}

// ---------- 地图标记 ----------

function iconFor(a) {
  const kind = state.alertKinds.get(a.hex);
  const classes = ["ac"];
  if (a.ground) classes.push("ground");
  if (kind) classes.push("kind-" + kind);
  if (a.hex === state.selected) classes.push("selected");
  const rot = a.track ?? 0;
  return L.divIcon({
    className: "",
    iconSize: [24, 24],
    iconAnchor: [12, 12],
    html: `<div class="${classes.join(" ")}"><div style="transform: rotate(${rot}deg)">${PLANE_SVG}</div><span class="lbl">${esc(nameOf(a))}</span></div>`,
  });
}

function updateMarkers() {
  for (const [hex, a] of state.aircraft) {
    let m = state.markers.get(hex);
    if (!m) {
      m = L.marker([a.lat, a.lon], { icon: iconFor(a) }).addTo(map);
      m.on("click", () => select(hex));
      state.markers.set(hex, m);
    } else {
      m.setLatLng([a.lat, a.lon]);
      m.setIcon(iconFor(a));
    }
    m.setZIndexOffset(state.alertKinds.has(hex) || hex === state.selected ? 1000 : a.ground ? -1000 : 0);
  }
  // 飞出范围的飞机从地图上删掉
  for (const [hex, m] of state.markers) {
    if (!state.aircraft.has(hex)) {
      map.removeLayer(m);
      state.markers.delete(hex);
    }
  }
}

// ---------- 选中一架飞机 ----------

async function select(hex, pan = false) {
  state.selected = hex;
  renderDetail();
  renderList();
  updateMarkers();
  await drawTrack();
  const a = state.aircraft.get(hex);
  if (pan && a) map.flyTo([a.lat, a.lon], Math.max(map.getZoom(), 11), { duration: 0.8 });
}

function deselect() {
  state.selected = null;
  if (state.trackLine) map.removeLayer(state.trackLine);
  state.trackLine = null;
  renderDetail();
  renderList();
  updateMarkers();
}

async function drawTrack() {
  if (!state.selected) return;
  const points = await fetch("/api/track?hex=" + encodeURIComponent(state.selected)).then(r => r.json());
  if (state.trackLine) map.removeLayer(state.trackLine);
  state.trackLine = L.polyline(points.map(p => [p[0], p[1]]), {
    color: "#5eb0ff", weight: 2.5, opacity: 0.85,
  }).addTo(map);
}

function renderDetail() {
  const el = document.getElementById("detail");
  const a = state.aircraft.get(state.selected);
  if (!a) { el.hidden = true; return; }
  el.hidden = false;
  const squawkClass = isEmergencySquawk(a.squawk) ? "squawk-alert" : "";
  el.innerHTML = `
    <button class="close" title="关闭" aria-label="关闭">×</button>
    <div class="callsign">${esc(nameOf(a))}</div>
    <div class="ident">${esc(a.type ?? "未知机型")} · ${esc(a.reg ?? "—")} · ${esc(a.hex)}</div>
    <dl>
      <div><dt>高度</dt><dd>${fmtAlt(a)}</dd></div>
      <div><dt>地速</dt><dd>${a.gs == null ? "—" : Math.round(a.gs) + " kt"}</dd></div>
      <div><dt>垂直速度</dt><dd>${fmtRate(a.rate)}</dd></div>
      <div><dt>航迹</dt><dd>${a.track == null ? "—" : String(Math.round(a.track)).padStart(3, "0") + "°"}</dd></div>
      <div><dt>Squawk</dt><dd class="${squawkClass}">${esc(a.squawk ?? "—")}</dd></div>
      <div><dt>距离</dt><dd>${a.dist} nm</dd></div>
    </dl>`;
  el.querySelector(".close").onclick = deselect;
}

// ---------- 左侧列表 ----------

function renderFeed(alerts) {
  const el = document.getElementById("feed");
  document.getElementById("feed-count").textContent = alerts.length ? alerts.length : "";
  if (!alerts.length) {
    el.innerHTML = '<li class="empty">暂时没有异常。出现紧急代码、复飞、盘旋等待或稀有机型时，会显示在这里。</li>';
    return;
  }
  el.innerHTML = alerts.map(f => `
    <li class="kind-${esc(f.kind)}" data-hex="${esc(f.hex)}">
      <div class="meta"><span class="label-${esc(f.kind)}">${esc(KIND_LABELS[f.kind] ?? f.kind)}</span><time>${fmtTime(f.time)}</time></div>
      <div class="msg">${esc(f.message)}</div>
    </li>`).join("");
  el.querySelectorAll("li[data-hex]").forEach(li => {
    li.onclick = () => {
      if (state.aircraft.has(li.dataset.hex)) select(li.dataset.hex, true);
    };
  });
}

function renderList() {
  const planes = [...state.aircraft.values()];
  document.getElementById("count").textContent = planes.length;
  const el = document.getElementById("list");
  el.innerHTML = planes.slice(0, 30).map(a => `
    <li data-hex="${esc(a.hex)}" class="${a.hex === state.selected ? "selected" : ""}">
      <span class="cs">${esc(nameOf(a))}</span>
      <span class="ty">${esc(a.type ?? "—")}</span>
      <span class="al">${fmtAlt(a)}</span>
      <span class="di">${a.dist}nm</span>
    </li>`).join("");
  el.querySelectorAll("li").forEach(li => { li.onclick = () => select(li.dataset.hex, true); });
}

function renderStatus(ok) {
  const box = document.getElementById("status");
  const text = document.getElementById("status-text");
  box.classList.toggle("offline", !ok);
  if (!ok) { text.textContent = "连接断开"; return; }
  const secs = Math.max(0, Math.round((Date.now() - state.lastUpdate) / 1000));
  text.textContent = state.lastUpdate ? `实时 · ${secs} 秒前` : "等待数据…";
}

// ---------- 主循环 ----------

async function refresh() {
  try {
    const [aircraft, alerts, config] = await Promise.all([
      fetch("/api/aircraft").then(r => r.json()),
      fetch("/api/alerts").then(r => r.json()),
      fetch("/api/config").then(r => r.json()),
    ]);
    state.aircraft = new Map(aircraft.map(a => [a.hex, a]));
    state.lastUpdate = config.lastUpdate;

    // 最近 15 分钟有提醒的飞机在地图上换颜色
    state.alertKinds = new Map();
    const cutoff = Date.now() - 15 * 60 * 1000;
    for (const f of [...alerts].reverse()) {
      if (f.time >= cutoff) state.alertKinds.set(f.hex, f.kind);
    }

    updateMarkers();
    renderFeed(alerts);
    renderList();
    if (state.selected && !state.aircraft.has(state.selected)) deselect();
    else if (state.selected) { renderDetail(); drawTrack(); }
    renderStatus(true);
  } catch (e) {
    console.error(e);
    renderStatus(false);
  }
}

async function init() {
  const config = await fetch("/api/config").then(r => r.json());
  const home = [config.lat, config.lon];
  map.setView(home, 9);

  // 监视范围
  L.circle(home, {
    radius: config.radiusNm * NM_TO_M,
    color: "#5eb0ff", weight: 1, opacity: 0.35, fill: false, dashArray: "4 6",
  }).addTo(map);

  // 你的位置
  L.marker(home, {
    icon: L.divIcon({ className: "", html: '<div class="home"></div>', iconSize: [12, 12], iconAnchor: [6, 6] }),
    interactive: false,
  }).addTo(map);

  // 机场
  for (const apt of config.airports) {
    L.marker([apt.lat, apt.lon], {
      icon: L.divIcon({ className: "", html: `<div class="apt">${esc(apt.code)}</div>`, iconSize: [36, 18], iconAnchor: [18, 9] }),
      interactive: false,
      zIndexOffset: -2000,
    }).addTo(map);
  }

  await refresh();
  setInterval(refresh, REFRESH_MS);
  setInterval(() => renderStatus(true), 1000);
}

init().catch(e => { console.error(e); renderStatus(false); });
