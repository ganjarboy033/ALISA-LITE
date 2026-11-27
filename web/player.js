/* Alisa Lite — tab Pemutar: pemutar audio/video native (mesin Nimiyo) + galeri gabungan + MixTools.
   Dimuat saat tab Pemutar dibuka atau saat service musik masih aktif. Audio berjalan di service native (nx.P),
   video di Activity native (nx.V) — JavaScript hanya remote control, jadi tetap lancar saat aplikasi di latar belakang. */
(() => {
'use strict';
if (window.PL) return;
const NATIVE = !!(N && N.aQueue);
const EN = () => typeof lang !== 'undefined' && lang === 'en';
const L = (i, e) => EN() ? e : i;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const fmt = ms => { const s = Math.max(0, Math.floor((ms || 0) / 1000)), h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = s % 60; return h ? h + ':' + String(m).padStart(2, '0') + ':' + String(x).padStart(2, '0') : m + ':' + String(x).padStart(2, '0'); };
const mb = n => (Number(n || 0) / 1048576).toFixed(1) + ' MB';
const clean = n => String(n || 'Media').replace(/\.[^.]+$/, '');
const presetOf = d => d < .25 ? 1 : d < .5 ? 2 : d < .7 ? 3 : d < .88 ? 4 : 5;     // sama dengan Nimiyo: Small→Large Hall
const mix = () => Object.assign({ speed: 100, pitch: true, rev: false, depth: .5, wet: .5 }, get('nx_mix', {}));
const mixCfg = () => { const m = mix(); return { speed: m.speed / 100, pitch: !!m.pitch && m.speed !== 100, rev: !!m.rev, preset: presetOf(m.depth), wet: m.wet }; };
const cfg = () => Object.assign({ aheadMb: get('nx_buf', 6), focus: get('nx_focus', true), noisy: get('nx_noisy', true) }, mixCfg());

/* ---------- adaptor mesin: native (NX) atau fallback HTML5 untuk uji di browser ---------- */
function fallback() {
  const el = document.createElement('video'); el.playsInline = true; el.preload = 'metadata'; el.style.display = 'none'; document.body.appendChild(el);
  let list = [], i = -1, rep = 0, shuf = false, rate = 1;
  const load = (n, auto) => { if (!list[n]) return; i = n; el.src = list[n].uri; el.playbackRate = rate; if (auto) el.play().catch(() => {}); };
  const nxt = auto => { if (!list.length) return; let n; if (shuf && list.length > 1) { do n = Math.floor(Math.random() * list.length); while (n === i); } else { n = i + 1; if (n >= list.length) { if (rep === 1 || !auto) n = 0; else return; } } load(n, true); };
  el.onended = () => { if (rep === 2) { el.currentTime = 0; el.play(); } else nxt(true); };
  return {
    queue: (l, n, a) => { list = l; load(n, a); }, add: l => { list = list.concat(l); if (i < 0) load(0, true); },
    toggle: () => el.paused ? el.play().catch(() => {}) : el.pause(), next: () => nxt(false),
    prev: () => { if (el.currentTime > 4) el.currentTime = 0; else load((i - 1 + list.length) % list.length, true); },
    seek: ms => { el.currentTime = ms / 1000; }, jump: n => load(n, true),
    state: () => { const t = list[i] || {}; return { on: i >= 0, playing: !el.paused, status: el.paused ? 'paused' : 'playing', idx: i, n: list.length, id: t.id, title: t.title, artist: t.artist, pos: el.currentTime * 1000, dur: (el.duration || 0) * 1000, buf: 100, speed: rate, repeat: rep, shuffle: shuf, sleep: 0, art: 0, err: '', revOk: false }; },
    art: () => '', list: () => list, speed: r => { rate = r; el.playbackRate = r; }, rev: () => {}, mode: (r, s) => { rep = r; shuf = s; },
    sleep: () => {}, stop: () => { el.pause(); el.removeAttribute('src'); list = []; i = -1; }, cfg: () => {}
  };
}
const A = NATIVE ? {
  queue: (l, i, auto, pos) => N.aQueue(JSON.stringify(l), i, auto, pos || 0, JSON.stringify(cfg())),
  add: l => N.aAdd(JSON.stringify(l)), toggle: () => N.aToggle(), next: () => N.aNext(), prev: () => N.aPrev(), seek: ms => N.aSeek(ms), jump: i => N.aJump(i),
  state: () => { try { return JSON.parse(N.aState()); } catch (_) { return { on: false }; } },
  art: () => N.aArt(), list: () => { try { return JSON.parse(N.aQueueList()); } catch (_) { return []; } },
  speed: (r, p) => N.aSpeed(r, p), rev: (o, p, w) => N.aReverb(o, p, w), mode: (r, s) => N.aMode(r, s), sleep: m => N.aSleep(m), stop: () => N.aStop(),
  cfg: () => { const c = cfg(); N.aCfg(c.aheadMb, c.focus, c.noisy); }
} : fallback();

/* ---------- state ---------- */
let S = { on: false }, LIB = { audio: null, video: null, image: null }, kind = 'all', qry = '', sortBy = 'def', shown = 60, cur = [];
let dragging = false, lastArt = -1, lastPlay = null, lastId = null, pollT = null, started = false;
const trk = x => ({ id: String(x.id || x.uri), title: clean(x.name), artist: x.artist && x.artist !== '<unknown>' ? x.artist : '', uri: x.uri, dur: x.duration || 0 });

/* ---------- render pemutar ---------- */
function render(s) {
  S = s || { on: false };
  const on = !!S.on, onPage = page === 'play';
  $('#mini').classList.toggle('hidden', !on || onPage);
  document.body.classList.toggle('hasmini', on && !onPage);
  $('#npCard').classList.toggle('hidden', !on);
  if (!on) return;
  const title = S.title || '—', sub = (S.artist ? S.artist + ' · ' : '') + (S.idx + 1) + ' / ' + S.n;
  if ($('#npT').textContent !== title) { $('#npT').textContent = title; $('#mT').textContent = title; }
  $('#npS').textContent = sub; $('#mS').textContent = sub;
  const st = S.status === 'loading' ? L('Memuat…', 'Loading…') : S.status === 'buffering' ? L('Buffering…', 'Buffering…') : S.status === 'error' ? (S.err || 'Error') : '';
  $('#npSt').textContent = st;
  const dur = S.dur || 0, pos = S.pos || 0;
  if (!dragging) { $('#seek').value = dur ? Math.min(1000, pos / dur * 1000) : 0; $('#tC').textContent = fmt(pos); }
  $('#tD').textContent = fmt(dur);
  $('#mProg').style.width = (dur ? pos / dur * 100 : 0) + '%';
  $('#bufBar').style.width = (S.buf == null ? 100 : S.buf) + '%';
  if (lastPlay !== !!S.playing) { lastPlay = !!S.playing; const h = ic(lastPlay ? 'pause' : 'play'); $('#bPlay').innerHTML = h; $('#mPlay').innerHTML = h; }
  $('#bShuf').classList.toggle('on', !!S.shuffle);
  $('#bRep').classList.toggle('on', S.repeat > 0); $('#bRep').dataset.m = S.repeat === 2 ? '1' : '';
  const m = mix(), tags = []; if (m.speed !== 100) tags.push(m.speed + '%'); if (m.rev) tags.push('Reverb');
  $('#mixSt').textContent = tags.join(' · '); $('#bMix').classList.toggle('on', tags.length > 0);
  const sl = S.sleep; $('#bSleep').classList.toggle('on', !!sl); $('#slSt').textContent = !sl ? '' : sl < 0 ? L('akhir lagu', 'end of track') : fmt(sl * 1000);
  if (S.art !== lastArt) { lastArt = S.art; const b = A.art(), bg = b ? `url(data:image/jpeg;base64,${b})` : ''; $('#npArt').style.backgroundImage = bg; $('#npArt').classList.toggle('has', !!b); $('#npBg').style.backgroundImage = bg; }
  if (S.id !== lastId) { lastId = S.id; $$('#libItems .mrow.cur').forEach(x => x.classList.remove('cur')); const r = $(`#libItems .mrow[data-id="${CSS.escape(String(S.id))}"]`); if (r) r.classList.add('cur'); }
}
function loop() {
  clearTimeout(pollT);
  const go2 = () => { if (!document.hidden) render(A.state()); pollT = setTimeout(go2, S.playing ? 500 : S.on ? 1000 : 2500); };
  go2();
}
document.addEventListener('visibilitychange', () => { if (!document.hidden) render(A.state()); });
const kick = () => { setTimeout(() => render(A.state()), 120); setTimeout(() => render(A.state()), 600); };
const hap = () => { try { if (get('nx_haptic', false) && N && N.haptic) N.haptic(); } catch (_) {} };

/* ---------- kontrol ---------- */
$('#bPlay').onclick = () => { hap(); if (!S.on) { const a = LIB.audio || []; if (a.length) return playAudio(a, 0); return toast(L('Pilih lagu dari galeri dulu', 'Pick a track from the gallery first')); } A.toggle(); kick(); };
$('#mPlay').onclick = () => { hap(); A.toggle(); kick(); };
$('#bNext').onclick = $('#mNext').onclick = () => { A.next(); kick(); };
$('#bPrev').onclick = $('#mPrev').onclick = () => { A.prev(); kick(); };
$('#bShuf').onclick = () => { A.mode(S.repeat || 0, !S.shuffle); S.shuffle = !S.shuffle; render(S); toast(S.shuffle ? L('Acak aktif', 'Shuffle on') : L('Acak mati', 'Shuffle off')); };
$('#bRep').onclick = () => { const r = ((S.repeat || 0) + 1) % 3; A.mode(r, !!S.shuffle); S.repeat = r; render(S); toast([L('Ulang mati', 'Repeat off'), L('Ulangi semua', 'Repeat all'), L('Ulangi satu lagu', 'Repeat one')][r]); };
$('#seek').oninput = e => { dragging = true; $('#tC').textContent = fmt((S.dur || 0) * e.target.value / 1000); };
$('#seek').onchange = e => { A.seek(Math.round((S.dur || 0) * e.target.value / 1000)); dragging = false; kick(); };
$('#mini').onclick = e => { if (!e.target.closest('button')) go('play'); };

/* ---------- galeri gabungan ---------- */
async function refresh(force) {
  if (!NATIVE || !N.mediaList) { view(); return; }
  if (N.requestMediaAccess) N.requestMediaAccess();
  $('#libStatus').classList.add('hidden'); $('#libItems').innerHTML = `<div class="panel muted" style="text-align:center">${L('Memuat media…', 'Loading media…')}</div>`;
  for (const k of ['audio', 'video', 'image']) {
    if (LIB[k] && !force) continue; await sleep(0);
    let rows = []; try { rows = JSON.parse(N.mediaList(k) || '[]'); } catch (_) {}
    rows.forEach(r => { r.k = k; }); LIB[k] = rows;
  }
  shown = 60; view();
}
function filtered() {
  let l = kind === 'all' ? [].concat(LIB.audio || [], LIB.video || [], LIB.image || []) : (LIB[kind] || []);
  if (qry) { const q = qry.toLowerCase(); l = l.filter(x => (x.name || '').toLowerCase().includes(q) || (x.artist || '').toLowerCase().includes(q)); }
  if (sortBy === 'name') l = l.slice().sort((a, b) => String(a.name).localeCompare(String(b.name)));
  else if (sortBy === 'big') l = l.slice().sort((a, b) => (b.size || 0) - (a.size || 0));
  else if (sortBy === 'small') l = l.slice().sort((a, b) => (a.size || 0) - (b.size || 0));
  return l;
}
function view() {
  cur = filtered();
  const box = $('#libItems'), total = (LIB.audio || []).length + (LIB.video || []).length + (LIB.image || []).length;
  $('#libStatus').classList.toggle('hidden', cur.length > 0);
  if (!cur.length) { $('#libStatus b').textContent = total ? L('Tidak ada hasil', 'No results') : L('Belum ada media', 'No media yet'); $('#libStatus small').textContent = total ? L('Coba kata kunci atau filter lain.', 'Try another keyword or filter.') : L('Izinkan akses media, lalu tekan muat ulang — atau buka Folder.', 'Allow media access then refresh — or open Folder.'); }
  box.innerHTML = cur.slice(0, shown).map((x, i) => {
    const th = x.k === 'image' ? `<img loading="lazy" decoding="async" alt="" src="${esc(x.uri)}" onerror="this.remove()">` : '';
    const g = x.k === 'audio' ? '♫' : x.k === 'video' ? '▶' : '▧';
    const meta = [x.k === 'audio' || x.k === 'video' ? (x.duration ? fmt(x.duration) : '') : '', mb(x.size), x.artist && x.artist !== '<unknown>' ? x.artist : ''].filter(Boolean).join(' · ');
    return `<button class="item mrow k-${x.k}${String(x.id || x.uri) === String(S.id) ? ' cur' : ''}" data-i="${i}" data-id="${esc(String(x.id || x.uri))}"><span class="ik th">${th}<em>${g}</em></span><span class="it"><b>${esc(clean(x.name))}</b><small>${esc(meta)}</small></span></button>`;
  }).join('');
  $('#libMore').classList.toggle('hidden', cur.length <= shown);
  $('#libCount').textContent = cur.length ? cur.length + ' ' + L('item', 'items') : '';
}
function playAudio(list, j) {
  const from = Math.max(0, j - 100), slice = list.slice(from, from + 300);   // batasi ukuran antrean (Intent ≤ ~1 MB)
  A.queue(slice.map(trk), j - from, true, 0); kick();
}
function playVideo(x) { if (NATIVE) N.playVideo(x.uri, clean(x.name), -1); else window.open(x.uri, '_blank'); }
$('#libItems').onclick = e => {
  const r = e.target.closest('.mrow'); if (!r) return; const x = cur[+r.dataset.i]; if (!x) return; hap();
  if (x.k === 'audio') { const a = cur.filter(z => z.k === 'audio'); playAudio(a, a.indexOf(x)); }
  else if (x.k === 'video') playVideo(x);
  else viewImage(cur.filter(z => z.k === 'image'), cur.filter(z => z.k === 'image').indexOf(x));
};
$('#libTabs').onclick = e => { const b = e.target.closest('button[data-v]'); if (!b) return; $$('#libTabs button').forEach(x => x.classList.toggle('on', x === b)); kind = b.dataset.v; shown = 60; view(); };
let qT; $('#libQ').oninput = e => { clearTimeout(qT); qT = setTimeout(() => { qry = e.target.value.trim(); shown = 60; view(); }, 160); };
$('#libSort').onchange = e => { sortBy = e.target.value; view(); };
$('#libMore').onclick = () => { shown += 80; view(); };
$('#plRefresh').onclick = () => refresh(true);
$('#plFolder').onclick = () => openFolder();
$('#plStop').onclick = () => { A.stop(); S = { on: false }; render(S); };

/* ---------- penampil gambar ---------- */
let iv = { list: [], i: 0 };
function viewImage(list, i) {
  iv = { list, i: Math.max(0, i) };
  let o = $('#imgv');
  if (!o) {
    o = document.createElement('div'); o.id = 'imgv'; o.className = 'imgv';
    o.innerHTML = '<div class="imgv-h"><button class="ib" id="ivX" aria-label="Close">✕</button><b id="ivT"></b><span class="muted" id="ivN"></span></div><img id="ivI" alt=""><div class="imgv-f"><button class="btn" id="ivP">‹</button><button class="btn" id="ivNx">›</button></div>';
    document.body.appendChild(o);
    const step = d => { iv.i = (iv.i + d + iv.list.length) % iv.list.length; paintImg(); };
    $('#ivX').onclick = closeImage; $('#ivP').onclick = () => step(-1); $('#ivNx').onclick = () => step(1);
    let x0 = null; o.addEventListener('touchstart', e => { x0 = e.touches[0].clientX; }, { passive: true });
    o.addEventListener('touchend', e => { if (x0 == null) return; const dx = e.changedTouches[0].clientX - x0; x0 = null; if (Math.abs(dx) > 60) step(dx < 0 ? 1 : -1); }, { passive: true });
  }
  o.classList.add('on'); paintImg();
}
function paintImg() { const x = iv.list[iv.i]; if (!x) return; $('#ivI').src = x.uri; $('#ivT').textContent = clean(x.name); $('#ivN').textContent = (iv.i + 1) + ' / ' + iv.list.length; }
function closeImage() { const o = $('#imgv'); if (!o || !o.classList.contains('on')) return false; o.classList.remove('on'); $('#ivI').removeAttribute('src'); return true; }

/* ---------- penjelajah folder (SAF) ---------- */
let activeTree = '', folderStack = [];
function openFolder() {
  if (!NATIVE || !N.chooseFolder) return toast(L('Jelajah folder tersedia di aplikasi Android.', 'Folder browsing is available in the Android app.'));
  const saved = N.savedTreeUri ? N.savedTreeUri() : '';
  if (saved) { activeTree = saved; folderStack = [{ id: '', name: L('Folder perangkat', 'Device folder') }]; renderFolder(); } else N.chooseFolder();
}
function folderChosen(uri) { activeTree = uri; folderStack = [{ id: '', name: L('Folder perangkat', 'Device folder') }]; renderFolder(); }
function renderFolder() {
  const c = folderStack[folderStack.length - 1]; let rows = [];
  try { rows = JSON.parse(N.listFolder(activeTree, c.id) || '[]'); } catch (e) { rows = [{ error: L('Gagal membaca folder', 'Could not read folder') }]; }
  const err = rows.find(x => x.error);
  const body = err ? `<p class="muted">${esc(err.error)}</p>` : rows.length ? rows.map((x, i) => `<button class="item folder-entry" data-i="${i}"><span class="ik">${x.dir ? '📁' : /^audio/.test(x.mime || '') ? '♫' : /^video/.test(x.mime || '') ? '▶' : '📄'}</span><span class="it"><b>${esc(x.name || '—')}</b><small>${x.dir ? L('Folder', 'Folder') : mb(x.size) + ' · ' + esc(x.mime || 'File')}</small></span></button>`).join('') : `<p class="muted">${L('Folder kosong', 'Empty folder')}</p>`;
  openModal(`<div class="eyebrow">${L('PENGELOLA FILE', 'FILE MANAGER')}</div><h3>${esc(c.name)}</h3><div class="btnrow"><button class="btn sm" id="fUp" ${folderStack.length < 2 ? 'disabled' : ''}>‹ ${L('Kembali', 'Back')}</button><button class="btn sm" id="fChoose">${L('Pilih folder lain', 'Choose another')}</button><button class="btn sm" id="fForget">${L('Lepas akses', 'Forget')}</button></div><div class="list" id="fRows">${body}</div>`);
  $('#fUp').onclick = () => { if (folderStack.length > 1) { folderStack.pop(); renderFolder(); } };
  $('#fChoose').onclick = () => N.chooseFolder();
  $('#fForget').onclick = () => { N.forgetFolder(); activeTree = ''; folderStack = []; closeModal(); toast(L('Akses folder dilepas.', 'Folder access removed.')); };
  $('#fRows').onclick = e => {
    const b = e.target.closest('.folder-entry'); if (!b) return; const x = rows[+b.dataset.i]; if (!x) return;
    if (x.dir) { folderStack.push({ id: x.id, name: x.name }); renderFolder(); return; }
    if (/^audio/.test(x.mime || '')) {                                    // putar semua audio di folder ini mulai dari yang diketuk
      const a = rows.filter(z => !z.dir && /^audio/.test(z.mime || '')), j = a.indexOf(x);
      A.queue(a.slice(Math.max(0, j - 100), j + 200).map(z => ({ id: z.uri, title: clean(z.name), artist: '', uri: z.uri, dur: 0 })), Math.min(j, 100), true, 0); closeModal(); kick();
    } else if (/^video/.test(x.mime || '')) { closeModal(); N.playVideo(x.uri, clean(x.name), -1); }
    else N.openTreeFile(x.uri, x.mime || '*/*');
  };
}

/* ---------- MixTools ---------- */
function openMix() {
  const m = mix();
  const tag = v => v === 100 ? 'Normal' : v < 100 ? 'Slowed' : 'SpeedUp';
  openModal(`<div class="eyebrow">MIXTOOLS</div><h3>${L('Efek musik', 'Music effects')}</h3>
    <div class="mx-sec"><div class="mx-h"><b>Speed FX</b><span class="pill" id="mxSl">${m.speed}% · ${tag(m.speed)}</span></div>
      <input type="range" id="mxS" min="60" max="140" step="1" value="${m.speed}">
      <div class="mx-g"><span>Slowed</span><span>Normal</span><span>SpeedUp</span></div>
      <div class="sw"><div><b>${L('Nada ikut berubah', 'Pitch follows speed')}</b><small>${L('Gaya slowed + reverb / nightcore', 'Slowed + reverb / nightcore style')}</small></div><label class="tg"><input type="checkbox" id="mxP" ${m.pitch ? 'checked' : ''}><i></i></label></div></div>
    <div class="mx-sec"><div class="sw" style="border:0;padding:0"><div><b>Reverb FX</b><small>${L('Efek gema ruang 3D', '3D room echo')}</small></div><label class="tg"><input type="checkbox" id="mxR" ${m.rev ? 'checked' : ''}><i></i></label></div>
      <div id="mxW" class="${m.rev ? '' : 'hidden'}"><div class="mx-h" style="margin-top:10px"><span class="muted" id="mxRl"></span></div>
        <div class="pad" id="mxPad"><i class="hl"></i><i class="vl"></i><i class="puck" id="mxPk"></i><span class="al t">▲ ${L('Luas', 'Wide')}</span><span class="al b">▼ ${L('Rapat', 'Tight')}</span><span class="al l">◀ Dry</span><span class="al r">Wet ▶</span></div>
        <small class="muted" id="mxN"></small></div></div>
    <div class="btnrow" style="margin-top:12px"><button class="btn" id="mxReset">Reset</button><button class="btn primary" id="mxOk">OK</button></div>`);
  const save = () => set('nx_mix', m);
  const spd = () => { $('#mxSl').textContent = m.speed + '% · ' + tag(m.speed); A.speed(m.speed / 100, !!m.pitch && m.speed !== 100); save(); render(S); };
  const rev = () => {
    const pk = $('#mxPk'); pk.style.left = m.wet * 100 + '%'; pk.style.top = (1 - m.depth) * 100 + '%';
    $('#mxRl').textContent = L('Gema ', 'Wet ') + Math.round(m.wet * 100) + '% · ' + L('Ruang ', 'Size ') + Math.round(m.depth * 100) + '%';
    $('#mxW').classList.toggle('hidden', !m.rev); A.rev(!!m.rev, presetOf(m.depth), m.wet); save(); render(S);
    $('#mxN').textContent = m.rev && S.revOk === false ? L('Perangkat ini tidak mendukung efek reverb.', 'This device does not support the reverb effect.') : L('Atas = Luas • Kanan = Gema', 'Up = Wide • Right = Wet');
  };
  $('#mxS').oninput = e => { m.speed = +e.target.value; spd(); };
  $('#mxP').onchange = e => { m.pitch = e.target.checked; spd(); };
  $('#mxR').onchange = e => { m.rev = e.target.checked; rev(); };
  const pad = $('#mxPad'); let drag = false;
  const at = (cx, cy) => { const r = pad.getBoundingClientRect(); if (!r.width) return; m.wet = Math.max(0, Math.min(1, (cx - r.left) / r.width)); m.depth = Math.max(0, Math.min(1, 1 - (cy - r.top) / r.height)); rev(); };
  pad.addEventListener('pointerdown', e => { drag = true; try { pad.setPointerCapture(e.pointerId); } catch (_) {} at(e.clientX, e.clientY); });
  pad.addEventListener('pointermove', e => { if (drag) at(e.clientX, e.clientY); });
  ['pointerup', 'pointercancel'].forEach(t => pad.addEventListener(t, () => { drag = false; }));
  $('#mxReset').onclick = () => { Object.assign(m, { speed: 100, pitch: true, rev: false, depth: .5, wet: .5 }); $('#mxS').value = 100; $('#mxP').checked = true; $('#mxR').checked = false; spd(); rev(); toast(L('MixTools di-reset', 'MixTools reset')); };
  $('#mxOk').onclick = closeModal; rev(); spd();
}
$('#bMix').onclick = openMix;

/* ---------- timer tidur & antrean ---------- */
$('#bSleep').onclick = () => {
  openModal(`<div class="eyebrow">${L('TIMER TIDUR', 'SLEEP TIMER')}</div><h3>${L('Berhenti otomatis', 'Stop automatically')}</h3><div class="list">${[[0, L('Mati', 'Off')], [15, '15 ' + L('menit', 'min')], [30, '30 ' + L('menit', 'min')], [60, '60 ' + L('menit', 'min')], [-1, L('Setelah lagu ini selesai', 'After this track')]].map(([v, t]) => `<button class="item" data-v="${v}"><span class="it"><b>${t}</b></span></button>`).join('')}</div>`);
  $('#mBody').querySelector('.list').onclick = e => { const b = e.target.closest('[data-v]'); if (!b) return; A.sleep(+b.dataset.v); closeModal(); toast(+b.dataset.v === 0 ? L('Timer tidur mati', 'Sleep timer off') : L('Timer tidur diatur', 'Sleep timer set')); kick(); };
};
$('#bQueue').onclick = () => {
  const l = A.list();
  openModal(`<div class="eyebrow">${L('ANTREAN', 'QUEUE')}</div><h3>${l.length} ${L('lagu', 'tracks')}</h3><div class="list" id="qL">${l.map((t, i) => `<button class="item mrow${i === S.idx ? ' cur' : ''}" data-i="${i}"><span class="ik"><em>${i === S.idx ? '♫' : i + 1}</em></span><span class="it"><b>${esc(t.title)}</b><small>${esc(t.artist || '')}</small></span></button>`).join('')}</div>`);
  $('#qL').onclick = e => { const b = e.target.closest('[data-i]'); if (!b) return; A.jump(+b.dataset.i); closeModal(); kick(); };
  const c = $('#qL .cur'); if (c) c.scrollIntoView({ block: 'center' });
};

/* ---------- fallback file input (browser/dev) ---------- */
if (NATIVE) { const lbl = $('label[for=fileIn]'); if (lbl) lbl.classList.add('hidden'); }
$('#fileIn').onchange = e => {
  const fs = [...e.target.files]; e.target.value = ''; if (!fs.length) return;
  const l = fs.map(f => ({ id: f.name + f.size, title: clean(f.name), artist: '', uri: URL.createObjectURL(f), dur: 0, k: f.type.startsWith('video') ? 'video' : 'audio' }));
  A.queue(l, 0, true, 0); kick();
};

/* ---------- API publik ---------- */
function start() { if (started) return; started = true; loop(); }
function enqueue(x) {                                                      // dari tombol "antrean" di Beranda: {n,u,v}
  const t = [{ id: x.u, title: x.n, artist: '', uri: x.u, dur: 0 }];
  if (S.on) A.add(t); else A.queue(t, 0, true, 0);
  start(); kick();
}
function show() { start(); render(A.state()); if (NATIVE && LIB.audio === null) refresh(false); else if (!NATIVE && LIB.audio === null) { LIB = { audio: [], video: [], image: [] }; view(); } }
window.PL = {
  show, enqueue, folderChosen, refresh: f => { if (LIB.audio !== null || f) refresh(true); }, paint: () => render(S),
  folder: openFolder, cfgChanged: () => A.cfg(), back: () => closeImage(), toggle: () => { A.toggle(); kick(); }
};
start();
})();
