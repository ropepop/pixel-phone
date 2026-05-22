#!/usr/bin/env node
import { mkdir, writeFile } from 'node:fs/promises';
import { Buffer } from 'node:buffer';
import { pathToFileURL } from 'node:url';

function argValue(name, fallback = '') {
  const index = process.argv.indexOf(name);
  if (index < 0 || index + 1 >= process.argv.length) return fallback;
  return process.argv[index + 1];
}

const url = argValue('--url', `https://ticket.jolkins.id.lv/?v=cdp-${Date.now()}`);
const outDir = argValue('--out-dir', '/tmp/ticket-cdp');
const label = argValue('--label', 'ticket');
const timeoutMs = Number(argValue('--timeout-ms', '15000'));
const settleMs = Number(argValue('--settle-ms', '0'));
const pollMs = Number(argValue('--poll-ms', '500'));

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
    await new Promise((resolve) => setTimeout(resolve, pollMs));
  }
  return { ok: false, elapsedMs: Date.now() - start, probe: last, name };
}

async function waitForPublicTicket(page, name) {
  const start = Date.now();
  let lastFrame = null;
  let lastHealth = null;
  let lastPublicTicket = null;
  while (Date.now() - start < timeoutMs) {
    lastFrame = await evaluate(page, canvasProbeExpression);
    const frameResult = {
      ok: lastFrame?.canvas?.looksDrawn === true,
      elapsedMs: Date.now() - start,
      probe: lastFrame,
      name,
    };
    lastHealth = await evaluate(page, `fetch('/api/v1/health', { cache: 'no-store' }).then(r => r.json()).catch(error => ({error: String(error)}))`);
    lastPublicTicket = evaluatePublicViewerTicketHealth(frameResult, lastHealth);
    if (lastPublicTicket.ok) {
      return { ok: true, elapsedMs: Date.now() - start, frameResult, health: lastHealth, publicTicket: lastPublicTicket };
    }
    await new Promise((resolve) => setTimeout(resolve, pollMs));
  }
  const frameResult = {
    ok: lastFrame?.canvas?.looksDrawn === true,
    elapsedMs: Date.now() - start,
    probe: lastFrame,
    name,
  };
  return {
    ok: false,
    elapsedMs: Date.now() - start,
    frameResult,
    health: lastHealth,
    publicTicket: lastPublicTicket || evaluatePublicViewerTicketHealth(frameResult, lastHealth || {}),
  };
}

