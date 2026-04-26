import fs from "node:fs/promises";
import path from "node:path";
import { chromium } from "playwright";

const cwd = process.cwd();
const baseUrl = (process.env.BROWSER_SANITY_BASE_URL || process.env.SATIKSME_WEB_PUBLIC_BASE_URL || "https://satiksme-bot.jolkins.id.lv").replace(/\/+$/, "");
const outDir = process.env.BROWSER_SANITY_OUT_DIR || path.resolve(cwd, "output/browser-use/pixel-browser-sanity");
const reportFile = process.env.BROWSER_SANITY_REPORT_FILE || path.resolve(cwd, "output/pixel/satiksme-bot-browser-sanity.json");
const timeoutMs = Number(process.env.BROWSER_SANITY_TIMEOUT_MS || 15000);

await fs.mkdir(outDir, { recursive: true });
await fs.mkdir(path.dirname(reportFile), { recursive: true });

function scenarioUrl(base, suffix) {
  const normalizedBase = base.endsWith("/") ? base : `${base}/`;
  if (!suffix || suffix === "/") {
    return new URL(".", normalizedBase).toString();
  }
  return new URL(suffix.replace(/^\/+/, ""), normalizedBase).toString();
}

function eventLocation(location) {
  if (!location || typeof location !== "object") {
    return null;
  }
  return {
    url: location.url || "",
    lineNumber: location.lineNumber ?? null,
    columnNumber: location.columnNumber ?? null,
  };
}

function canonicalUrl(rawUrl) {
  try {
    const url = new URL(rawUrl);
    url.hash = "";
    return url.toString();
  } catch (_error) {
    return rawUrl || "";
  }
}

function procedureName(rawUrl) {
  try {
    const url = new URL(rawUrl);
    const match = url.pathname.match(/\/call\/([^/]+)$/);
    return match ? decodeURIComponent(match[1]) : "";
  } catch (_error) {
    return "";
  }
}

function prefixedProcedureUrl(rawUrl) {
  const procedure = procedureName(rawUrl);
  if (!procedure || procedure.startsWith("satiksmebot_")) {
    return "";
  }
  try {
    const url = new URL(rawUrl);
    url.pathname = url.pathname.replace(/\/call\/[^/]+$/, `/call/${encodeURIComponent(`satiksmebot_${procedure}`)}`);
    return canonicalUrl(url.toString());
  } catch (_error) {
    return "";
  }
}

function isAllowedNetworkEvent(event, successfulUrls) {
  const url = canonicalUrl(event.url || "");
  if (!url) {
    return false;
  }
  if (event.kind === "requestfailed" && /\/cdn-cgi\/rum(?:$|\?)/.test(url)) {
    return true;
  }
  if (event.kind === "response" && event.status === 404 && /\/favicon\.ico(?:$|\?)/.test(url)) {
    return true;
  }
  if (event.kind === "response" && event.status === 404) {
    const fallbackUrl = prefixedProcedureUrl(url);
    if (fallbackUrl && successfulUrls.has(fallbackUrl)) {
      return true;
    }
  }
  return false;
}

function isAllowedConsoleEvent(event, successfulUrls) {
  const type = String(event.type || "");
  if (type !== "error" && type !== "pageerror") {
    return true;
  }
  const locationUrl = canonicalUrl(event.location?.url || "");
  if (locationUrl && /\/favicon\.ico(?:$|\?)/.test(locationUrl)) {
    return true;
  }
  if (locationUrl) {
    const fallbackUrl = prefixedProcedureUrl(locationUrl);
    if (fallbackUrl && successfulUrls.has(fallbackUrl)) {
      return true;
    }
  }
  return false;
}

async function waitForBodyText(page, regex, timeout = timeoutMs) {
  await page.waitForFunction(
    ({ source, flags }) => new RegExp(source, flags).test(document.body?.innerText || ""),
    { source: regex.source, flags: regex.flags },
    { timeout },
  );
}

async function readBodyText(page) {
  return page.locator("body").innerText().catch(() => "");
}

