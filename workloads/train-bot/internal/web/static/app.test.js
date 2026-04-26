"use strict";

var test = require("node:test");
var assert = require("node:assert/strict");

global.window = {
  TRAIN_APP_CONFIG: {},
  location: { search: "" },
  localStorage: {
    getItem: function () {
      return null;
    },
  },
};

global.document = {
  getElementById: function () {
    return null;
  },
  addEventListener: function () {},
};

var app = require("./app.js");

test("renderSettingsTab uses the reports channel URL in settings", function () {
  var html = app.__test__.renderSettingsTab(
    {
      alertsEnabled: true,
      alertStyle: "DETAILED",
      language: "EN",
    }
  );

  assert.match(
    html,
    /href="https:\/\/t\.me\/vivi_kontrole_reports"/
  );
  assert.equal(app.__test__.reportsChannelURL, "https://t.me/vivi_kontrole_reports");
});

test("markerMovementTimestampMs prefers explicit movement timestamps", function () {
  assert.equal(
    app.__test__.markerMovementTimestampMs({ movementObservedAt: "2026-03-10T18:55:15Z" }),
    Date.parse("2026-03-10T18:55:15Z")
  );
});

test("markerMovementDurationMs caps long gaps for the train live overlay", function () {
  assert.equal(
    app.__test__.markerMovementDurationMs(
      Date.parse("2026-03-10T18:55:00Z"),
      Date.parse("2026-03-10T18:55:10Z"),
      10000
    ),
    2500
  );
});

test("alignMiniMapToSelectedTrain pins the map target and resumes follow", function () {
  global.window.TRAIN_APP_CONFIG.mode = "mini-app";
  app.__test__.resetState({
    mapFollowTrainId: "train-old",
    mapFollowPaused: true,
  });

  app.__test__.alignMiniMapToSelectedTrain("train-new");

  var state = app.__test__.getState();
  assert.equal(state.mapTrainId, "train-new");
  assert.equal(state.mapPinnedTrainId, "train-new");
  assert.equal(state.mapFollowTrainId, "train-new");
  assert.equal(state.mapFollowPaused, false);
});

test("pauseMiniMapFollow only pauses while actively tracking a train map", function () {
  global.window.TRAIN_APP_CONFIG.mode = "mini-app";
  app.__test__.resetState({
    tab: "map",
    mapTrainId: "train-map",
    mapFollowTrainId: "train-map",
  });

  app.__test__.pauseMiniMapFollow("test-manual-move");

  var pausedState = app.__test__.getState();
  assert.equal(pausedState.mapFollowPaused, true);

  app.__test__.resetState({
    tab: "dashboard",
    mapTrainId: "train-map",
    mapFollowTrainId: "train-map",
  });

  app.__test__.pauseMiniMapFollow("test-non-map-view");

  var idleState = app.__test__.getState();
  assert.equal(idleState.mapFollowPaused, false);
});

test("map tracking survives detail changes and app refreshes until leaving the map view", function () {
  global.window.TRAIN_APP_CONFIG.mode = "mini-app";
  app.__test__.resetState({
    tab: "map",
    selectedTrain: {
      trainCard: {
        train: {
          id: "train-detail",
        },
      },
    },
  });

  app.__test__.setPinnedDetailTrain("train-detail", { fromUser: true, reason: "test" });
  app.__test__.alignMiniMapToSelectedTrain("train-map");
  app.__test__.clearPinnedDetailTrain("test-close-detail");
  app.__test__.applyCurrentRidePayload({
    currentRide: {
      checkIn: {
        trainInstanceId: "train-ride",
      },
      train: {
        trainCard: {
          train: {
            id: "train-ride",
          },
        },
      },
    },
  });

  var activeState = app.__test__.getState();
  assert.equal(app.__test__.preferredMapTrainId(), "train-map");
  assert.equal(app.__test__.resolveMiniMapFollowTarget(), "train-map");
  assert.equal(activeState.mapTrainId, "train-map");
  assert.equal(activeState.mapPinnedTrainId, "train-map");
  assert.equal(activeState.mapFollowTrainId, "train-map");

  app.__test__.setMiniAppTab("dashboard", "test-leave-map");

  var inactiveState = app.__test__.getState();
  assert.equal(inactiveState.tab, "dashboard");
  assert.equal(inactiveState.mapPinnedTrainId, "");
  assert.equal(inactiveState.mapFollowTrainId, "");
  assert.equal(inactiveState.mapFollowPaused, false);
});
