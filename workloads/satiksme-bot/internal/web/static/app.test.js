"use strict";

var test = require("node:test");
var assert = require("node:assert/strict");

global.window = {
  SATIKSME_APP_CONFIG: {},
};

global.document = {
  addEventListener: function () {},
  getElementById: function () {
    return null;
  },
};

var app = require("./app.js");

test("parseDepartures extracts live row ids and departure clocks", function () {
  var parsed = app.__test__.parseDepartures(
    "stop,3012\ntram,1,b-a,68420,35119,Imanta\nbus,22,a-b,68542,78648,Lidosta\n",
    new Date("2026-03-10T18:55:00+02:00")
  );

  assert.equal(parsed.stopId, "3012");
  assert.equal(parsed.departures.length, 2);
  assert.equal(parsed.departures[0].routeLabel, "1");
  assert.equal(parsed.departures[0].liveRowId, "35119");
  assert.equal(parsed.departures[1].departureClock, "19:02");
  assert.equal(parsed.departures[1].minutesAway, 7);
});

test("parseDepartures keeps rows inside stale grace and filters older ones", function () {
  var parsed = app.__test__.parseDepartures(
    "stop,3012\ntram,1,b-a,68420,35119,Imanta\nbus,22,a-b,68610,78648,Lidosta\n",
    new Date("2026-03-10T19:00:30+02:00")
  );

  assert.equal(parsed.departures.length, 2);
  assert.equal(parsed.departures[0].routeLabel, "1");
  assert.equal(parsed.departures[0].minutesAway, 0);
  assert.equal(parsed.departures[1].routeLabel, "22");
});

test("parseDepartures rolls departures over after midnight in Riga time", function () {
  var parsed = app.__test__.parseDepartures(
    "stop,3012\ntram,1,b-a,300,35119,Imanta\n",
    new Date("2026-03-10T23:55:00+02:00")
  );

  assert.equal(parsed.departures.length, 1);
  assert.equal(parsed.departures[0].minutesAway, 10);
});

test("resolveInitialView falls back to Riga center", function () {
  assert.deepEqual(app.__test__.resolveInitialView(null), {
    lat: 56.9496,
    lng: 24.1052,
    zoom: 13,
  });
});

test("renderReportControls is hidden in public mode", function () {
  var html = app.__test__.renderReportControls("public", false, { id: "3012" }, [], null, new Date("2026-03-10T18:55:00+02:00"));
  assert.equal(html, "");
});