async function saveEvidence(page, name, verificationResult) {
  if (settleMs > 0 && verificationResult?.ok) {
    await new Promise((resolve) => setTimeout(resolve, settleMs));
  }
  const pageJson = verificationResult?.health || await evaluate(page, `fetch('/api/v1/health', { cache: 'no-store' }).then(r => r.json()).catch(error => ({error: String(error)}))`);
  const browserJson = await evaluate(page, `(async () => ({
    href: location.href,
    title: document.title,
    userAgent: navigator.userAgent,
    av1WebCodecs: Boolean(window.VideoDecoder),
    av1ConfigSupported: window.VideoDecoder ? await VideoDecoder.isConfigSupported({codec: 'av01.0.08M.08', codedWidth: 1080, codedHeight: 2424}).then(r => r.supported).catch(() => false) : false,
    navigation: performance.getEntriesByType('navigation')[0]?.toJSON?.() || null
  }))()`);
  const publicTicket = verificationResult?.publicTicket || evaluatePublicViewerTicketHealth(verificationResult?.frameResult, pageJson);
  await writeFile(`${outDir}/${name}-page.json`, JSON.stringify({ verificationResult, publicTicket, browser: browserJson, health: pageJson }, null, 2));
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

function valueAt(obj, path) {
  return path.split('.').reduce((current, key) => (current && typeof current === 'object' ? current[key] : undefined), obj);
}

function boolValue(value) {
  return value === true || value === 'true';
}

function numberValue(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function failIf(reasons, condition, reason) {
  if (condition) reasons.push(reason);
}

function parsePhoneHealthJson(health) {
  const raw = health?.state?.phone?.healthJson || health?.state?.phone?.HealthJSON || '';
  if (!raw || typeof raw !== 'string') return null;
  try {
    const parsed = JSON.parse(raw);
    return parsed?.data || parsed || null;
  } catch (_) {
    return null;
  }
}

export function evaluateRootPublicTicketHealth(health) {
  const reasons = [];
  const pixel = health?.phoneFull?.data || health?.phoneFull || health?.data || parsePhoneHealthJson(health) || health || {};
  const relay = health?.directStream || {};
  const phone = health?.phone || {};
  const visibleAgo = numberValue(valueAt(pixel, 'visibleFrame.lastFrameAgoMillis'));
  const relayFrameAgo = numberValue(relay.lastFrameAgoMillis);
  const streamEpoch = numberValue(relay.streamEpoch);

  failIf(reasons, pixel.sessionState !== 'live', `pixel.sessionState=${pixel.sessionState ?? 'missing'}`);
  failIf(reasons, pixel.streamActive !== true, `pixel.streamActive=${pixel.streamActive ?? 'missing'}`);
  failIf(reasons, pixel.streamVerdict !== 'live', `pixel.streamVerdict=${pixel.streamVerdict ?? 'missing'}`);
  failIf(reasons, valueAt(pixel, 'ticketState.state') !== 'live', `pixel.ticketState.state=${valueAt(pixel, 'ticketState.state') ?? 'missing'}`);
  failIf(reasons, valueAt(pixel, 'viviState.state') !== 'TICKET_DETAIL', `pixel.viviState.state=${valueAt(pixel, 'viviState.state') ?? 'missing'}`);
  failIf(reasons, visibleAgo === null || visibleAgo > 1500, `pixel.visibleFrame.lastFrameAgoMillis=${visibleAgo ?? 'missing'}`);
  failIf(reasons, valueAt(pixel, 'hardwareH264.active') !== true, `pixel.hardwareH264.active=${valueAt(pixel, 'hardwareH264.active') ?? 'missing'}`);
  failIf(reasons, valueAt(pixel, 'hardwareH264.state') !== 'active', `pixel.hardwareH264.state=${valueAt(pixel, 'hardwareH264.state') ?? 'missing'}`);

  const phoneConnected = boolValue(phone.connected) || boolValue(phone.Connected) || boolValue(relay.phoneConnected);
  failIf(reasons, !phoneConnected, `relay.phoneConnected=${relay.phoneConnected ?? phone.connected ?? phone.Connected ?? 'missing'}`);
  failIf(reasons, numberValue(relay.activeVideoClients) === null || numberValue(relay.activeVideoClients) < 1, `relay.directStream.activeVideoClients=${relay.activeVideoClients ?? 'missing'}`);
  failIf(reasons, !relay.codec || !relay.transport || streamEpoch === null || streamEpoch <= 0, 'relay.directStream.configured=false');
  failIf(reasons, relayFrameAgo === null || relayFrameAgo > 1500, `relay.directStream.lastFrameAgoMillis=${relayFrameAgo ?? 'missing'}`);

  return { ok: reasons.length === 0, reasons };
}

export function evaluatePublicViewerTicketHealth(frameResult, health) {
  const visualOk = frameResult?.ok === true && frameResult?.probe?.canvas?.looksDrawn === true;
  const root = evaluateRootPublicTicketHealth(health);
  let failure = '';
  if (!visualOk && !root.ok) {
    failure = 'public_ticket_unavailable';
  } else if (!visualOk) {
    failure = 'public_ticket_visual_missing';
  } else if (!root.ok) {
    failure = 'public_ticket_split_brain';
  }
  return {
    scope: 'public_viewer_ticket_health',
    ok: visualOk && root.ok,
    visualOk,
    rootHealthOk: root.ok,
    failure,
    reasons: root.reasons,
  };
}

export function evaluatePublicTicketHealth(frameResult, health) {
  return evaluatePublicViewerTicketHealth(frameResult, health);
}

async function run() {
  await mkdir(outDir, { recursive: true });
  const page = await newPage(url);
  const initial = await waitForPublicTicket(page, `${label}-initial`);
  await saveEvidence(page, `${label}-initial`, initial);
  await page.send('Page.reload', { ignoreCache: true });
  const reload = await waitForPublicTicket(page, `${label}-reload`);
  await saveEvidence(page, `${label}-reload`, reload);
  const initialPublicTicket = initial.publicTicket;
  const reloadPublicTicket = reload.publicTicket;
  const summary = { url, initial, reload, initialPublicTicket, reloadPublicTicket };
  await writeFile(`${outDir}/${label}-summary.json`, JSON.stringify(summary, null, 2));
  page.ws.close();
  await fetch(`http://127.0.0.1:9222/json/close/${page.target.id}`).catch(() => {});
  console.log(JSON.stringify({
    outDir,
    initial: initial.elapsedMs,
    initialOk: initialPublicTicket.ok,
    reload: reload.elapsedMs,
    reloadOk: reloadPublicTicket.ok,
    initialFailure: initialPublicTicket.failure,
    reloadFailure: reloadPublicTicket.failure,
    initialReasons: initialPublicTicket.reasons,
    reloadReasons: reloadPublicTicket.reasons,
  }, null, 2));
  if (!initialPublicTicket.ok || !reloadPublicTicket.ok) {
    process.exitCode = 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  run().catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
}