const scenarios = [
  {
    key: "homeDesktop",
    urlPath: "/",
    screenshot: "home-desktop.png",
    viewport: { width: 1440, height: 1100 },
    async check(page) {
      await page.waitForSelector("text=Satiksmes mape", { timeout: timeoutMs });
      await page.waitForSelector(".leaflet-container", { timeout: timeoutMs });
      await page.waitForSelector("text=Izvēlētā pietura", { timeout: timeoutMs });
      await page.waitForSelector("text=Jaunākie ziņojumi", { timeout: timeoutMs });
      await waitForBodyText(page, /Notiekošais/i);
      return page.evaluate(() => ({
        headingCount: document.querySelectorAll("h1").length,
        leafletCount: document.querySelectorAll(".leaflet-container").length,
        stopPanelVisible: document.body?.innerText.includes("Izvēlētā pietura") || false,
      }));
    },
  },
  {
    key: "incidentsDesktop",
    urlPath: "/incidents",
    screenshot: "incidents-desktop.png",
    viewport: { width: 1440, height: 1100 },
    async check(page) {
      await page.waitForSelector("text=Kontroles plūsma", { timeout: timeoutMs });
      await page.waitForTimeout(1500);
      await page.waitForFunction(
        () => {
          const text = document.body?.innerText || "";
          return (
            /Pēdējās 24 stundas/i.test(text)
            && (
              /Pēdējo 24 stundu incidentu nav\./i.test(text)
              || /Izvēlies incidentu/i.test(text)
              || document.querySelectorAll(".incident-card").length > 0
            )
          );
        },
        undefined,
        { timeout: timeoutMs },
      );
      return page.evaluate(() => ({
        incidentCards: document.querySelectorAll(".incident-card").length,
        emptyStateVisible: /Pēdējo 24 stundu incidentu nav\./i.test(document.body?.innerText || ""),
      }));
    },
  },
  {
    key: "appMobile",
    urlPath: "/app",
    screenshot: "app-mobile.png",
    viewport: { width: 390, height: 844 },
    isMobile: true,
    async check(page) {
      await page.waitForSelector("text=Satiksmes mape", { timeout: timeoutMs });
      await page.waitForSelector(".leaflet-container", { timeout: timeoutMs });
      await page.waitForTimeout(2000);
      await waitForBodyText(page, /Telegram/i);
      const bodyText = await readBodyText(page);
      if (/Telegram sesija aktīva/i.test(bodyText)) {
        throw new Error("mini app unexpectedly booted into an authenticated state");
      }
      const reportButtons = await page.locator('[data-action="report-stop"], [data-action="report-vehicle"]').count();
      if (reportButtons > 0) {
        throw new Error("mini app exposed report actions without Telegram auth");
      }
      return {
        readOnlyTelegramMessageVisible: /Atver Telegram/i.test(bodyText),
        reportButtons,
      };
    },
  },
  {
    key: "incidentsMobile",
    urlPath: "/incidents",
    screenshot: "incidents-mobile.png",
    viewport: { width: 390, height: 844 },
    isMobile: true,
    async check(page) {
      await page.waitForSelector("text=Kontroles plūsma", { timeout: timeoutMs });
      await page.waitForTimeout(1500);
      await page.waitForFunction(
        () => {
          const text = document.body?.innerText || "";
          return (
            /Pēdējās 24 stundas/i.test(text)
            && (
              /Pēdējo 24 stundu incidentu nav\./i.test(text)
              || /Izvēlies incidentu/i.test(text)
              || document.querySelectorAll(".incident-card").length > 0
            )
          );
        },
        undefined,
        { timeout: timeoutMs },
      );
      return page.evaluate(() => ({
        incidentCards: document.querySelectorAll(".incident-card").length,
        emptyStateVisible: /Pēdējo 24 stundu incidentu nav\./i.test(document.body?.innerText || ""),
      }));
    },
  },
];

