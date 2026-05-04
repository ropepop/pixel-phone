#!/usr/bin/env node
import { mkdir, writeFile } from 'node:fs/promises';
import { Buffer } from 'node:buffer';

function argValue(name, fallback = '') {
  const index = process.argv.indexOf(name);
  if (index < 0 || index + 1 >= process.argv.length) return fallback;
  return process.argv[index + 1];
}

const url = argValue('--url', `https://ticket.jolkins.id.lv/?v=cdp-${Date.now()}`);
const outDir = argValue('--out-dir', '/tmp/ticket-cdp');
const label = argValue('--label', 'ticket');
const timeoutMs = Number(argValue('--timeout-ms', '25000'));
const settleMs = Number(argValue('--settle-ms', '0'));

async function newPage(targetUrl) {
  const response = await fetch(`http://127.0.0.1:9222/json/new?${encodeURIComponent(targetUrl)}`, { method: 'PUT' });
  if (!response.ok) throw new Error(`new page failed ${response.status}: ${await response.text()}`);
  const target = await response.json();
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true });
    ws.addEventListener('error', reject, { once: true });
  });
  let nextId = 0;
  const pending = new Map();
  const events = [];
  ws.addEventListener('message', (event) => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      const callbacks = pending.get(message.id);
      pending.delete(message.id);
      if (message.error) callbacks.reject(new Error(JSON.stringify(message.error)));
      else callbacks.resolve(message.result || {});
    } else if (message.method) {
      events.push(message);
      if (events.length > 800) events.shift();
    }
  });
  const send = (method, params = {}) => {
    const id = ++nextId;
    ws.send(JSON.stringify({ id, method, params }));
    return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
  };
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Network.enable');
  await send('Log.enable');
  return { target, ws, send, events };
}

async function evaluate(page, expression) {
  const result = await page.send('Runtime.evaluate', {
    expression,
    awaitPromise: true,
    returnByValue: true,
  });
  if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
  return result.result?.value;
}

const canvasProbeExpression = `(() => {
  const canvas = document.querySelector('canvas');
  const text = document.body ? document.body.innerText.slice(0, 1200) : '';
  const result = {
    href: location.href,
    title: document.title,
    readyState: document.readyState,
    text,
    canvas: null,
    health: null
  };
  if (!canvas || !canvas.width || !canvas.height) return result;
  const context = canvas.getContext('2d', { willReadFrequently: true });
  const width = canvas.width;
  const height = canvas.height;
  const sampleWidth = Math.min(96, width);
  const sampleHeight = Math.min(96, height);
  const x = Math.max(0, Math.floor((width - sampleWidth) / 2));
  const y = Math.max(0, Math.floor((height - sampleHeight) / 3));
  let mean = 0;
  let meanSq = 0;
  let alphaPixels = 0;
  const data = context.getImageData(x, y, sampleWidth, sampleHeight).data;
  for (let i = 0; i < data.length; i += 4) {
    if (data[i + 3] > 0) alphaPixels += 1;
    const luma = 0.2126 * data[i] + 0.7152 * data[i + 1] + 0.0722 * data[i + 2];
    mean += luma;
    meanSq += luma * luma;
  }
  const pixels = data.length / 4;
  mean = pixels ? mean / pixels : 0;
  const variance = pixels ? meanSq / pixels - mean * mean : 0;
  result.canvas = {
    width,
    height,
    sampleWidth,
    sampleHeight,
    mean: Math.round(mean * 1000) / 1000,
    variance: Math.round(variance * 1000) / 1000,
    alphaPercent: pixels ? Math.round(alphaPixels * 100000 / pixels) / 1000 : 0,
    looksDrawn: width > 0 && height > 0 && alphaPixels > pixels * 0.95 && variance > 20
  };
  return result;
})()`;

async function waitForFrame(page, name) {
  const start = Date.now();
  let last = null;
  while (Date.now() - start < timeoutMs) {
    last = await evaluate(page, canvasProbeExpression);
    if (last?.canvas?.looksDrawn) {
      return { ok: true, elapsedMs: Date.now() - start, probe: last };
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  return { ok: false, elapsedMs: Date.now() - start, probe: last, name };
}

async function saveEvidence(page, name, frameResult) {
  if (settleMs > 0 && frameResult?.ok) {
    await new Promise((resolve) => setTimeout(resolve, settleMs));
  }
  const pageJson = await evaluate(page, `fetch('/api/v1/health', { cache: 'no-store' }).then(r => r.json()).catch(error => ({error: String(error)}))`);
  const browserJson = await evaluate(page, `(async () => ({
    href: location.href,
    title: document.title,
    userAgent: navigator.userAgent,
    av1WebCodecs: Boolean(window.VideoDecoder),
    av1ConfigSupported: window.VideoDecoder ? await VideoDecoder.isConfigSupported({codec: 'av01.0.08M.08', codedWidth: 1080, codedHeight: 2424}).then(r => r.supported).catch(() => false) : false,
    navigation: performance.getEntriesByType('navigation')[0]?.toJSON?.() || null
  }))()`);
  await writeFile(`${outDir}/${name}-page.json`, JSON.stringify({ frameResult, browser: browserJson, health: pageJson }, null, 2));
  const screenshot = await page.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
  await writeFile(`${outDir}/${name}-page.png`, Buffer.from(screenshot.data, 'base64'));
  const canvasDataUrl = await evaluate(page, `(() => {
    const canvas = document.querySelector('canvas');
    return canvas && canvas.width && canvas.height ? canvas.toDataURL('image/png') : '';
  })()`);
  if (canvasDataUrl.startsWith('data:image/png;base64,')) {
    await writeFile(`${outDir}/${name}-canvas.png`, Buffer.from(canvasDataUrl.split(',')[1], 'base64'));
  }
}

await mkdir(outDir, { recursive: true });
const page = await newPage(url);
const initial = await waitForFrame(page, `${label}-initial`);
await saveEvidence(page, `${label}-initial`, initial);
await page.send('Page.reload', { ignoreCache: true });
const reload = await waitForFrame(page, `${label}-reload`);
await saveEvidence(page, `${label}-reload`, reload);
await writeFile(`${outDir}/${label}-summary.json`, JSON.stringify({ url, initial, reload }, null, 2));
page.ws.close();
await fetch(`http://127.0.0.1:9222/json/close/${page.target.id}`).catch(() => {});
console.log(JSON.stringify({ outDir, initial: initial.elapsedMs, initialOk: initial.ok, reload: reload.elapsedMs, reloadOk: reload.ok }, null, 2));
