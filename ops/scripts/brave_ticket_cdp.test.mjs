import test from 'node:test';
import assert from 'node:assert/strict';

import { evaluatePublicTicketHealth, evaluatePublicViewerTicketHealth } from './brave_ticket_cdp.mjs';

const visualOk = { ok: true, probe: { canvas: { looksDrawn: true } } };
const visualFail = { ok: false, probe: { canvas: { looksDrawn: false } } };

function healthyRoot(overrides = {}) {
  return {
    phone: { connected: true },
    directStream: {
      activeVideoClients: 1,
      codec: 'avc1.42C028',
      transport: 'hardware-h264-annexb',
      streamEpoch: 42,
      lastFrameAgoMillis: 400,
      phoneConnected: true,
    },
    phoneFull: {
      sessionState: 'live',
      streamActive: true,
      streamVerdict: 'live',
      visibleFrame: { lastFrameAgoMillis: 300 },
      ticketState: { state: 'live' },
      viviState: { state: 'TICKET_DETAIL', source: 'root' },
      hardwareH264: { active: true, state: 'active' },
      streamPipeline: { streamConfigured: true },
      ...overrides,
    },
  };
}

test('visual and root health pass together', () => {
  const result = evaluatePublicViewerTicketHealth(visualOk, healthyRoot());
  assert.equal(result.ok, true);
  assert.equal(result.visualOk, true);
  assert.equal(result.rootHealthOk, true);
  assert.equal(result.scope, 'public_viewer_ticket_health');
  assert.deepEqual(result.reasons, []);
});

test('legacy public ticket health export stays as public viewer alias', () => {
  assert.deepEqual(
    evaluatePublicTicketHealth(visualOk, healthyRoot()),
    evaluatePublicViewerTicketHealth(visualOk, healthyRoot())
  );
});

test('visual pass with degraded root health is split brain', () => {
  const result = evaluatePublicTicketHealth(visualOk, healthyRoot({
    sessionState: 'needs_attention',
    streamVerdict: 'capture_blocked',
  }));
  assert.equal(result.ok, false);
  assert.equal(result.visualOk, true);
  assert.equal(result.rootHealthOk, false);
  assert.equal(result.failure, 'public_ticket_split_brain');
  assert.match(result.reasons.join(','), /pixel.sessionState=needs_attention/);
  assert.match(result.reasons.join(','), /pixel.streamVerdict=capture_blocked/);
});

test('visual fail with root health pass remains a visual failure', () => {
  const result = evaluatePublicTicketHealth(visualFail, healthyRoot());
  assert.equal(result.ok, false);
  assert.equal(result.visualOk, false);
  assert.equal(result.rootHealthOk, true);
  assert.equal(result.failure, 'public_ticket_visual_missing');
});

test('stale root and relay frame ages fail with exact reasons', () => {
  const health = healthyRoot({
    visibleFrame: { lastFrameAgoMillis: 1600 },
  });
  health.directStream.lastFrameAgoMillis = 1700;
  const result = evaluatePublicTicketHealth(visualOk, health);
  assert.equal(result.ok, false);
  assert.match(result.reasons.join(','), /pixel.visibleFrame.lastFrameAgoMillis=1600/);
  assert.match(result.reasons.join(','), /relay.directStream.lastFrameAgoMillis=1700/);
});

test('unknown ViVi state fails even when frames are visible', () => {
  const result = evaluatePublicTicketHealth(visualOk, healthyRoot({
    viviState: { state: 'UNKNOWN_VIVI', source: 'fast_empty' },
  }));
  assert.equal(result.ok, false);
  assert.equal(result.failure, 'public_ticket_split_brain');
  assert.match(result.reasons.join(','), /pixel.viviState.state=UNKNOWN_VIVI/);
});

test('reads Pixel root health from public relay state phone health JSON', () => {
  const health = healthyRoot();
  const result = evaluatePublicTicketHealth(visualOk, {
    phone: health.phone,
    directStream: health.directStream,
    state: {
      phone: {
        healthJson: JSON.stringify({ type: 'health', data: health.phoneFull }),
      },
    },
  });
  assert.equal(result.ok, true);
  assert.equal(result.rootHealthOk, true);
});
