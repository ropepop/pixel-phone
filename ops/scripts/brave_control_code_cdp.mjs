#!/usr/bin/env node
import { mkdir, writeFile } from 'node:fs/promises';
import { Buffer } from 'node:buffer';
import { pathToFileURL } from 'node:url';
import { evaluatePublicViewerTicketHealth } from './brave_ticket_cdp.mjs';

function argValue(name, fallback = '') {
  const index = process.argv.indexOf(name);
  if (index < 0 || index + 1 >= process.argv.length) return fallback;
  return process.argv[index + 1];
}

const url = argValue('--url', `https://ticket.jolkins.id.lv/?v=control-${Date.now()}`);
const outDir = argValue('--out-dir', '/tmp/ticket-control-code-cdp');
const digits = String(argValue('--digits', process.env.CONTROL_CODE_DIGITS || '')).replace(/\D/g, '');
const timeoutMs = Number(argValue('--timeout-ms', '90000'));
const pollMs = Number(argValue('--poll-ms', '200'));
const closeResult = argValue('--close-result', 'true') !== 'false';

if (digits.length < 2 || digits.length > 8) {
  console.error('Provide --digits with 2-8 numeric digits, or CONTROL_CODE_DIGITS.');
  process.exit(2);
}

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
  const text = document.body ? document.body.innerText.slice(0, 1600) : '';
  const debug = window.ticketStreamDebug || null;
  const result = { href: location.href, title: document.title, readyState: document.readyState, text, debug, canvas: null };
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
    mean: Math.round(mean * 1000) / 1000,
    variance: Math.round(variance * 1000) / 1000,
    alphaPercent: pixels ? Math.round(alphaPixels * 100000 / pixels) / 1000 : 0,
    looksDrawn: width > 0 && height > 0 && alphaPixels > pixels * 0.95 && variance > 20,
  };
  return result;
})()`;

async function health(page) {
  return evaluate(page, `fetch('/api/v1/health', { cache: 'no-store' }).then(r => r.json()).catch(error => ({error: String(error)}))`);
}

function phoneHealth(publicHealth) {
  if (publicHealth?.phoneFull?.data) return publicHealth.phoneFull.data;
  if (publicHealth?.phoneFull) return publicHealth.phoneFull;
  const raw = publicHealth?.state?.phone?.healthJson || publicHealth?.state?.phone?.HealthJSON || '';
  if (raw && typeof raw === 'string') {
    try {
      const parsed = JSON.parse(raw);
      return parsed?.data || parsed || {};
    } catch (_) {
      return {};
    }
  }
  return {};
}

async function pageState(page) {
  const frameResult = {
    ok: false,
    elapsedMs: 0,
    probe: await evaluate(page, canvasProbeExpression),
  };
  frameResult.ok = frameResult.probe?.canvas?.looksDrawn === true;
  const publicHealth = await health(page);
  const phone = phoneHealth(publicHealth);
  return {
    at: new Date().toISOString(),
    frameResult,
    health: publicHealth,
    publicTicket: evaluatePublicViewerTicketHealth(frameResult, publicHealth),
    phoneControlCode: phone.controlCodeRequest || null,
    phoneTicketState: phone.ticketState || null,
    phoneViviState: phone.viviState || null,
    streamDebug: frameResult.probe?.debug || null,
    dom: await evaluate(page, `(() => ({
      bodyClass: document.body?.className || '',
      requestButtonDisabled: document.querySelector('#requestControlCode')?.disabled || false,
      dialogHidden: document.querySelector('#controlCodeDialog')?.hidden ?? null,
      resultHidden: document.querySelector('#controlCodeResultArea')?.hidden ?? null,
      resultStatus: document.querySelector('#controlCodeResultArea')?.dataset?.status || '',
      resultImageHidden: document.querySelector('#controlCodeResultImage')?.hidden ?? null,
      resultImageHasSrc: Boolean(document.querySelector('#controlCodeResultImage')?.getAttribute('src')),
      resultText: document.querySelector('#controlCodeResultStatus')?.textContent || '',
      requestState: document.querySelector('#codeRequestState')?.textContent || '',
      requestDetail: document.querySelector('#codeRequestDetail')?.textContent || ''
    }))()`),
  };
}

async function saveEvidence(page, name, extra = {}) {
  const state = await pageState(page);
  await writeFile(`${outDir}/${name}.json`, JSON.stringify({ ...state, ...extra }, null, 2));
  const screenshot = await page.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
  await writeFile(`${outDir}/${name}.png`, Buffer.from(screenshot.data, 'base64'));
  const canvasDataUrl = await evaluate(page, `(() => {
    const canvas = document.querySelector('canvas');
    return canvas && canvas.width && canvas.height ? canvas.toDataURL('image/png') : '';
  })()`);
  if (canvasDataUrl.startsWith('data:image/png;base64,')) {
    await writeFile(`${outDir}/${name}-canvas.png`, Buffer.from(canvasDataUrl.split(',')[1], 'base64'));
  }
  const resultDataUrl = await evaluate(page, `(() => {
    const img = document.querySelector('#controlCodeResultImage');
    return img && !img.hidden ? img.getAttribute('src') || '' : '';
  })()`);
  if (resultDataUrl.startsWith('data:image/png;base64,')) {
    await writeFile(`${outDir}/${name}-result.png`, Buffer.from(resultDataUrl.split(',')[1], 'base64'));
  }
  return state;
}

async function waitUntil(page, label, predicate, timeout = timeoutMs) {
  const start = Date.now();
  let last = null;
  while (Date.now() - start < timeout) {
    last = await pageState(page);
    if (predicate(last)) return { ok: true, elapsedMs: Date.now() - start, state: last };
    await new Promise((resolve) => setTimeout(resolve, pollMs));
  }
  return { ok: false, elapsedMs: Date.now() - start, state: last, label };
}

async function click(page, selector) {
  return evaluate(page, `(() => {
    const element = document.querySelector(${JSON.stringify(selector)});
    if (!element) throw new Error(${JSON.stringify(`${selector} missing`)});
    element.click();
    return true;
  })()`);
}

async function fillAndSubmit(page, value) {
  return evaluate(page, `(() => {
    const input = document.querySelector('#controlCodeDigits');
    const form = document.querySelector('#controlCodeForm');
    if (!input || !form) throw new Error('control-code form missing');
    input.value = ${JSON.stringify(value)};
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    return true;
  })()`);
}

async function run() {
  const startedAt = Date.now();
  const timings = {};
  await mkdir(outDir, { recursive: true });
  const page = await newPage(url);
  try {
    const live = await waitUntil(page, 'live-before-request', (state) => state.publicTicket.ok, timeoutMs);
    timings.liveReadyMs = live.elapsedMs;
    await saveEvidence(page, '01-live-before-request');
    if (!live.ok) throw new Error(`live ticket did not become ready: ${live.state?.publicTicket?.failure || 'timeout'}`);

    await click(page, '#requestControlCode');
    const dialog = await waitUntil(page, 'dialog-open', (state) => state.dom.dialogHidden === false, 5000);
    timings.dialogOpenMs = Date.now() - startedAt;
    await saveEvidence(page, '02-dialog-open');
    if (!dialog.ok) throw new Error('control-code dialog did not open');

    await fillAndSubmit(page, digits);
    await saveEvidence(page, '03-request-submitted');
    timings.requestSubmittedMs = Date.now() - startedAt;

    const result = await waitUntil(page, 'result-visible', (state) => (
      state.dom.resultHidden === false &&
      state.dom.resultStatus === 'succeeded' &&
      state.dom.resultImageHidden === false &&
      state.dom.resultImageHasSrc === true &&
      state.phoneControlCode?.browserCaptureReason === 'browser_capture_confirmed'
    ), timeoutMs);
    timings.resultVisibleMs = Date.now() - startedAt;
    await saveEvidence(page, '04-result-visible', { wait: result });
    if (!result.ok) {
      throw new Error(`result image did not become visible with browser capture ack: ${result.state?.dom?.resultText || result.state?.phoneControlCode?.reason || 'timeout'}`);
    }

    const cleanup = await waitUntil(page, 'cleanup-live-behind-result', (state) => (
      state.phoneTicketState?.state === 'live' &&
      state.phoneViviState?.state === 'TICKET_DETAIL' &&
      state.phoneControlCode?.status === 'succeeded'
    ), 45000);
    timings.cleanupLiveMs = cleanup.elapsedMs;
    await saveEvidence(page, '05-cleanup-live-behind-result', { wait: cleanup });
    if (!cleanup.ok) throw new Error(`ticket did not return live after capture: ${cleanup.state?.phoneViviState?.state || 'timeout'}`);

    if (closeResult) {
      await click(page, '#closeControlCodeResult');
      const closed = await waitUntil(page, 'result-closed-ticket-live', (state) => (
        state.dom.resultHidden === true &&
        state.publicTicket.ok
      ), 10000);
      timings.closedMs = closed.elapsedMs;
      await saveEvidence(page, '06-result-closed-ticket-live', { wait: closed });
      if (!closed.ok) throw new Error(`ticket was not live after closing result: ${closed.state?.publicTicket?.failure || 'timeout'}`);
    }

    const finalState = await pageState(page);
    const summary = {
      ok: true,
      url,
      outDir,
      timings,
      finalControlCode: finalState.phoneControlCode,
      finalTicket: finalState.publicTicket,
      finalStreamDebug: finalState.streamDebug,
    };
    await writeFile(`${outDir}/summary.json`, JSON.stringify(summary, null, 2));
    console.log(JSON.stringify(summary, null, 2));
  } finally {
    page.ws.close();
    await fetch(`http://127.0.0.1:9222/json/close/${page.target.id}`).catch(() => {});
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  run().catch(async (error) => {
    try {
      await mkdir(outDir, { recursive: true });
      await writeFile(`${outDir}/summary.json`, JSON.stringify({ ok: false, error: error.message || String(error), outDir }, null, 2));
    } catch (_) {}
    console.error(error);
    process.exitCode = 1;
  });
}