async function runScenario(browser, scenario) {
  const consoleEvents = [];
  const networkEvents = [];
  const successfulUrls = new Set();
  const targetUrl = scenarioUrl(baseUrl, scenario.urlPath);
  const context = await browser.newContext({
    viewport: scenario.viewport,
    isMobile: scenario.isMobile || false,
  });
  const page = await context.newPage();

  page.on("console", (message) => {
    consoleEvents.push({
      type: message.type(),
      text: message.text(),
      location: eventLocation(message.location()),
    });
  });

  page.on("pageerror", (error) => {
    consoleEvents.push({
      type: "pageerror",
      text: String(error),
      location: null,
    });
  });

  page.on("requestfailed", (request) => {
    networkEvents.push({
      kind: "requestfailed",
      method: request.method(),
      url: request.url(),
      failure: request.failure()?.errorText || "unknown failure",
    });
  });

  page.on("response", (response) => {
    const event = {
      kind: "response",
      method: response.request().method(),
      status: response.status(),
      url: response.url(),
    };
    networkEvents.push(event);
    if (response.status() < 400) {
      successfulUrls.add(canonicalUrl(response.url()));
    }
  });

  let details = {};
  let screenshotPath = path.join(outDir, scenario.screenshot);
  let status = "PASS";
  let failure = "";

  try {
    const response = await page.goto(targetUrl, { waitUntil: "domcontentloaded", timeout: timeoutMs });
    if (!response || response.status() !== 200) {
      throw new Error(`navigation returned ${response ? response.status() : "no response"}`);
    }
    details = await scenario.check(page);
    await page.screenshot({ path: screenshotPath, fullPage: true });
  } catch (error) {
    status = "FAIL";
    failure = error instanceof Error ? error.message : String(error);
    await page.screenshot({ path: screenshotPath, fullPage: true }).catch(() => {});
  } finally {
    await context.close();
  }

  const allowedNetworkEvents = [];
  const blockingNetworkEvents = [];
  for (const event of networkEvents) {
    if (event.kind === "response" && event.status < 400) {
      continue;
    }
    if (isAllowedNetworkEvent(event, successfulUrls)) {
      allowedNetworkEvents.push(event);
    } else {
      blockingNetworkEvents.push(event);
    }
  }

  const allowedConsoleEvents = [];
  const blockingConsoleEvents = [];
  for (const event of consoleEvents) {
    if (isAllowedConsoleEvent(event, successfulUrls)) {
      if (event.type === "error" || event.type === "pageerror") {
        allowedConsoleEvents.push(event);
      }
      continue;
    }
    if (event.type === "error" || event.type === "pageerror") {
      blockingConsoleEvents.push(event);
    }
  }

  if (status === "PASS" && (blockingNetworkEvents.length > 0 || blockingConsoleEvents.length > 0)) {
    status = "FAIL";
    const issues = [];
    if (blockingNetworkEvents.length > 0) {
      issues.push(`${blockingNetworkEvents.length} blocking network issue(s)`);
    }
    if (blockingConsoleEvents.length > 0) {
      issues.push(`${blockingConsoleEvents.length} blocking console issue(s)`);
    }
    failure = issues.join(", ");
  }

  return {
    key: scenario.key,
    url: targetUrl,
    viewport: scenario.viewport,
    status,
    failure,
    details,
    screenshotPath,
    consoleEvents,
    networkEvents,
    allowedConsoleEvents,
    allowedNetworkEvents,
    blockingConsoleEvents,
    blockingNetworkEvents,
  };
}

const browser = await chromium.launch({ headless: true });
const generatedAtUtc = new Date().toISOString();
const scenarioResults = [];
let success = true;
let topLevelFailure = "";

try {
  for (const scenario of scenarios) {
    const result = await runScenario(browser, scenario);
    scenarioResults.push(result);
    if (result.status !== "PASS") {
      success = false;
    }
  }
} catch (error) {
  success = false;
  topLevelFailure = error instanceof Error ? error.message : String(error);
} finally {
  await browser.close();
}

const reportPayload = {
  generatedAtUtc,
  success,
  baseUrl,
  topLevelFailure,
  scenarios: scenarioResults,
};

await fs.writeFile(reportFile, `${JSON.stringify(reportPayload, null, 2)}\n`, "utf8");
await fs.writeFile(path.join(outDir, "console.json"), `${JSON.stringify(scenarioResults.map(({ key, consoleEvents, allowedConsoleEvents, blockingConsoleEvents }) => ({
  key,
  consoleEvents,
  allowedConsoleEvents,
  blockingConsoleEvents,
})), null, 2)}\n`, "utf8");
await fs.writeFile(path.join(outDir, "network.json"), `${JSON.stringify(scenarioResults.map(({ key, networkEvents, allowedNetworkEvents, blockingNetworkEvents }) => ({
  key,
  networkEvents,
  allowedNetworkEvents,
  blockingNetworkEvents,
})), null, 2)}\n`, "utf8");

if (!success) {
  const failures = scenarioResults.filter((scenario) => scenario.status !== "PASS").map((scenario) => `${scenario.key}: ${scenario.failure}`);
  if (topLevelFailure) {
    failures.unshift(`top-level: ${topLevelFailure}`);
  }
  console.error(failures.join("\n"));
  process.exit(1);
}

console.log(`browser sanity ok (${reportFile})`);