test("renderDepartureRow keeps time metadata inside the copy column", function () {
  var html = app.__test__.renderDepartureRow(
    {
      mode: "trol",
      routeLabel: "9",
      destination: "Iļģuciems",
      departureClock: "19:02",
      minutesAway: 11,
    },
    0,
    { mode: "mini-app", authenticated: true }
  );

  assert.match(html, /departure-copy/);
  assert.match(html, /departure-meta/);
  assert.match(html, /departure-meta-clock\">19:02</);
  assert.match(html, /departure-meta-countdown\">pēc 11 min</);
  assert.match(html, /data-action="report-vehicle"/);
});

test("renderReportNote explains Telegram requirement in mini app mode", function () {
  var html = app.__test__.renderReportNote("mini-app", "telegram_required");
  assert.match(html, /Atver šo lapu no Telegram/);
});

test("formatEventTime renders clock values", function () {
  assert.equal(app.__test__.formatEventTime("2026-03-10T18:55:00+02:00"), "18:55");
});

test("buildVehicleReportPayload keeps fallback-friendly fields", function () {
  assert.deepEqual(
    app.__test__.buildVehicleReportPayload("3012", {
      mode: "tram",
      routeLabel: "1",
      direction: "b-a",
      destination: "Imanta",
      departureSeconds: 68420,
      liveRowId: "",
    }),
    {
      stopId: "3012",
      mode: "tram",
      routeLabel: "1",
      direction: "b-a",
      destination: "Imanta",
      departureSeconds: 68420,
      liveRowId: "",
    }
  );
});

test("vehicleMovementTimestampMs reads updatedAt values", function () {
  assert.equal(
    app.__test__.vehicleMovementTimestampMs({ updatedAt: "2026-03-10T18:55:15Z" }),
    Date.parse("2026-03-10T18:55:15Z")
  );
});

test("vehicleMovementDurationMs tracks feed cadence with a long satiksme easing window", function () {
  assert.equal(
    app.__test__.vehicleMovementDurationMs(
      Date.parse("2026-03-10T18:55:00Z"),
      Date.parse("2026-03-10T18:55:15Z"),
      15000
    ),
    12750
  );
});

test("mapZoomTier switches between far, compact, and detail tiers", function () {
  assert.equal(app.__test__.mapZoomTier(12), "far");
  assert.equal(app.__test__.mapZoomTier(14), "compact");
  assert.equal(app.__test__.mapZoomTier(15), "detail");
});

test("shouldRenderStopMarker keeps far-zoom stops visible", function () {
  assert.equal(app.__test__.shouldRenderStopMarker(13, false, 0), true);
  assert.equal(app.__test__.shouldRenderStopMarker(13, false, 2), true);
  assert.equal(app.__test__.shouldRenderStopMarker(13, true, 0), true);
  assert.equal(app.__test__.shouldRenderStopMarker(14, false, 0), true);
});

test("shouldShowStopBadge keeps sighting badges visible whenever a stop has reports", function () {
  assert.equal(app.__test__.shouldShowStopBadge(13, 3), true);
  assert.equal(app.__test__.shouldShowStopBadge(14, 3), true);
  assert.equal(app.__test__.shouldShowStopBadge(15, 0), false);
  assert.equal(app.__test__.shouldShowStopBadge(15, 3), true);
});

test("stopMarkerStyle keeps far-zoom stops at the minimum size while keeping sighting colors", function () {
  assert.deepEqual(app.__test__.stopMarkerStyle(13, false, 0, 1000), {
    radius: 2.33,
    weight: 1,
    color: "#21636d",
    fillColor: "#4db8ab",
    fillOpacity: 0.74,
  });

  assert.deepEqual(app.__test__.stopMarkerStyle(13, false, 2, 1000), {
    radius: 2.33,
    weight: 1,
    color: "#a63d32",
    fillColor: "#f46d43",
    fillOpacity: 0.88,
  });

  assert.deepEqual(app.__test__.stopMarkerStyle(13, true, 0, 1000), {
    radius: 2.33,
    weight: 2,
    color: "#8a5a12",
    fillColor: "#ffd166",
    fillOpacity: 0.96,
  });
});

test("stopMarkerRadiusForHeight clamps and interpolates across the configured range", function () {
  assert.equal(app.__test__.stopMarkerRadiusForHeight(1500), 2.33);
  assert.equal(app.__test__.stopMarkerRadiusForHeight(1000), 2.33);
  assert.equal(app.__test__.stopMarkerRadiusForHeight(525), 8.665);
  assert.ok(Math.abs(app.__test__.stopMarkerRadiusForHeight(70) - 14.733263157894738) < 1e-9);
  assert.equal(app.__test__.stopMarkerRadiusForHeight(50), 15);
  assert.equal(app.__test__.stopMarkerRadiusForHeight(0), 15);
});

test("stopMarkerStyle interpolates compact stop size between 1km and 50m", function () {
  assert.deepEqual(app.__test__.stopMarkerStyle(14, false, 0, 525), {
    radius: 8.665,
    weight: 1,
    color: "#21636d",
    fillColor: "#4db8ab",
    fillOpacity: 0.74,
  });

  assert.deepEqual(app.__test__.stopMarkerStyle(14, false, 2, 525), {
    radius: 8.665,
    weight: 1,
    color: "#b34a3c",
    fillColor: "#f46d43",
    fillOpacity: 0.88,
  });

  assert.deepEqual(app.__test__.stopMarkerStyle(14, true, 0, 525), {
    radius: 8.665,
    weight: 2,
    color: "#8a5a12",
    fillColor: "#ffd166",
    fillOpacity: 0.94,
  });
});

test("stopMarkerStyle matches detail stop size to the transport footprint at 50m", function () {
  assert.deepEqual(app.__test__.stopMarkerStyle(15, false, 0, 50), {
    radius: 15,
    weight: 1,
    color: "#0b5563",
    fillColor: "#3bb7a5",
    fillOpacity: 0.92,
  });

  assert.deepEqual(app.__test__.stopMarkerStyle(15, false, 2, 50), {
    radius: 15,
    weight: 1,
    color: "#d94b3d",
    fillColor: "#f46d43",
    fillOpacity: 0.92,
  });

  assert.deepEqual(app.__test__.stopMarkerStyle(15, true, 0, 50), {
    radius: 15,
    weight: 2,
    color: "#8a5a12",
    fillColor: "#ffd166",
    fillOpacity: 0.92,
  });
});

test("stopMarkerStyle keeps growing through the 70m band", function () {
  var style = app.__test__.stopMarkerStyle(15, false, 0, 70);
  assert.ok(Math.abs(style.radius - 14.733263157894738) < 1e-9);
  assert.equal(style.weight, 1);
  assert.equal(style.color, "#0b5563");
  assert.equal(style.fillColor, "#3bb7a5");
  assert.equal(style.fillOpacity, 0.92);
});

test("stopMarkerStyle is still smaller above 70m while preserving detail colors", function () {
  var style = app.__test__.stopMarkerStyle(15, false, 2, 120);
  assert.ok(Math.abs(style.radius - 14.06642105263158) < 1e-9);
  assert.equal(style.weight, 1);
  assert.equal(style.color, "#d94b3d");
  assert.equal(style.fillColor, "#f46d43");
  assert.equal(style.fillOpacity, 0.92);
});

test("boundsHeightMeters measures the vertical span of the map bounds", function () {
  var height = app.__test__.boundsHeightMeters({
    getNorth: function () {
      return 56.949915;
    },
    getSouth: function () {
      return 56.949285;
    },
    getCenter: function () {
      return { lng: 24.1052 };
    },
  });

  assert.equal(Math.round(height), 70);
});

test("vehicleMarkerProfile collapses vehicles into compact dots below detail zoom", function () {
  var compact = app.__test__.vehicleMarkerProfile(14);
  assert.equal(compact.compact, true);
  assert.equal(compact.showRoute, false);
  assert.equal(compact.showBadge, false);
  assert.deepEqual(compact.iconSize, [14, 14]);

  var detail = app.__test__.vehicleMarkerProfile(15);
  assert.equal(detail.compact, false);
  assert.equal(detail.showRoute, true);
  assert.equal(detail.showBadge, true);
  assert.deepEqual(detail.iconSize, [34, 34]);
});

test("buildVehicleMarkerHTML omits label and badge for compact markers", function () {
  var compactHtml = app.__test__.buildVehicleMarkerHTML(
    { mode: "tram", routeLabel: "1", sightingCount: 3, lowFloor: true },
    app.__test__.vehicleMarkerProfile(14)
  );
  assert.match(compactHtml, /vehicle-marker-compact/);
  assert.doesNotMatch(compactHtml, /vehicle-marker-route/);
  assert.doesNotMatch(compactHtml, /vehicle-marker-badge/);

  var detailHtml = app.__test__.buildVehicleMarkerHTML(
    { mode: "tram", routeLabel: "1", sightingCount: 3, lowFloor: true },
    app.__test__.vehicleMarkerProfile(15)
  );
  assert.match(detailHtml, /vehicle-marker-route/);
  assert.match(detailHtml, /vehicle-marker-badge/);
  assert.match(detailHtml, /vehicle-marker-low-floor/);
});

test("buildVehiclePopupHTML renders only route and identification number", function () {
  var html = app.__test__.buildVehiclePopupHTML({
    mode: "bus",
    routeLabel: "22",
    vehicleCode: "78648",
    stopName: "Lidosta",
    arrivalSeconds: 68542,
    sightingCount: 3,
  });

  assert.match(html, /vehicle-popup-route[^>]*>22</);
  assert.match(html, /vehicle-popup-id[^>]*>78648</);
  assert.doesNotMatch(html, /vehicle-popup-separator/);
  assert.doesNotMatch(html, /Next stop/);
  assert.doesNotMatch(html, /ETA/);
  assert.doesNotMatch(html, /Vehicle/);
  assert.doesNotMatch(html, /sighting/);
  assert.doesNotMatch(html, /report-live-vehicle/);
});

test("buildVehiclePopupHTML falls back cleanly when no identification number is present", function () {
  var html = app.__test__.buildVehiclePopupHTML({
    mode: "tram",
    routeLabel: "1",
  });

  assert.match(html, /vehicle-popup-route[^>]*>1</);
  assert.doesNotMatch(html, /vehicle-popup-separator/);
  assert.doesNotMatch(html, /<span class="vehicle-popup-id"/);
});

test("buildVehiclePopupHTML shows a report action for reportable live vehicles in the mini app", function () {
  var html = app.__test__.buildVehiclePopupHTML(
    {
      id: "bus:22:78648",
      mode: "bus",
      routeLabel: "22",
      vehicleCode: "78648",
      stopId: "1402",
    },
    {
      mode: "mini-app",
      authenticated: true,
    }
  );

  assert.match(html, /report-live-vehicle/);
  assert.match(html, /data-vehicle-id="bus:22:78648"/);
  assert.match(html, /Kontrole/);
});

test("matchDepartureToVehicle prefers same route, direction, and closest arrival", function () {
  var match = app.__test__.matchDepartureToVehicle(
    {
      mode: "bus",
      routeLabel: "22",
      direction: "a-b",
      arrivalSeconds: 68542,
    },
    [
      { mode: "bus", routeLabel: "22", direction: "b-a", departureSeconds: 68542, destination: "Centrs" },
      { mode: "bus", routeLabel: "22", direction: "a-b", departureSeconds: 68500, destination: "Lidosta" },
      { mode: "bus", routeLabel: "22", direction: "a-b", departureSeconds: 68680, destination: "Jugla" },
    ]
  );

  assert.deepEqual(match, {
    mode: "bus",
    routeLabel: "22",
    direction: "a-b",
    departureSeconds: 68500,
    destination: "Lidosta",
  });
});

test("buildLiveVehicleFallbackReportPayload uses the current stop when live departures are unavailable", function () {
  assert.deepEqual(
    app.__test__.buildLiveVehicleFallbackReportPayload({
      stopId: "1402",
      stopName: "Lidosta",
      mode: "bus",
      routeLabel: "22",
      direction: "a-b",
      arrivalSeconds: 68542,
    }),
    {
      stopId: "1402",
      mode: "bus",
      routeLabel: "22",
      direction: "a-b",
      destination: "Lidosta",
      departureSeconds: 68542,
      liveRowId: "",
    }
  );
});

test("canReportDeparture requires a destination for transport reports", function () {
  assert.equal(
    app.__test__.canReportDeparture({
      mode: "bus",
      routeLabel: "22",
      destination: "Lidosta",
    }),
    true
  );
  assert.equal(
    app.__test__.canReportDeparture({
      mode: "bus",
      routeLabel: "22",
      destination: "",
    }),
    false
  );
});

test("renderReportControls hides transport report actions when a destination is missing", function () {
  var html = app.__test__.renderReportControls(
    "mini-app",
    true,
    { id: "3012" },
    [
      { mode: "bus", routeLabel: "22", destination: "" },
      { mode: "tram", routeLabel: "1", destination: "Centrs" },
    ],
    {
      stopSightings: [{ stopId: "3012", createdAt: "2026-03-10T18:50:00+02:00" }],
      vehicleSightings: [],
    },
    new Date("2026-03-10T18:55:00+02:00")
  );

  assert.match(html, /Kontrole/);
  assert.doesNotMatch(html, /data-index="0"/);
  assert.match(html, /data-index="1"/);
});

test("renderStopSightingControl places a stop sighting action in the stop detail section", function () {
  var html = app.__test__.renderStopSightingControl(
    "mini-app",
    true,
    { id: "3012" },
    {
      stopSightings: [{ stopId: "3012", createdAt: "2026-03-10T18:50:00+02:00" }],
      vehicleSightings: [],
    },
    new Date("2026-03-10T18:55:00+02:00")
  );

  assert.match(html, /Pieturas kontrole/);
  assert.match(html, /Pēdējais: pirms 5 min/);
  assert.match(html, /stop-detail-actions/);
});

test("latestReportAgeLabel matches stop ids with and without leading zeros", function () {
  assert.equal(
    app.__test__.latestReportAgeLabel(
      "0705",
      {
        stopSightings: [],
        vehicleSightings: [{ stopId: "705", createdAt: "2026-03-10T18:50:00+02:00" }],
      },
      new Date("2026-03-10T18:55:00+02:00")
    ),
    "Pēdējais: pirms 5 min"
  );
});

test("formatRelativeReportAge formats recent ages in Latvian", function () {
  assert.equal(
    app.__test__.formatRelativeReportAge(
      "2026-03-10T18:54:30+02:00",
      new Date("2026-03-10T18:55:00+02:00")
    ),
    "tikko"
  );
  assert.equal(
    app.__test__.formatRelativeReportAge(
      "2026-03-10T18:49:00+02:00",
      new Date("2026-03-10T18:55:00+02:00")
    ),
    "pirms 6 min"
  );
});
