function at(value, path) {
  return path.split('.').reduce(
    (current, key) => current && typeof current === 'object' ? current[key] : undefined,
    value,
  );
}

function number(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function phoneHealth(health) {
  const raw = health?.state?.phone?.healthJson || health?.state?.phone?.HealthJSON;
  if (!raw || typeof raw !== 'string') return null;
  try {
    const parsed = JSON.parse(raw);
    return parsed?.data || parsed;
  } catch (_) {
    return null;
  }
}

export function evaluateRootPublicTicketHealth(health) {
  const reasons = [];
  const pixel = health?.phoneFull?.data || health?.phoneFull || health?.data || phoneHealth(health) || health || {};
  const relay = health?.directStream || {};
  const phone = health?.phone || {};
  const visibleAge = number(at(pixel, 'visibleFrame.lastFrameAgoMillis'));
  const relayAge = number(relay.lastFrameAgoMillis);
  const epoch = number(relay.streamEpoch);
  const checks = [
    [pixel.sessionState === 'live', `pixel.sessionState=${pixel.sessionState ?? 'missing'}`],
    [pixel.streamActive === true, `pixel.streamActive=${pixel.streamActive ?? 'missing'}`],
    [pixel.streamVerdict === 'live', `pixel.streamVerdict=${pixel.streamVerdict ?? 'missing'}`],
    [at(pixel, 'ticketState.state') === 'live', `pixel.ticketState.state=${at(pixel, 'ticketState.state') ?? 'missing'}`],
    [at(pixel, 'viviState.state') === 'TICKET_DETAIL', `pixel.viviState.state=${at(pixel, 'viviState.state') ?? 'missing'}`],
    [visibleAge !== null && visibleAge <= 1500, `pixel.visibleFrame.lastFrameAgoMillis=${visibleAge ?? 'missing'}`],
    [at(pixel, 'hardwareH264.active') === true, `pixel.hardwareH264.active=${at(pixel, 'hardwareH264.active') ?? 'missing'}`],
    [at(pixel, 'hardwareH264.state') === 'active', `pixel.hardwareH264.state=${at(pixel, 'hardwareH264.state') ?? 'missing'}`],
    [phone.connected === true || phone.connected === 'true' || phone.Connected === true || phone.Connected === 'true' || relay.phoneConnected === true || relay.phoneConnected === 'true', `relay.phoneConnected=${relay.phoneConnected ?? phone.connected ?? phone.Connected ?? 'missing'}`],
    [number(relay.activeVideoClients) !== null && number(relay.activeVideoClients) >= 1, `relay.directStream.activeVideoClients=${relay.activeVideoClients ?? 'missing'}`],
    [Boolean(relay.codec && relay.transport && epoch !== null && epoch > 0), 'relay.directStream.configured=false'],
    [relayAge !== null && relayAge <= 1500, `relay.directStream.lastFrameAgoMillis=${relayAge ?? 'missing'}`],
  ];
  for (const [passed, reason] of checks) if (!passed) reasons.push(reason);
  return { ok: reasons.length === 0, reasons };
}

export function evaluatePublicViewerTicketHealth(frameResult, health) {
  const visualOk = frameResult?.ok === true && frameResult?.probe?.canvas?.looksDrawn === true;
  const root = evaluateRootPublicTicketHealth(health);
  const failure = visualOk
    ? (root.ok ? '' : 'public_ticket_split_brain')
    : (root.ok ? 'public_ticket_visual_missing' : 'public_ticket_unavailable');
  return {
    scope: 'public_viewer_ticket_health',
    ok: visualOk && root.ok,
    visualOk,
    rootHealthOk: root.ok,
    failure,
    reasons: root.reasons,
  };
}

export const evaluatePublicTicketHealth = evaluatePublicViewerTicketHealth;
