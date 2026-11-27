/* Alisa Lite — modul Tools (port ringan dari Mori, tanpa Capacitor).
   Dimuat sekali saat menu Tools dibuka (lazy). Library PDF TIDAK dibundel: dimuat dari CDN hanya saat Kompres PDF dipakai.
   Jaringan lewat E.http -> jembatan native NX (bebas CORS). Simpan hasil lewat NX.saveBegin/saveChunk/saveEnd. */
(() => {
'use strict';
if (window.AT) return;
const W = window, N = W.NX, E = () => W.E;
const $ = (s, r) => (r || document).querySelector(s), $$ = (s, r) => [...(r || document).querySelectorAll(s)];
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const LS = k => { try { return JSON.parse(localStorage.getItem(k)); } catch (_) { return null; } };
const EN = () => (LS('nx_lang') || ((navigator.language || 'id').startsWith('en') ? 'en' : 'id')) === 'en';
const T = (i, e) => EN() ? e : i;
const toast = m => W.__toast ? W.__toast(m) : console.log(m);
const hap = () => { try { if (N && N.haptic && LS('nx_haptic')) N.haptic(); } catch (_) {} };
const fmtB = n => n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(1) + ' KB' : (n / 1048576).toFixed(2) + ' MB';
const stem = n => String(n || 'file').replace(/\.[^.]+$/, '').replace(/[\\/:*?"<>|]+/g, '_').slice(0, 80) || 'file';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const UA = 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36';
const urls = new Set();
const mkUrl = b => { const u = URL.createObjectURL(b); urls.add(u); return u; };
const freeUrls = () => { urls.forEach(u => URL.revokeObjectURL(u)); urls.clear(); };

/* ---------- jaringan ---------- */
const customUA = () => (LS('nx_ua') || '').trim() || UA;
async function http(o) {
  o.headers = Object.assign({ 'User-Agent': customUA() }, o.headers || {});
  return E().http(o);
}
function wifiGuard() {
  if (LS('nx_wifionly') && N && N.netType && N.netType() === 'cell') { toast(T('Mode Hanya Wi-Fi aktif — sambungkan Wi-Fi dulu', 'Wi-Fi only mode is on — connect to Wi-Fi first')); return false; }
  return true;
}

/* ---------- file ---------- */
function pick(accept, multiple) {
  return new Promise(res => {
    const i = document.createElement('input'); i.type = 'file'; i.accept = accept; i.multiple = !!multiple; i.style.display = 'none';
    i.onchange = () => { res([...i.files]); i.remove(); }; i.oncancel = () => { res([]); i.remove(); };
    document.body.appendChild(i); i.click();
  });
}
const b64Of = b => new Promise((ok, no) => { const r = new FileReader(); r.onload = () => ok(String(r.result).split(',')[1] || ''); r.onerror = no; r.readAsDataURL(b); });
async function saveBlob(blob, name) {
  if (N && N.saveBegin) {
    const id = N.saveBegin(name, blob.type || 'application/octet-stream');
    if (id === 'PERM') { toast(T('Izinkan akses penyimpanan, lalu tekan Simpan lagi', 'Allow storage access, then tap Save again')); return false; }
    if (!id) { toast(T('Gagal menyiapkan penyimpanan', 'Could not prepare storage')); return false; }
    const CH = 3 * 128 * 1024;                       // kelipatan 3 byte -> base64 tanpa padding di tengah
    for (let o = 0; o < blob.size; o += CH) {
      if (!N.saveChunk(id, await b64Of(blob.slice(o, o + CH)))) { toast(T('Gagal menulis file', 'Could not write file')); return false; }
    }
    const r = N.saveEnd(id);
    if (!r) { toast(T('Gagal menyimpan file', 'Could not save file')); return false; }
    toast(T('Tersimpan: Download/alisadownloader/', 'Saved: Download/alisadownloader/') + r); hap(); return true;
  }
  const a = document.createElement('a'); a.href = mkUrl(blob); a.download = name; document.body.appendChild(a); a.click(); a.remove(); return true;
}
const awake = on => { try { if (N && N.keepAwake) N.keepAwake(!!on); } catch (_) {} };
const copy = t => { if (N) N.copy(t); else if (navigator.clipboard) navigator.clipboard.writeText(t).catch(() => {}); toast(T('Tersalin', 'Copied')); };
const openUrl = u => { if (N) N.open(u); else window.open(u, '_blank'); };
const segVal = el => ($('button.on', el) || {}).dataset ? $('button.on', el).dataset.v : '';
function segs(root) { $$('.seg', root).forEach(s => s.addEventListener('click', e => { const b = e.target.closest('button[data-v]'); if (!b) return; $$('button', s).forEach(x => x.classList.toggle('on', x === b)); s.dispatchEvent(new Event('change')); })); }
const seg = (k, opts, on) => `<div class="seg" data-k="${k}">${opts.map(([v, l], i) => `<button data-v="${v}"${(on != null ? on === v : i === 0) ? ' class="on"' : ''}>${l}</button>`).join('')}</div>`;
const lbl = s => `<div class="eyebrow tv-l">${s}</div>`;
const kv = (a, b) => `<div class="kv"><span>${a}</span><b>${b}</b></div>`;
const say = (el, m, c) => { el.textContent = m || ''; el.className = 'status' + (c ? ' ' + c : ''); };

/* =============== 1. Video → Audio (WAV 16-bit) =============== */
function wav(buf, stereo) {
  const ch = stereo && buf.numberOfChannels >= 2 ? 2 : 1, sr = buf.sampleRate, n = buf.length, data = n * ch * 2;
  const ab = new ArrayBuffer(44 + data), v = new DataView(ab), w = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
  w(0, 'RIFF'); v.setUint32(4, 36 + data, true); w(8, 'WAVE'); w(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, ch, true);
  v.setUint32(24, sr, true); v.setUint32(28, sr * ch * 2, true); v.setUint16(32, ch * 2, true); v.setUint16(34, 16, true); w(36, 'data'); v.setUint32(40, data, true);
  const c0 = buf.getChannelData(0), c1 = ch === 2 ? buf.getChannelData(1) : null; let o = 44;
  for (let i = 0; i < n; i++) {
    let s = Math.max(-1, Math.min(1, c0[i])); v.setInt16(o, s < 0 ? s * 0x8000 : s * 0x7fff, true); o += 2;
    if (c1) { s = Math.max(-1, Math.min(1, c1[i])); v.setInt16(o, s < 0 ? s * 0x8000 : s * 0x7fff, true); o += 2; }
  }
  return new Blob([ab], { type: 'audio/wav' });
}
function vAudio(b) {
  b.innerHTML = `<div class="panel"><p class="muted">${T('Ambil trek audio dari video di perangkat → WAV 16-bit. Maks 200 MB.', 'Extract the audio track from a local video → 16-bit WAV. Max 200 MB.')}</p>
    ${lbl(T('SALURAN', 'CHANNELS'))}${seg('ch', [['stereo', 'Stereo'], ['mono', 'Mono']])}
    <button class="btn primary wide" id="go">${T('Pilih video', 'Choose video')}</button><div class="status" id="st"></div><div id="out"></div></div>`;
  segs(b); const st = $('#st', b), out = $('#out', b);
  $('#go', b).onclick = async () => {
    const f = (await pick('video/*,audio/*'))[0]; if (!f) return; out.innerHTML = '';
    if (f.size > 200 * 1048576) return say(st, T('Video terlalu besar (>200 MB) — pilih klip lebih kecil agar tidak crash.', 'Video too large (>200 MB) — pick a smaller clip.'), 'err');
    const AC = W.AudioContext || W.webkitAudioContext; if (!AC) return say(st, 'Web Audio API tidak tersedia', 'err');
    say(st, T('Mengekstrak…', 'Extracting…')); awake(true); const ctx = new AC();
    try {
      const ab = await ctx.decodeAudioData(await f.arrayBuffer()), blob = wav(ab, segVal($('[data-k=ch]', b)) !== 'mono');
      const nm = stem(f.name) + '.wav'; say(st, T('Selesai', 'Done'), 'ok');
      out.innerHTML = `<audio class="pv" controls src="${mkUrl(blob)}"></audio>${kv(T('Durasi', 'Duration'), Math.floor(ab.duration / 60) + ':' + String(Math.floor(ab.duration % 60)).padStart(2, '0'))}${kv('Sample rate', ab.sampleRate + ' Hz')}${kv(T('Ukuran video', 'Video size'), fmtB(f.size))}${kv(T('Ukuran audio', 'Audio size'), fmtB(blob.size))}<button class="btn primary wide" id="sv">${T('Simpan', 'Save')} ${esc(nm)}</button>`;
      $('#sv', out).onclick = () => saveBlob(blob, nm);
    } catch (e) { say(st, T('Tidak bisa membaca audio video ini (kosong atau codec tidak didukung).', 'Could not read this video’s audio (silent or unsupported codec).'), 'err'); }
    finally { awake(false); try { ctx.close(); } catch (_) {} }
  };
}

/* =============== 2. Konverter gambar =============== */
async function convertImage(file, fmt, q, sc) {
  let src, w, h, close = () => {};
  if (W.createImageBitmap) { try { const bm = await createImageBitmap(file); src = bm; w = bm.width; h = bm.height; close = () => bm.close && bm.close(); } catch (_) {} }
  if (!src) src = await new Promise((ok, no) => { const u = URL.createObjectURL(file), i = new Image(); i.onload = () => { URL.revokeObjectURL(u); ok(i); }; i.onerror = () => { URL.revokeObjectURL(u); no(new Error('decode')); }; i.src = u; }), w = src.naturalWidth, h = src.naturalHeight;
  const tw = Math.max(1, Math.round(w * sc)), th = Math.max(1, Math.round(h * sc)), c = document.createElement('canvas'); c.width = tw; c.height = th;
  const x = c.getContext('2d'); if (fmt === 'image/jpeg') { x.fillStyle = '#fff'; x.fillRect(0, 0, tw, th); } x.drawImage(src, 0, 0, tw, th); close();
  const blob = await new Promise(r => c.toBlob(r, fmt, q)); c.width = c.height = 0; if (!blob) throw new Error('fail');
  return { blob, w, h, tw, th };
}
function vImage(b) {
  let file = null;
  b.innerHTML = `<div class="panel"><p class="muted">${T('Ubah format (JPG / PNG / WebP) dan kecilkan ukuran gambar.', 'Convert (JPG / PNG / WebP) and shrink an image.')}</p>
    <button class="btn wide" id="pk">${T('Pilih gambar', 'Choose image')}</button><div class="muted" id="fn" style="margin-top:8px"></div>
    ${lbl(T('FORMAT', 'FORMAT'))}${seg('fmt', [['image/jpeg', 'JPG'], ['image/png', 'PNG'], ['image/webp', 'WebP']])}
    ${lbl(T('KUALITAS', 'QUALITY') + ' <b id="qv">85%</b>')}<input type="range" id="q" min="30" max="100" value="85">
    ${lbl(T('SKALA', 'SCALE') + ' <b id="sv2">100%</b>')}<input type="range" id="sc" min="10" max="100" value="100">
    <button class="btn primary wide" id="go" disabled>${T('Konversi', 'Convert')}</button><div class="status" id="st"></div><div id="out"></div></div>`;
  segs(b); const st = $('#st', b), out = $('#out', b), q = $('#q', b), sc = $('#sc', b);
  q.oninput = () => $('#qv', b).textContent = q.value + '%'; sc.oninput = () => $('#sv2', b).textContent = sc.value + '%';
  $('#pk', b).onclick = async () => { const f = (await pick('image/*'))[0]; if (!f) return; file = f; $('#fn', b).textContent = f.name + ' · ' + fmtB(f.size); $('#go', b).disabled = false; out.innerHTML = ''; say(st, ''); };
  $('#go', b).onclick = async () => {
    if (!file) return; const fmt = segVal($('[data-k=fmt]', b)); say(st, T('Mengonversi…', 'Converting…')); out.innerHTML = '';
    try {
      const r = await convertImage(file, fmt, q.value / 100, sc.value / 100), ext = fmt === 'image/jpeg' ? 'jpg' : fmt === 'image/png' ? 'png' : 'webp', nm = stem(file.name) + '.' + ext;
      say(st, T('Selesai', 'Done'), 'ok');
      out.innerHTML = `<img class="pv" src="${mkUrl(r.blob)}" alt="">${kv(T('Dimensi', 'Size'), r.w + '×' + r.h + ' → ' + r.tw + '×' + r.th)}${kv(T('Ukuran', 'File size'), fmtB(file.size) + ' → ' + fmtB(r.blob.size))}<button class="btn primary wide" id="sv">${T('Simpan', 'Save')} ${esc(nm)}</button>`;
      $('#sv', out).onclick = () => saveBlob(r.blob, nm);
    } catch (e) { say(st, T('Gagal mengonversi gambar', 'Conversion failed'), 'err'); }
  };
}

/* =============== 3. Kompres PDF (library dari CDN, hanya saat dipakai) =============== */
const CDN = { lib: 'https://cdnjs.cloudflare.com/ajax/libs/pdf-lib/1.17.1/pdf-lib.min.js', js: 'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.min.js', wk: 'https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js' };
const loadScript = src => new Promise((ok, no) => { const s = document.createElement('script'); s.src = src; s.onload = ok; s.onerror = () => no(new Error(T('Gagal memuat pustaka PDF — butuh internet saat pertama kali dipakai.', 'Could not load the PDF library — internet is needed the first time.'))); document.head.appendChild(s); });
async function pdfLibs() {
  if (!W.PDFLib) await loadScript(CDN.lib);
  if (!W.pdfjsLib) await loadScript(CDN.js);
  if (!W.__pdfWk) { const r = await E().http({ url: CDN.wk, timeout: 90000 }); if (r.status !== 200 || r.text.length < 1000) throw new Error('worker PDF gagal dimuat'); W.__pdfWk = URL.createObjectURL(new Blob([r.text], { type: 'text/javascript' })); }
  W.pdfjsLib.GlobalWorkerOptions.workerSrc = W.__pdfWk;
}
function vPdf(b) {
  let file = null;
  b.innerHTML = `<div class="panel"><p class="muted">${T('Kecilkan ukuran PDF (halaman dirender ulang sebagai gambar JPEG). Cocok untuk dokumen hasil scan; teks tidak bisa diseleksi lagi.', 'Shrink a PDF (pages are re-rendered as JPEG). Best for scans; text will no longer be selectable.')}</p>
    <button class="btn wide" id="pk">${T('Pilih PDF', 'Choose PDF')}</button><div class="muted" id="fn" style="margin-top:8px"></div>
    ${lbl(T('TINGKAT KOMPRESI', 'COMPRESSION'))}${seg('lv', [['low', T('Ringan', 'Light')], ['medium', T('Sedang', 'Medium')], ['high', T('Kuat', 'Strong')]], 'medium')}
    <div class="tprog hidden" id="pg"><i></i></div>
    <button class="btn primary wide" id="go" disabled>${T('Kompres', 'Compress')}</button><div class="status" id="st"></div><div id="out"></div>
    <p class="muted" style="font-size:12px;margin-top:10px">${T('Pustaka PDF (~1 MB) diunduh sekali saat pertama dipakai agar APK tetap ringan.', 'The PDF library (~1 MB) is downloaded once on first use to keep the APK small.')}</p></div>`;
  segs(b); const st = $('#st', b), out = $('#out', b), pg = $('#pg', b);
  $('#pk', b).onclick = async () => { const f = (await pick('application/pdf'))[0]; if (!f) return; file = f; $('#fn', b).textContent = f.name + ' · ' + fmtB(f.size); $('#go', b).disabled = false; out.innerHTML = ''; say(st, ''); };
  $('#go', b).onclick = async () => {
    if (!file) return; if (file.size > 80 * 1048576) return say(st, T('PDF terlalu besar (>80 MB) untuk diproses di perangkat.', 'PDF too large (>80 MB) to process on-device.'), 'err');
    const cfg = { low: [1.1, .85], medium: [.9, .7], high: [.7, .5] }[segVal($('[data-k=lv]', b))]; out.innerHTML = ''; $('#go', b).disabled = true; awake(true);
    try {
      say(st, T('Memuat pustaka PDF…', 'Loading PDF library…')); await pdfLibs();
      const doc = await W.pdfjsLib.getDocument({ data: new Uint8Array(await file.arrayBuffer()), cMapPacked: true }).promise, n = doc.numPages;
      if (!n) throw new Error(T('PDF tidak punya halaman', 'PDF has no pages'));
      const np = await W.PDFLib.PDFDocument.create(); pg.classList.remove('hidden');
      for (let i = 1; i <= n; i++) {
        say(st, T('Halaman ', 'Page ') + i + ' / ' + n); pg.firstChild.style.width = (i - 1) / n * 100 + '%'; await sleep(0);
        const p = await doc.getPage(i), vp = p.getViewport({ scale: cfg[0] }), c = document.createElement('canvas');
        c.width = Math.max(1, Math.round(vp.width)); c.height = Math.max(1, Math.round(vp.height));
        const x = c.getContext('2d'); x.fillStyle = '#fff'; x.fillRect(0, 0, c.width, c.height); await p.render({ canvasContext: x, viewport: vp }).promise;
        const jb = await new Promise(r => c.toBlob(r, 'image/jpeg', cfg[1])); c.width = c.height = 0;
        if (jb) { const im = await np.embedJpg(await jb.arrayBuffer()), o = p.getViewport({ scale: 1 }), pp = np.addPage([o.width, o.height]); pp.drawImage(im, { x: 0, y: 0, width: o.width, height: o.height }); }
        p.cleanup && p.cleanup();
      }
      pg.firstChild.style.width = '100%';
      const blob = new Blob([await np.save({ useObjectStreams: true })], { type: 'application/pdf' }), nm = stem(file.name) + '-kompres.pdf', saved = Math.round((1 - blob.size / file.size) * 100);
      say(st, blob.size < file.size ? T('Selesai — hemat ', 'Done — saved ') + saved + '%' : T('Hasil tidak lebih kecil dari aslinya (PDF sudah efisien).', 'Result is not smaller than the original (already efficient).'), blob.size < file.size ? 'ok' : 'err');
      out.innerHTML = kv(T('Halaman', 'Pages'), n) + kv(T('Ukuran', 'Size'), fmtB(file.size) + ' → ' + fmtB(blob.size)) + `<button class="btn primary wide" id="sv">${T('Simpan', 'Save')} ${esc(nm)}</button>`;
      $('#sv', out).onclick = () => saveBlob(blob, nm); doc.destroy && doc.destroy();
    } catch (e) { say(st, e.message || String(e), 'err'); }
    finally { awake(false); $('#go', b).disabled = false; }
  };
}

/* =============== 4. Web Media Sniffer =============== */
const VID = /\.(mp4|webm|mov|mkv|m4v|3gp|avi|flv)(\?|#|$)/i, STR = /\.(m3u8|mpd)(\?|#|$)/i, SEG = /\.ts(\?|#|$)/i, AUD = /\.(mp3|m4a|wav|aac|flac|ogg|opus|weba)(\?|#|$)/i, IMG = /\.(jpe?g|png|webp|gif|svg|avif|bmp)(\?|#|$)/i;
const SCRIPT_MEDIA = /https?:\\?\/\\?\/[^"'<>\s\\]+\.(?:mp4|webm|mov|mkv|m4v|3gp|avi|flv|m3u8|mpd|ts|mp3|m4a|wav|aac|flac|ogg|opus|weba)(?:\?[^"'<>\s\\]*)?/gi;
const IFRAME = /(?:youtube(?:-nocookie)?\.com\/embed\/|player\.vimeo\.com\/video\/|dailymotion\.com\/embed\/|bilibili\.com\/player|facebook\.com\/plugins\/video)/i;
const unesc = r => String(r || '').trim().replace(/\\u0026/g, '&').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/\\\//g, '/').replace(/\\"/g, '"').replace(/\\/g, '');
const abs = (r, base) => { const c = unesc(r); if (!c || /^(javascript|data|mailto):/i.test(c)) return null; try { return new URL(c, base).href; } catch (_) { return null; } };
function bestSrcset(s, base) { let m = -1, u = null; String(s || '').split(',').forEach(p => { const t = p.trim().split(/\s+/); if (!t[0]) return; const w = t[1] && /w$/.test(t[1]) ? parseInt(t[1], 10) || 0 : 0; if (w >= m) { m = w; u = t[0]; } }); return u ? abs(u, base) : null; }
async function sniff(target) {
  let u = String(target || '').trim(); if (!u) throw new Error(T('Masukkan URL halaman', 'Enter a page URL')); if (!/^https?:\/\//i.test(u)) u = 'https://' + u;
  const r = await http({ url: u, headers: { Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8' } });
  const html = r.text || ''; if (!html) throw new Error(T('Halaman tidak mengembalikan konten', 'The page returned no content'));
  const base = r.url && r.url !== 'ERR' ? r.url : u, doc = new DOMParser().parseFromString(html, 'text/html');
  let title = (doc.querySelector('title') || {}).textContent || (doc.querySelector('meta[property="og:title"]') || { getAttribute: () => '' }).getAttribute('content') || ''; title = title.trim(); if (!title) { try { title = new URL(base).hostname; } catch (_) { title = 'Media'; } }
  const seen = new Set(), V = [], A = [], I = [];
  const add = (list, url, label, extra) => { const a = abs(url, base); if (!a || seen.has(a)) return; seen.add(a); list.push(Object.assign({ url: a, title: label }, extra || {})); };
  const meta = s => { const e = doc.querySelector(s); return e && e.getAttribute('content'); };
  doc.querySelectorAll('video').forEach(v => { const po = abs(v.getAttribute('poster'), base); if (v.getAttribute('src')) add(V, v.getAttribute('src'), title + ' (Video ' + (V.length + 1) + ')', { poster: po }); v.querySelectorAll('source').forEach(s => s.getAttribute('src') && add(V, s.getAttribute('src'), title + ' (Video ' + (V.length + 1) + ')', { poster: po })); });
  doc.querySelectorAll('audio').forEach(a => { if (a.getAttribute('src')) add(A, a.getAttribute('src'), title + ' (Audio ' + (A.length + 1) + ')'); a.querySelectorAll('source').forEach(s => s.getAttribute('src') && add(A, s.getAttribute('src'), title + ' (Audio ' + (A.length + 1) + ')')); });
  doc.querySelectorAll('iframe[src]').forEach(f => { const s = f.getAttribute('src'); if (IFRAME.test(s)) add(V, s, title + ' (Embed)', { embed: true }); });
  const ogv = meta('meta[property="og:video"]') || meta('meta[property="og:video:url"]') || meta('meta[property="og:video:secure_url"]') || meta('meta[name="twitter:player:stream"]'); if (ogv) add(V, ogv, title + ' (OG Video)');
  const oga = meta('meta[property="og:audio"]') || meta('meta[property="og:audio:url"]'); if (oga) add(A, oga, title + ' (OG Audio)');
  const ogi = meta('meta[property="og:image"]') || meta('meta[property="og:image:url"]') || meta('meta[name="twitter:image"]'); if (ogi) add(I, ogi, title + ' (OG Image)');
  doc.querySelectorAll('a[href]').forEach(a => { const h = a.getAttribute('href') || '', t = (a.textContent || '').trim() || 'Media'; if (VID.test(h) || STR.test(h)) add(V, h, title + ' - ' + t); else if (AUD.test(h)) add(A, h, title + ' - ' + t); else if (IMG.test(h)) add(I, h, title + ' - ' + t); });
  doc.querySelectorAll('img').forEach(im => {
    const s = bestSrcset(im.getAttribute('srcset'), base) || im.getAttribute('data-src') || im.getAttribute('data-original') || im.getAttribute('data-lazy-src') || im.getAttribute('src');
    if (!s || /^data:/.test(s)) return; const w = parseInt(im.getAttribute('width') || '0', 10), h = parseInt(im.getAttribute('height') || '0', 10);
    if ((w > 0 && w < 80) || (h > 0 && h < 80) || /favicon|tracker|pixel|spacer|badge|1x1/i.test(s)) return;
    const alt = (im.getAttribute('alt') || '').trim(); add(I, s, alt ? title + ' - ' + alt : title + ' (Image ' + (I.length + 1) + ')');
  });
  for (const m of html.matchAll(/"contentUrl"\s*:\s*"([^"]+)"/gi)) { const x = unesc(m[1]); if (VID.test(x) || STR.test(x)) add(V, x, title + ' (Schema Video)'); else if (AUD.test(x)) add(A, x, title + ' (Schema Audio)'); }
  for (const m of html.matchAll(/"video_versions"\s*:\s*\[\s*\{[^}]*?"url"\s*:\s*"([^"]+)"/gi)) add(V, unesc(m[1]), title + ' (Social Video)');
  for (const m of html.matchAll(/"progressive"\s*:\s*\[\s*\{[^}]*?"url"\s*:\s*"([^"]+)"/gi)) add(V, unesc(m[1]), title + ' (Progressive)');
  for (const m of html.matchAll(SCRIPT_MEDIA)) { const x = unesc(m[0]); if (VID.test(x) || STR.test(x)) add(V, x, title + ' (Script Video)'); else if (AUD.test(x)) add(A, x, title + ' (Script Audio)'); }
  for (const m of html.matchAll(/"videoUrl"\s*:\s*"([^"]+)"/gi)) { const x = unesc(m[1]); if (VID.test(x) || STR.test(x)) add(V, x, title + ' (Stream)'); }
  const hls = V.some(v => STR.test(v.url)), fv = hls ? V.filter(v => !SEG.test(v.url)).sort((a, b) => (STR.test(b.url) ? 1 : 0) - (STR.test(a.url) ? 1 : 0)) : V;
  return { title, url: base, videos: fv, audios: A, images: I, total: fv.length + A.length + I.length };
}
const MIMES = { mp4: 'video/mp4', webm: 'video/webm', mov: 'video/quicktime', mkv: 'video/x-matroska', mp3: 'audio/mpeg', m4a: 'audio/mp4', wav: 'audio/wav', aac: 'audio/aac', flac: 'audio/flac', ogg: 'audio/ogg', jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp', gif: 'image/gif' };
const extOf = (u, d) => { try { const m = new URL(u).pathname.match(/\.([a-z0-9]{2,5})$/i); return m ? m[1].toLowerCase() : d; } catch (_) { return d; } };
const folder = () => String(LS('nx_folder') || 'Alisa Lite').replace(/[\\/:*?"<>|]+/g, ' ').trim() || 'Alisa Lite';
function nativeDl(url, title, ext, referer) {
  if (!wifiGuard()) return false;
  if (!N) { window.open(url, '_blank'); return true; }
  const id = N.dl(url, folder() + '/' + stem(title) + '.' + ext, MIMES[ext] || 'application/octet-stream', 'User-Agent: ' + customUA() + (referer ? '\nReferer: ' + referer : ''));
  if (!id) { toast(T('Unduhan ditolak: ', 'Download rejected: ') + (N.dlError ? N.dlError() : '')); return false; }
  toast(T('Mengunduh…', 'Downloading…')); hap(); return true;
}
function vSniffer(b) {
  b.innerHTML = `<div class="panel"><p class="muted">${T('Cari video, audio, dan gambar yang tertanam di sebuah halaman web.', 'Find videos, audio and images embedded in a web page.')}</p>
    <input class="fld" id="u" type="url" placeholder="https://…"><div class="btnrow"><button class="btn" id="ps">${T('Tempel', 'Paste')}</button><button class="btn primary" id="go">${T('Cari media', 'Find media')}</button></div><div class="status" id="st"></div></div><div id="out"></div>`;
  const st = $('#st', b), out = $('#out', b); let cache = [];
  $('#ps', b).onclick = () => { const t = N ? N.paste() : ''; if (t) $('#u', b).value = t.trim(); };
  $('#go', b).onclick = async () => {
    say(st, T('Memindai halaman…', 'Scanning page…')); out.innerHTML = ''; cache = [];
    try {
      const r = await sniff($('#u', b).value); say(st, r.total ? T('Ditemukan ', 'Found ') + r.total + ' media' : T('Tidak ada media ditemukan di sumber halaman (mungkin dimuat lewat JavaScript).', 'No media found in the page source (it may be loaded by JavaScript).'), r.total ? 'ok' : 'err');
      const grp = (name, list, kind) => !list.length ? '' : `<div class="sec"><h3>${name} (${list.length})</h3></div><div class="list">${list.map(m => { const i = cache.push(m) - 1, ext = extOf(m.url, kind === 'v' ? 'mp4' : kind === 'a' ? 'mp3' : 'jpg'), stream = STR.test(m.url), dl = !m.embed && !stream;
        return `<div class="hrow"><div class="il"><b>${esc(m.title)}</b><small class="muted" style="word-break:break-all">${esc(m.url.slice(0, 90))}</small>${stream ? `<small class="muted">${T('Stream HLS/DASH — salin tautan untuk pemutar lain', 'HLS/DASH stream — copy the link for another player')}</small>` : ''}</div><div class="acts"><button data-a="cp" data-i="${i}">${T('Salin', 'Copy')}</button>${dl ? `<button class="go" data-a="dl" data-i="${i}" data-e="${ext}">${T('Unduh', 'Get')}</button>` : `<button data-a="op" data-i="${i}">${T('Buka', 'Open')}</button>`}</div></div>`; }).join('')}</div>`;
      out.innerHTML = grp(T('Video', 'Video'), r.videos, 'v') + grp(T('Audio', 'Audio'), r.audios, 'a') + grp(T('Gambar', 'Images'), r.images, 'i'); out._base = r;
    } catch (e) { say(st, e.message || String(e), 'err'); }
  };
  out.onclick = e => { const x = e.target.closest('button[data-a]'); if (!x) return; const m = cache[+x.dataset.i]; if (!m) return; const a = x.dataset.a;
    if (a === 'cp') copy(m.url); else if (a === 'op') openUrl(m.url); else if (a === 'dl') nativeDl(m.url, m.title, x.dataset.e, out._base && out._base.url); };
}

/* =============== 5. Resolver tautan pendek (ikuti redirect, tanpa melewati iklan/proteksi) =============== */
const cleanUrl = t => { const m = String(t || '').match(/https?:\/\/[^\s]+/i); let c = (m ? m[0] : String(t || '').trim()).replace(/[\u200B-\u200D\uFEFF]/g, '').trim(); if (c && !/^https?:\/\//i.test(c)) c = 'https://' + c; return c; };
function paramTarget(u) {
  try { const p = new URL(u); for (const k of ['url', 'link', 'target', 'go', 'u', 'dest']) { const v = p.searchParams.get(k); if (!v) continue; try { const d = atob(v); if (/^https?:\/\//i.test(d)) return d.trim(); } catch (_) {} if (/^https?:\/\//i.test(v)) return v.trim(); } } catch (_) {}
  return null;
}
const metaRefresh = (h, base) => { const m = h.match(/<meta[^>]+http-equiv=["']?refresh["']?[^>]*content=["'][^"']*?url\s*=\s*([^"'>\s]+)/i); try { return m ? new URL(m[1], base).href : null; } catch (_) { return null; } };
const jsRedirect = (h, base) => { const m = h.slice(0, 60000).match(/(?:window\.|document\.|top\.)?location(?:\.href|\.replace\()?\s*=?\s*\(?\s*["'](https?:\/\/[^"']+)["']/i); try { return m ? new URL(m[1], base).href : null; } catch (_) { return null; } };
async function resolveLink(input) {
  let u = cleanUrl(input); if (!u) throw new Error(T('Tempel tautan dulu', 'Paste a link first'));
  const trail = [u], p = paramTarget(u); if (p) { trail.push(p); u = p; }
  const follow = r => { const f = r && r.url && r.url !== 'ERR' ? r.url : u; if (f !== u) { trail.push(f); u = f; } };
  for (let hop = 0; hop < 6; hop++) {
    let h = null; try { h = await http({ url: u, method: 'HEAD' }); } catch (_) {}          // HEAD dulu: murah, tidak mengunduh isi file
    if (h && h.status > 0 && h.status < 400) { follow(h); const ct = (h.headers || {})['content-type'] || ''; if (ct && !/html/i.test(ct)) break; }
    const r = await http({ url: u }); follow(r);                                             // GET hanya untuk halaman HTML (meta-refresh / redirect JS)
    const nx = r.text ? (metaRefresh(r.text, u) || jsRedirect(r.text, u)) : null;
    if (nx && nx !== u && !trail.includes(nx)) { trail.push(nx); u = nx; continue; }
    break;
  }
  return { final: u, trail };
}
function vLink(b) {
  b.innerHTML = `<div class="panel"><p class="muted">${T('Ikuti pengalihan tautan pendek (bit.ly, dll.) dan tampilkan tujuan akhirnya. Tidak melewati halaman iklan atau proteksi.', 'Follow short-link redirects (bit.ly etc.) and show the final destination. It does not skip ad or protection pages.')}</p>
    <input class="fld" id="u" type="url" placeholder="https://…"><div class="btnrow"><button class="btn" id="ps">${T('Tempel', 'Paste')}</button><button class="btn primary" id="go">${T('Periksa', 'Resolve')}</button></div><div class="status" id="st"></div><div id="out"></div></div>`;
  const st = $('#st', b), out = $('#out', b);
  $('#ps', b).onclick = () => { const t = N ? N.paste() : ''; if (t) $('#u', b).value = cleanUrl(t); };
  $('#go', b).onclick = async () => {
    say(st, T('Memeriksa…', 'Resolving…')); out.innerHTML = '';
    try {
      const r = await resolveLink($('#u', b).value); say(st, r.trail.length > 1 ? T('Tujuan ditemukan', 'Destination found') : T('Tidak ada pengalihan — tautan sudah langsung', 'No redirect — the link is already direct'), 'ok');
      out.innerHTML = `${kv(T('Tujuan akhir', 'Final URL'), esc(r.final))}${kv(T('Langkah', 'Hops'), r.trail.length - 1)}<div class="btnrow"><button class="btn" id="cp">${T('Salin', 'Copy')}</button><button class="btn" id="op">${T('Buka', 'Open')}</button><button class="btn primary" id="an">${T('Analisis', 'Analyze')}</button></div>`;
      $('#cp', out).onclick = () => copy(r.final); $('#op', out).onclick = () => openUrl(r.final); $('#an', out).onclick = () => { AT.close(); W.__share && W.__share(r.final); };
    } catch (e) { say(st, e.message || String(e), 'err'); }
  };
}

/* =============== 6. TikTok User Finder =============== */
async function findTikTok(input) {
  let q = String(input || '').trim().replace(/^@/, ''); if (!q) throw new Error(T('Masukkan @username atau tautan profil', 'Enter a @username or profile link'));
  const r = await http({ url: 'https://tikuserfinder.com/api/finder/resolve?input=' + encodeURIComponent(q) + '&kind=auto', headers: { Accept: 'application/json, text/plain, */*', Referer: 'https://tikuserfinder.com/' } });
  let j; try { j = JSON.parse((r.text || '').trim()); } catch (_) { throw new Error(T('Layanan memblokir atau mengembalikan respons tidak valid', 'The service blocked the request or returned an invalid response')); }
  if (!j || j.code !== 0 || !j.data) throw new Error((j && j.message) || T('Gagal mencari akun', 'Lookup failed'));
  const d = j.data;
  if (d.status === 'not_found') return { notFound: true, message: T('Akun tidak ditemukan di TikTok.', 'Account not found on TikTok.') };
  if (d.status === 'daily_limit' || d.errorReason === 'budget') return { busy: true, message: T('Layanan pencarian sedang penuh. Coba lagi nanti.', 'The lookup service is busy. Try again later.') };
  if (d.status === 'unsupported') return { message: d.unsupportedReason === 'name' ? T('Cari dengan @username atau tautan profil, bukan nama lengkap.', 'Search by @username or profile link, not a full name.') : T('Format username/tautan tidak didukung.', 'Unsupported username/link format.') };
  if (!d.profile) throw new Error(T('Data profil tidak ada', 'No profile data'));
  return { ok: true, profile: d.profile };
}
const fDate = s => { if (!s) return '-'; try { return new Date(s).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }); } catch (_) { return String(s).slice(0, 10); } };
const fNum = n => n == null ? '-' : n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(1) + 'K' : String(n);
function vTiktok(b) {
  b.innerHTML = `<div class="panel"><p class="muted">${T('Cari tanggal pembuatan, avatar HD, ID numerik, dan statistik publik akun TikTok.', 'Look up creation date, HD avatar, numeric ID and public stats of a TikTok account.')}</p>
    <input class="fld" id="u" placeholder="@username / https://tiktok.com/@…"><button class="btn primary wide" id="go">${T('Cari akun', 'Find account')}</button><div class="status" id="st"></div><div id="out"></div></div>`;
  const st = $('#st', b), out = $('#out', b);
  $('#go', b).onclick = async () => {
    say(st, T('Mencari…', 'Searching…')); out.innerHTML = '';
    try {
      const r = await findTikTok($('#u', b).value); if (!r.ok) return say(st, r.message, 'err'); say(st, T('Akun ditemukan', 'Account found'), 'ok');
      const p = r.profile, c = p.counts || {}, f = p.flags || {}, cr = p.createdAt && p.createdAt.value ? fDate(p.createdAt.value) + (p.createdAt.source === 'derived' ? T(' (perkiraan)', ' (estimated)') : '') : '-';
      out.innerHTML = `<div class="rr" style="border:0">${p.avatarUrl ? `<img class="av" src="${esc(p.avatarUrl)}" referrerpolicy="no-referrer" alt="">` : ''}<div class="il"><b>${esc(p.displayName || p.username)}${f.verified ? ' ✓' : ''}</b><small class="muted">@${esc(p.username)}${p.isPrivate ? ' · ' + T('privat', 'private') : ''}</small></div></div>
        ${p.bio ? `<p class="muted" style="margin:6px 0">${esc(p.bio)}</p>` : ''}${kv(T('Dibuat', 'Created'), esc(cr))}${kv('Followers', fNum(c.followers))}${kv('Following', fNum(c.following))}${kv('Likes', fNum(c.totalLikes))}${kv('Video', fNum(c.videos))}${kv(T('Wilayah', 'Region'), esc((p.region || '-').toUpperCase()))}${kv(T('Bahasa', 'Language'), esc((p.language || '-').toUpperCase()))}${kv(T('Ganti nama', 'Nickname changed'), esc(fDate(p.nicknameChangedAt)))}${kv('User ID', esc(p.userId || '-'))}
        <div class="btnrow"><button class="btn" id="cu">${T('Salin ID', 'Copy ID')}</button><button class="btn" id="op">${T('Buka profil', 'Open profile')}</button>${p.avatarUrl ? `<button class="btn" id="av">${T('Avatar HD', 'HD avatar')}</button>` : ''}</div>`;
      $('#cu', out).onclick = () => copy(String(p.userId || '')); $('#op', out).onclick = () => openUrl(p.profileUrl || 'https://www.tiktok.com/@' + p.username);
      if ($('#av', out)) $('#av', out).onclick = () => nativeDl(p.avatarUrl, 'tiktok-' + p.username, extOf(p.avatarUrl, 'jpg'), 'https://www.tiktok.com/');
    } catch (e) { say(st, e.message || String(e), 'err'); }
  };
}

/* =============== 7. Pencarian adegan anime (trace.moe) =============== */
const mmss = s => { if (typeof s !== 'number' || isNaN(s) || s < 0) return '00:00'; return String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(Math.floor(s % 60)).padStart(2, '0'); };
async function traceAnime(input) {
  let res;
  if (typeof input === 'string') {
    const u = input.trim(); if (!/^https?:\/\//i.test(u)) throw new Error(T('URL gambar harus diawali http:// atau https://', 'Image URL must start with http:// or https://'));
    const r = await http({ url: 'https://api.trace.moe/search?anilistInfo&url=' + encodeURIComponent(u), timeout: 60000 });
    if (r.status === 429) throw new Error(T('Batas pencarian tercapai. Tunggu semenit.', 'Search limit reached. Wait a minute.'));
    try { res = JSON.parse(r.text); } catch (_) { throw new Error('HTTP ' + r.status); }
  } else {
    const fd = new FormData(); fd.append('image', input);
    const r = await fetch('https://api.trace.moe/search?anilistInfo', { method: 'POST', body: fd });
    if (r.status === 429) throw new Error(T('Batas pencarian tercapai. Tunggu semenit.', 'Search limit reached. Wait a minute.'));
    if (r.status === 413) throw new Error(T('Gambar terlalu besar (maks 25 MB).', 'Image too large (max 25 MB).'));
    res = await r.json();
  }
  if (res && res.error) throw new Error(res.error);
  return (Array.isArray(res && res.result) ? res.result : []).filter(i => (i.similarity || 0) >= .7).sort((a, b) => b.similarity - a.similarity).slice(0, 5).map(i => {
    const al = i.anilist || {}, t = al.title || {};
    return { title: t.english || t.romaji || t.native || i.filename || 'Unknown', native: t.native || '', ep: i.episode != null ? i.episode : 'N/A', time: mmss(i.from) + ' – ' + mmss(i.to), sim: ((i.similarity || 0) * 100).toFixed(1) + '%', video: i.video, image: i.image, ani: al.siteUrl || (al.id ? 'https://anilist.co/anime/' + al.id : ''), mal: al.idMal ? 'https://myanimelist.net/anime/' + al.idMal : '' };
  });
}
function vAnime(b) {
  b.innerHTML = `<div class="panel"><p class="muted">${T('Lacak judul anime, episode, dan menit adegan dari sebuah gambar (trace.moe).', 'Trace the anime title, episode and scene time from an image (trace.moe).')}</p>
    <input class="fld" id="u" type="url" placeholder="https://…/gambar.jpg"><div class="btnrow"><button class="btn" id="fl">${T('Pilih gambar', 'Choose image')}</button><button class="btn primary" id="go">${T('Cari dari URL', 'Search URL')}</button></div><div class="status" id="st"></div></div><div id="out"></div>`;
  const st = $('#st', b), out = $('#out', b);
  const run = async inp => {
    say(st, T('Mencari adegan…', 'Searching scene…')); out.innerHTML = '';
    try {
      const L = await traceAnime(inp); say(st, L.length ? T('Ditemukan ', 'Found ') + L.length + T(' kecocokan', ' matches') : T('Tidak ada kecocokan ≥70%. Coba gambar yang lebih jelas.', 'No match ≥70%. Try a clearer frame.'), L.length ? 'ok' : 'err');
      out.innerHTML = L.map((m, i) => `<div class="panel" style="margin-top:10px">${m.image ? `<img class="pv" loading="lazy" src="${esc(m.image)}" alt="">` : ''}<b>${esc(m.title)}</b>${m.native ? `<div class="muted">${esc(m.native)}</div>` : ''}${kv('Episode', esc(m.ep))}${kv(T('Menit', 'Time'), esc(m.time))}${kv(T('Kemiripan', 'Similarity'), esc(m.sim))}<div class="btnrow">${m.video ? `<button class="btn" data-a="pv" data-i="${i}">${T('Pratinjau', 'Preview')}</button>` : ''}${m.ani ? `<button class="btn" data-a="an" data-i="${i}">AniList</button>` : ''}${m.mal ? `<button class="btn" data-a="ml" data-i="${i}">MAL</button>` : ''}</div><div id="pvb${i}"></div></div>`).join('');
      out.onclick = e => { const x = e.target.closest('button[data-a]'); if (!x) return; const m = L[+x.dataset.i];
        if (x.dataset.a === 'pv') $('#pvb' + x.dataset.i, out).innerHTML = `<video class="pv" controls autoplay muted playsinline src="${esc(m.video)}"></video>`; else openUrl(x.dataset.a === 'an' ? m.ani : m.mal); };
    } catch (e) { say(st, e.message || String(e), 'err'); }
  };
  $('#go', b).onclick = () => run($('#u', b).value); $('#fl', b).onclick = async () => { const f = (await pick('image/*'))[0]; if (f) run(f); };
}

/* =============== 8. Username Checker (sosial + domain) =============== */
const enc = encodeURIComponent;
const PLAT = [
  ['TikTok', 'Social Video', u => 'https://www.tiktok.com/@' + u, { tt: 1 }],
  ['YouTube', 'Video', u => 'https://www.youtube.com/@' + u, { chk: u => 'https://www.youtube.com/@' + enc(u) }],
  ['Telegram', 'Messaging', u => 'https://t.me/' + u, { chk: u => 'https://t.me/' + enc(u), has: /tgme_page_extra|tgme_page_title/ }],
  ['Snapchat', 'Social', u => 'https://www.snapchat.com/add/' + u, { chk: u => 'https://www.snapchat.com/add/' + enc(u) }],
  ['Spotify', 'Music', u => 'https://open.spotify.com/user/' + u, { chk: u => 'https://open.spotify.com/user/' + enc(u) }],
  ['SoundCloud', 'Music', u => 'https://soundcloud.com/' + u, { chk: u => 'https://soundcloud.com/' + enc(u) }],
  ['Twitch', 'Streaming', u => 'https://www.twitch.tv/' + u, { chk: u => 'https://decapi.me/twitch/id/' + enc(u), num: 1 }],
  ['Roblox', 'Gaming', u => 'https://www.roblox.com/user.aspx?username=' + u, { roblox: 1 }],
  ['Steam', 'Gaming', u => 'https://steamcommunity.com/id/' + u, { chk: u => 'https://steamcommunity.com/id/' + enc(u) + '/?xml=1', has: /<steamID64>/ }],
  ['Duolingo', 'Learning', u => 'https://www.duolingo.com/profile/' + u, { chk: u => 'https://www.duolingo.com/2017-06-30/users?username=' + enc(u), duo: 1 }],
  ['Wattpad', 'Writing', u => 'https://www.wattpad.com/user/' + u, { chk: u => 'https://www.wattpad.com/user/' + enc(u) }],
  ['Chess.com', 'Gaming', u => 'https://www.chess.com/member/' + u, { chk: u => 'https://api.chess.com/pub/player/' + enc(u) }],
  ['Linktree', 'Links', u => 'https://linktr.ee/' + u, { chk: u => 'https://linktr.ee/' + enc(u) }],
  ['Vimeo', 'Video', u => 'https://vimeo.com/' + u, { chk: u => 'https://vimeo.com/' + enc(u) }],
  ['GitHub', 'Dev', u => 'https://github.com/' + u, { chk: u => 'https://api.github.com/users/' + enc(u) }],
  ['GitLab', 'Dev', u => 'https://gitlab.com/' + u, { chk: u => 'https://gitlab.com/' + enc(u) }],
  ['Substack', 'Writing', u => 'https://' + u + '.substack.com', { chk: u => 'https://' + enc(u) + '.substack.com' }],
  ['Dailymotion', 'Video', u => 'https://www.dailymotion.com/' + u, { chk: u => 'https://api.dailymotion.com/user/' + enc(u) }],
  ['Blogspot', 'Blog', u => 'https://' + domLabel(u) + '.blogspot.com/', { chk: u => 'https://' + domLabel(u) + '.blogspot.com/' }],
  ['Behance', 'Design', u => 'https://www.behance.net/' + u, { chk: u => 'https://www.behance.net/' + enc(u) }]
].concat(['com', 'id', 'net', 'org', 'io', 'dev', 'me', 'xyz', 'app'].map(t => ['.' + t, 'Domain', u => 'https://' + domLabel(u) + '.' + t, { dom: t }]));
function domLabel(u) { return String(u || '').toLowerCase().replace(/[_.]+/g, '-').replace(/[^a-z0-9-]/g, '').replace(/^-+|-+$/g, ''); }
async function checkOne(p, u) {
  const o = p[3];
  try {
    if (o.tt) { const r = await findTikTok(u); if (r.ok) return 'taken'; if (r.notFound) return 'free'; return 'error'; }
    if (o.dom) {
      const l = domLabel(u); if (!l || l.length > 63) return 'error';
      const r = await http({ url: 'https://cloudflare-dns.com/dns-query?name=' + enc(l + '.' + o.dom) + '&type=NS', headers: { Accept: 'application/dns-json' } }), j = JSON.parse(r.text);
      if (j.Status === 0) return ((j.Answer || []).length || (j.Authority || []).length) ? 'taken' : 'free'; if (j.Status === 3) return 'free'; return 'error';
    }
    if (o.roblox) { const r = await http({ url: 'https://users.roblox.com/v1/usernames/users', method: 'POST', data: { usernames: [u], excludeBannedUsers: false } }); if (r.status !== 200) return 'error'; const j = JSON.parse(r.text); return j.data && j.data.length ? 'taken' : 'free'; }
    const r = await http({ url: o.chk(u) });
    if (r.status === 0) return 'error';
    if (o.num) return /^\d+$/.test((r.text || '').trim()) ? 'taken' : 'free';
    if (o.has) return o.has.test(r.text || '') ? 'taken' : 'free';
    if (o.duo) { const j = JSON.parse(r.text); return j.users && j.users.length ? 'taken' : 'free'; }
    if (r.status === 200) return 'taken'; if (r.status === 404 || r.status === 410) return 'free'; return 'error';
  } catch (_) { return 'error'; }
}
function vUsername(b) {
  b.innerHTML = `<div class="panel"><p class="muted">${T('Periksa ketersediaan username di 20 platform dan 9 domain populer.', 'Check username availability across 20 platforms and 9 popular domains.')}</p>
    <input class="fld" id="u" placeholder="username"><button class="btn primary wide" id="go">${T('Periksa', 'Check')}</button><div class="status" id="st"></div>
    <div class="tprog hidden" id="pg"><i></i></div>${seg('f', [['all', T('Semua', 'All')], ['free', T('Tersedia', 'Free')], ['taken', T('Dipakai', 'Taken')]])}<div id="out" class="list"></div></div>`;
  segs(b); const st = $('#st', b), out = $('#out', b), pg = $('#pg', b); let rows = [];
  const draw = () => { const f = segVal($('[data-k=f]', b)); out.innerHTML = rows.filter(r => f === 'all' || r.s === f).map(r => `<div class="hrow"><div class="il"><b>${esc(r.n)}</b><small class="muted">${esc(r.c)}</small></div><div class="acts"><span class="pill ${r.s}">${r.s === 'free' ? T('Tersedia', 'Free') : r.s === 'taken' ? T('Dipakai', 'Taken') : T('Error', 'Error')}</span><button data-u="${esc(r.u)}">↗</button></div></div>`).join(''); };
  $('[data-k=f]', b).addEventListener('change', draw); out.onclick = e => { const x = e.target.closest('button[data-u]'); if (x) openUrl(x.dataset.u); };
  $('#go', b).onclick = async () => {
    const u = $('#u', b).value.trim().replace(/^[@/]+/, ''); if (!u) return say(st, T('Isi username dulu', 'Enter a username first'), 'err');
    if (!wifiGuard()) return; rows = []; out.innerHTML = ''; pg.classList.remove('hidden'); $('#go', b).disabled = true; let done = 0; const tot = PLAT.length, q = PLAT.slice();
    const worker = async () => { while (q.length) { const p = q.shift(), s = await checkOne(p, u); rows.push({ n: p[0], c: p[1], s, u: p[2](u) }); done++; pg.firstChild.style.width = done / tot * 100 + '%'; say(st, done + ' / ' + tot); if (done % 4 === 0 || done === tot) draw(); } };
    await Promise.all([worker(), worker(), worker(), worker(), worker()]);
    rows.sort((a, c) => (a.s === 'free' ? 0 : a.s === 'taken' ? 1 : 2) - (c.s === 'free' ? 0 : c.s === 'taken' ? 1 : 2)); draw();
    say(st, T('Tersedia ', 'Free ') + rows.filter(r => r.s === 'free').length + ' · ' + T('Dipakai ', 'Taken ') + rows.filter(r => r.s === 'taken').length + (rows.some(r => r.s === 'error') ? ' · Error ' + rows.filter(r => r.s === 'error').length : ''), 'ok'); $('#go', b).disabled = false;
  };
}

/* =============== 9. Batch download =============== */
function vBatch(b) {
  b.innerHTML = `<div class="panel"><p class="muted">${T('Tempel beberapa tautan (satu per baris). Tiap tautan dianalisis lalu diunduh dengan scraper bawaan.', 'Paste several links (one per line). Each is analysed and downloaded with the built-in scraper.')}</p>
    <textarea class="fld" id="u" placeholder="https://…&#10;https://…"></textarea>${lbl(T('PILIH', 'PREFER'))}${seg('k', [['video', 'Video'], ['audio', T('Audio', 'Audio')]])}
    <div class="btnrow"><button class="btn" id="ps">${T('Tempel', 'Paste')}</button><button class="btn primary" id="go">${T('Mulai', 'Start')}</button></div><div class="status" id="st"></div><div id="out" class="list"></div></div>`;
  segs(b); const st = $('#st', b), out = $('#out', b);
  $('#ps', b).onclick = () => { const t = N ? N.paste() : ''; if (t) $('#u', b).value = ($('#u', b).value ? $('#u', b).value + '\n' : '') + t.trim(); };
  $('#go', b).onclick = async () => {
    const L = [...new Set(($('#u', b).value.match(/https?:\/\/[^\s]+/gi) || []).map(x => x.trim()))].slice(0, 30);
    if (!L.length) return say(st, T('Tidak ada tautan yang valid', 'No valid links'), 'err'); if (!wifiGuard()) return;
    const kind = segVal($('[data-k=k]', b)); out.innerHTML = L.map((u, i) => `<div class="hrow"><div class="il"><b>${esc(u.slice(0, 60))}</b><small class="muted" id="r${i}">${T('Menunggu…', 'Waiting…')}</small></div></div>`).join(''); $('#go', b).disabled = true; awake(true); let ok = 0;
    for (let i = 0; i < L.length; i++) {
      const s = $('#r' + i, out); say(st, (i + 1) + ' / ' + L.length);
      try {
        s.textContent = T('Menganalisis…', 'Analysing…'); const r = await E().run(L[i]);
        let it = r.items.find(x => x.kind === kind) || r.items[0]; if (it.lazy) it = await E().resolve(it); if (!it.url) throw new Error('URL kosong');
        const ext = it.ext || extOf(it.url, kind === 'audio' ? 'mp3' : 'mp4'), h = Object.assign({ 'User-Agent': E().UA }, it.h || {});
        const nm = stem(r.title || 'media') + (r.items.length > 1 ? ' - ' + stem(it.label || '') : '');
        const id = N ? N.dl(it.url, folder() + '/' + nm + '.' + ext, MIMES[ext] || 'application/octet-stream', Object.keys(h).map(k => k + ': ' + h[k]).join('\n')) : (window.open(it.url, '_blank'), 'web');
        if (!id) throw new Error('DownloadManager menolak' + (N.dlError ? ' — ' + N.dlError() : '')); s.textContent = T('Diunduh — ', 'Queued — ') + (r.pname || ''); s.className = 'ok'; ok++;
      } catch (e) { s.textContent = T('Gagal: ', 'Failed: ') + (e.message || e); s.className = 'bad'; }
    }
    awake(false); $('#go', b).disabled = false; say(st, T('Selesai — ', 'Done — ') + ok + ' / ' + L.length + T(' masuk antrean unduhan', ' queued'), ok ? 'ok' : 'err'); hap();
  };
}

/* =============== kerangka sub-halaman =============== */
const TOOLS = {
  audio: { t: () => T('Ekstrak audio', 'Video to Audio'), s: () => T('Video → WAV', 'Video → WAV'), v: vAudio },
  image: { t: () => T('Konversi gambar', 'Image Converter'), s: () => 'JPG · PNG · WebP', v: vImage },
  pdf: { t: () => T('Kompres PDF', 'Compress PDF'), s: () => T('Perkecil ukuran PDF', 'Reduce PDF size'), v: vPdf },
  sniffer: { t: () => 'Media Sniffer', s: () => T('Deteksi media halaman web', 'Detect media in a web page'), v: vSniffer },
  shortlink: { t: () => T('Resolver tautan', 'Link Resolver'), s: () => T('Tujuan akhir tautan pendek', 'Final URL of short links'), v: vLink },
  tiktok: { t: () => 'TikTok Finder', s: () => T('Info akun TikTok publik', 'Public TikTok account info'), v: vTiktok },
  anime: { t: () => T('Cari adegan anime', 'Anime Scene Search'), s: () => 'trace.moe', v: vAnime },
  username: { t: () => 'Username Checker', s: () => T('Sosial & domain', 'Social & domains'), v: vUsername },
  batch: { t: () => 'Batch download', s: () => T('Banyak tautan sekaligus', 'Many links at once'), v: vBatch }
};
let host = null, openId = null;
function ensureHost() {
  if (host) return host; const p = $('#p-tools'); host = document.createElement('div'); host.id = 'tv'; host.className = 'tv hidden';
  host.innerHTML = '<div class="tv-head"><button class="btn sm" id="tvBack" aria-label="Back">←</button><div><h3 id="tvT"></h3><small class="muted" id="tvS"></small></div></div><div id="tvB"></div>';
  p.appendChild(host); $('#tvBack', host).onclick = () => AT.close(); return host;
}
const AT = W.AT = {
  has: id => !!TOOLS[id],
  isOpen: () => !!openId,
  open(id) {
    const d = TOOLS[id]; if (!d) return false; const h = ensureHost(); freeUrls(); openId = id;
    $('#tvT', h).textContent = d.t(); $('#tvS', h).textContent = d.s(); const body = $('#tvB', h); body.innerHTML = ''; d.v(body);
    h.classList.remove('hidden'); $('#p-tools').classList.add('tools-open'); W.scrollTo(0, 0); return true;
  },
  close() { if (!openId) return false; openId = null; freeUrls(); awake(false); const h = ensureHost(); $('#tvB', h).innerHTML = ''; h.classList.add('hidden'); $('#p-tools').classList.remove('tools-open'); return true; }
};
})();
