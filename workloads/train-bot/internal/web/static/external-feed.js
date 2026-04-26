(function (root, factory) {
  if (typeof module === "object" && module.exports) {
    module.exports = factory();
    return;
  }
  root.TrainExternalFeed = factory();
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  var GRAPH_PATH = "/api/trainGraph";

  function stringValue(value) {
    if (value === null || value === undefined) {
      return "";
    }
    return String(value).trim();
  }

  function toNumber(value) {
    if (typeof value === "number" && Number.isFinite(value)) {
      return value;
    }
    if (typeof value === "string" && value.trim() !== "") {
      var parsed = Number(value);
      if (Number.isFinite(parsed)) {
        return parsed;
      }
    }
    return null;
  }

  function toInteger(value) {
    var parsed = toNumber(value);
    if (parsed === null) {
      return null;
    }
    return Math.round(parsed);
  }

  function normalizePoint(value) {
    if (!Array.isArray(value) || value.length < 2) {
      return null;
    }
    var lat = toNumber(value[0]);
    var lng = toNumber(value[1]);
    if (lat === null || lng === null) {
      return null;
    }
    return { lat: lat, lng: lng };
  }

  function normalizeStationKey(value) {
    return stringValue(value)
      .normalize("NFKD")
      .replace(/[\u0300-\u036f]/g, "")
      .replace(/\*/g, "")
      .replace(/[^a-zA-Z0-9]+/g, " ")
      .trim()
      .toLowerCase();
  }

  function extractServiceDate(value) {
    var text = stringValue(value);
    var match = text.match(/^(\d{4}-\d{2}-\d{2})/);
    return match ? match[1] : "";
  }

  function clockMinutes(value) {
    var text = stringValue(value);
    var match = text.match(/(?:\d{4}-\d{2}-\d{2}[ T])?(\d{2}):(\d{2})/);
    if (!match) {
      return null;
    }
    return (Number(match[1]) * 60) + Number(match[2]);
  }

  function pointList(points) {
    return (Array.isArray(points) ? points : []).map(normalizePoint).filter(Boolean);
  }

  function normalizeStop(stop, index) {
    return {
      stationId: stringValue(stop && (stop.id || stop.pvID || stop.gps_id)),
      title: stringValue(stop && (stop.title || stop.lockedTitle || stop.cargoTitle)),
      titleKey: normalizeStationKey(stop && (stop.title || stop.lockedTitle || stop.cargoTitle)),
      position: normalizePoint(stop && stop.coords),
      departureTime: stringValue(stop && stop.departure),
      departureMinutes: clockMinutes(stop && stop.departure),
      routeId: stringValue(stop && stop.routes_id),
      gpsId: stringValue(stop && stop.gps_id),
      stopIndex: toInteger(stop && stop.i) !== null ? toInteger(stop && stop.i) : index,
    };
  }

  function normalizeTrainGraphRoute(route) {
    var stops = Array.isArray(route && route.stops)
      ? route.stops.map(normalizeStop).filter(function (stop) {
        return Boolean(stop.title) && Boolean(stop.position);
      })
      : [];
    var origin = stops.length ? stops[0] : null;
    var destination = stops.length ? stops[stops.length - 1] : null;
    return {
      routeId: stringValue(route && route.id),
      trainNumber: stringValue(route && route.train),
      serviceDate: stringValue(route && route.schDate) || extractServiceDate(route && route.departure),
      name: stringValue(route && route.name),
      departureTime: stringValue(route && route.departure),
      arrivalTime: stringValue(route && route.arrival),
      departureMinutes: clockMinutes(route && route.departure),
      arrivalMinutes: clockMinutes(route && route.arrival),
      direction: stringValue(route && route.direction),
      railLine: stringValue(route && route.railLine),
      fuelType: stringValue(route && route.fuelType),
      origin: origin ? origin.title : "",
      destination: destination ? destination.title : "",
      originKey: origin ? origin.titleKey : "",
      destinationKey: destination ? destination.titleKey : "",
      stops: stops,
      polyline: stops.map(function (stop) {
        return stop.position;
      }),
    };
  }

  function normalizeTrainGraphPayload(payload) {
    var routes = Array.isArray(payload && payload.data)
      ? payload.data
      : Array.isArray(payload)
        ? payload
        : [];
    var normalized = routes.map(normalizeTrainGraphRoute).filter(function (route) {
      return route.routeId || route.trainNumber;
    });
    return {
      routes: normalized,
      routeCount: normalized.length,
    };
  }

  function normalizeBackEndEntry(entry) {
    var live = entry && entry.returnValue ? entry.returnValue : entry;
    var stops = Array.isArray(live && live.stopObjArray)
      ? live.stopObjArray.map(normalizeStop).filter(function (stop) {
        return Boolean(stop.title) && Boolean(stop.position);
      })
      : [];
    var currentStopIndex = toInteger(live && live.currentStopIndex);
    var currentStop = currentStopIndex !== null && currentStopIndex >= 0 && currentStopIndex < stops.length
      ? stops[currentStopIndex]
      : null;
    var nextStop = live && live.nextStopObj ? normalizeStop(live.nextStopObj, currentStopIndex !== null ? currentStopIndex + 1 : stops.length) : null;
    var animatedPosition = normalizePoint(live && live.animatedCoord);
    var rawPosition = normalizePoint(live && live.position);
    return {
      routeId: stringValue(live && live.id),
      trainNumber: stringValue(live && live.train),
      name: stringValue(entry && entry.name),
      serviceDate: extractServiceDate(live && live.departureTime),
      departureTime: stringValue(live && live.departureTime),
      arrivalTime: stringValue(live && live.arrivalTime),
      departureMinutes: clockMinutes(live && live.departureTime),
      arrivalMinutes: clockMinutes(live && live.arrivalTime),
      position: animatedPosition || rawPosition,
      animatedPosition: animatedPosition,
      rawPosition: rawPosition,
      currentStopIndex: currentStopIndex,
      currentStop: currentStop,
      nextStop: nextStop && nextStop.title ? nextStop : null,
      updatedAt: stringValue(live && live.updaterTimeStamp),
      stopped: Boolean(live && live.stopped),
      finished: Boolean(live && live.finished),
      isGpsActive: Boolean(live && live.isGpsActive),
      nextTime: live && live.nextTime,
      waitingTime: live && live.waitingTime,
      arrivingTime: live && live.arrivingTime,
      stops: stops,
      polyline: Array.isArray(entry && entry.stopCoordArray)
        ? pointList(entry.stopCoordArray)
        : stops.map(function (stop) {
          return stop.position;
        }),
      origin: stops.length ? stops[0].title : "",
      destination: stops.length ? stops[stops.length - 1].title : "",
      originKey: stops.length ? stops[0].titleKey : "",
      destinationKey: stops.length ? stops[stops.length - 1].titleKey : "",
    };
  }

  function normalizeBackEndFrame(payload) {
    if (payload && Array.isArray(payload.data)) {
      return payload.data.map(normalizeBackEndEntry).filter(function (item) {
        return item.routeId || item.trainNumber || item.position;
      });
    }
    if (Array.isArray(payload)) {
      return payload.map(normalizeBackEndEntry).filter(function (item) {
        return item.routeId || item.trainNumber || item.position;
      });
    }
    return normalizeBackEndEntry(payload);
  }

  function normalizeActiveStopEntry(entry) {
    return {
      stationId: stringValue(entry && (entry.id || entry.pvID || entry.gps_id)),
      title: stringValue(entry && (entry.title || entry.lockedTitle || entry.cargoTitle)),
      titleKey: normalizeStationKey(entry && (entry.title || entry.lockedTitle || entry.cargoTitle)),
      position: normalizePoint(entry && entry.coords),
      routeId: stringValue(entry && entry.routes_id),
      trainNumber: stringValue(entry && entry.train),
      currentStopIndex: toInteger(entry && entry.currentStopIndex),
      stopIndex: toInteger(entry && entry.stopIndex),
      animatedPosition: normalizePoint(entry && entry.animatedCoord),
      directionList: Array.isArray(entry && entry.directionList) ? entry.directionList.slice() : [],
      departureTime: stringValue(entry && entry.departure),
      serviceDate: extractServiceDate(entry && entry.departure),
      hasTrain: Boolean(stringValue(entry && entry.train)),
    };
  }

  function normalizeActiveStopsFrame(payload) {
    if (payload && Array.isArray(payload.data)) {
      return payload.data.map(normalizeActiveStopEntry).filter(function (item) {
        return item.title && item.position;
      });
    }
    if (Array.isArray(payload)) {
      return payload.map(normalizeActiveStopEntry).filter(function (item) {
        return item.title && item.position;
      });
    }
    return normalizeActiveStopEntry(payload);
  }

  function roundedNumber(value) {
    var parsed = toNumber(value);
    if (parsed === null) {
      return null;
    }
    return Math.round(parsed * 1000000) / 1000000;
  }

  function stableMaterialValue(value, ignoredFields) {
    if (value === undefined || value === null) {
      return null;
    }
    if (typeof value === "number") {
      return Number.isFinite(value) ? roundedNumber(value) : null;
    }
    if (typeof value === "string" || typeof value === "boolean") {
      return value;
    }
    if (Array.isArray(value)) {
      return value.map(function (item) {
        return stableMaterialValue(item, ignoredFields);
      });
    }
    if (typeof value === "object") {
      var out = {};
      Object.keys(value).sort().forEach(function (key) {
        if (ignoredFields && ignoredFields[key]) {
          return;
        }
        if (typeof value[key] === "function") {
          return;
        }
        out[key] = stableMaterialValue(value[key], ignoredFields);
      });
      return out;
    }
    return value;
  }

  function stableSerialize(value) {
    return JSON.stringify(value);
  }

  function materialSignature(value, ignoredFields) {
    return stableSerialize(stableMaterialValue(value, ignoredFields || {}));
  }

  function sameMaterialValue(left, right, ignoredFields) {
    return materialSignature(left, ignoredFields) === materialSignature(right, ignoredFields);
  }

  function sameTrainStopsPayload(left, right) {
    return sameMaterialValue(left, right, { schedule: true, generatedAt: true });
  }

  function sameNetworkMapPayload(left, right) {
    return sameMaterialValue(left, right, { schedule: true, generatedAt: true });
  }

  function samePublicDashboard(left, right) {
    return sameMaterialValue(left, right, { schedule: true, generatedAt: true });
  }

  function samePublicStationDepartures(left, right) {
    return sameMaterialValue(left, right, { schedule: true, generatedAt: true });
  }

  function sameExternalFeedState(left, right) {
    return sameMaterialValue(left, right, { lastMessageAt: true, lastGraphAt: true });
  }

  function mapConfigSignature(config) {
    return materialSignature(config, { modelKey: true, bounds: true });
  }

  function mapConfigMarkerSignature(marker) {
    return materialSignature(marker, {});
  }

  function planMarkerReconcile(previousItems, nextItems, openPopupKey) {
    var previousIndex = new Map();
    var nextIndex = new Map();
    var addKeys = [];
    var updateKeys = [];
    var removeKeys = [];
    var previousList = Array.isArray(previousItems) ? previousItems : [];
    var nextList = Array.isArray(nextItems) ? nextItems : [];

    previousList.forEach(function (item) {
      var key = stringValue(item && item.markerKey);
      if (!key) {
        return;
      }
      previousIndex.set(key, mapConfigMarkerSignature(item));
    });
    nextList.forEach(function (item) {
      var key = stringValue(item && item.markerKey);
      if (!key) {
        return;
      }
      var nextSignature = mapConfigMarkerSignature(item);
      nextIndex.set(key, nextSignature);
      if (!previousIndex.has(key)) {
        addKeys.push(key);
        return;
      }
      if (previousIndex.get(key) !== nextSignature) {
        updateKeys.push(key);
      }
    });
    previousIndex.forEach(function (_signature, key) {
      if (!nextIndex.has(key)) {
        removeKeys.push(key);
      }
    });

    var retainPopup = Boolean(openPopupKey) && nextIndex.has(openPopupKey);
    return {
      addKeys: addKeys,
      updateKeys: updateKeys,
      removeKeys: removeKeys,
      retainPopupKey: retainPopup ? openPopupKey : "",
      clearPopup: Boolean(openPopupKey) && !retainPopup,
      hasChanges: addKeys.length > 0 || updateKeys.length > 0 || removeKeys.length > 0,
    };
  }

  function extractLocalTrain(raw) {
    var train = raw && raw.train
      ? raw.train
      : raw && raw.trainCard && raw.trainCard.train
        ? raw.trainCard.train
        : raw;
    if (!train) {
      return null;
    }
    var id = stringValue(train.id || raw.localTrainId);
    var exactMatch = id.match(/(\d{3,5})$/);
    return {
      match: raw,
      localTrainId: id,
      serviceDate: stringValue(train.serviceDate || raw.serviceDate) || extractServiceDate(id),
      trainNumber: stringValue(train.trainNumber || raw.trainNumber || (exactMatch ? exactMatch[1] : "")),
      originKey: normalizeStationKey(train.fromStation || raw.fromStation || raw.origin || raw.originName),
      destinationKey: normalizeStationKey(train.toStation || raw.toStation || raw.destination || raw.destinationName),
      departureMinutes: clockMinutes(train.departureAt || train.departure || raw.departureTime || raw.departureAt),
    };
  }

  function extractExternalTrain(raw) {
    if (!raw) {
      return null;
    }
    return {
      routeId: stringValue(raw.routeId || raw.id),
      serviceDate: stringValue(raw.serviceDate) || extractServiceDate(raw.departureTime || raw.departureAt),
      trainNumber: stringValue(raw.trainNumber || raw.train),
      originKey: normalizeStationKey(raw.origin || raw.originName || (raw.stops && raw.stops[0] && raw.stops[0].title)),
      destinationKey: normalizeStationKey(raw.destination || raw.destinationName || (raw.stops && raw.stops[raw.stops.length - 1] && raw.stops[raw.stops.length - 1].title)),
      departureMinutes: clockMinutes(raw.departureTime || raw.departureAt || raw.departure),
    };
  }

  function stableExternalTrainIdentity(raw) {
    var external = extractExternalTrain(raw);
    if (!external) {
      return "";
    }
    if (external.routeId) {
      return "route:" + external.routeId;
    }
    if (external.serviceDate && external.trainNumber) {
      return "train:" + external.serviceDate + ":" + external.trainNumber;
    }
    if (
      external.serviceDate &&
      external.originKey &&
      external.destinationKey &&
      external.departureMinutes !== null
    ) {
      return "route-time:" + external.serviceDate + ":" + external.originKey + ":" + external.destinationKey + ":" + String(external.departureMinutes);
    }
    return "";
  }

  function matchLocalTrain(externalTrain, localItems) {
    var external = Array.isArray(externalTrain) ? extractExternalTrain(localItems) : extractExternalTrain(externalTrain);
    var locals = Array.isArray(externalTrain) ? externalTrain : localItems;
    if (!external || !external.serviceDate) {
      return null;
    }

    var normalizedLocal = (Array.isArray(locals) ? locals : []).map(extractLocalTrain).filter(Boolean);
    if (external.trainNumber) {
      var exactId = external.serviceDate + "-train-" + external.trainNumber;
      var exact = normalizedLocal.find(function (item) {
        return item.localTrainId === exactId;
      });
      if (exact) {
        return {
          match: exact.match,
          matchType: "exact-id",
          localTrainId: exact.localTrainId,
        };
      }

      var sameNumber = normalizedLocal.filter(function (item) {
        return item.serviceDate === external.serviceDate && item.trainNumber === external.trainNumber;
      }).sort(function (left, right) {
        return Math.abs((left.departureMinutes || 0) - (external.departureMinutes || 0)) - Math.abs((right.departureMinutes || 0) - (external.departureMinutes || 0));
      });
      if (sameNumber.length) {
        return {
          match: sameNumber[0].match,
          matchType: "train-number-same-day",
          localTrainId: sameNumber[0].localTrainId,
        };
      }
    }

    if (!external.originKey || !external.destinationKey || external.departureMinutes === null) {
      return null;
    }

    var routeTime = normalizedLocal.filter(function (item) {
      return item.serviceDate === external.serviceDate &&
        item.originKey === external.originKey &&
        item.destinationKey === external.destinationKey &&
        item.departureMinutes !== null &&
        Math.abs(item.departureMinutes - external.departureMinutes) <= 2;
    }).sort(function (left, right) {
      return Math.abs(left.departureMinutes - external.departureMinutes) - Math.abs(right.departureMinutes - external.departureMinutes);
    });

    if (!routeTime.length) {
      return null;
    }
    return {
      match: routeTime[0].match,
      matchType: "route-time-window",
      localTrainId: routeTime[0].localTrainId,
    };
  }

  function trimTrailingSlash(value) {
    return stringValue(value).replace(/\/+$/, "");
  }

  function createExternalTrainMapClient(options) {
    var opts = options || {};
    var fetchImpl = typeof opts.fetchImpl === "function"
      ? opts.fetchImpl
      : typeof fetch === "function"
        ? fetch.bind(typeof globalThis !== "undefined" ? globalThis : null)
        : null;
    var WebSocketCtor = typeof opts.WebSocketCtor === "function"
      ? opts.WebSocketCtor
      : typeof WebSocket === "function"
        ? WebSocket
        : null;
    var setTimer = typeof opts.setTimeoutFn === "function" ? opts.setTimeoutFn : setTimeout;
    var clearTimer = typeof opts.clearTimeoutFn === "function" ? opts.clearTimeoutFn : clearTimeout;
    var onState = typeof opts.onState === "function" ? opts.onState : null;
    var state = {
      enabled: opts.enabled !== false,
      connectionState: opts.enabled === false ? "disabled" : "idle",
      routes: [],
      liveTrains: [],
      activeStops: [],
      lastGraphAt: "",
      lastMessageAt: "",
      error: "",
    };

    var socket = null;
    var reconnectTimer = null;
    var reconnectDelayMs = 1000;
    var stopped = false;

    function snapshot() {
      return {
        enabled: state.enabled,
        connectionState: state.connectionState,
        routes: state.routes.slice(),
        liveTrains: state.liveTrains.slice(),
        activeStops: state.activeStops.slice(),
        lastGraphAt: state.lastGraphAt,
        lastMessageAt: state.lastMessageAt,
        error: state.error,
      };
    }

    function emit() {
      if (onState) {
        onState(snapshot());
      }
    }

    function scheduleReconnect() {
      if (stopped || !state.enabled || reconnectTimer) {
        return;
      }
      var delay = reconnectDelayMs;
      reconnectDelayMs = Math.min(reconnectDelayMs * 2, 30000);
      reconnectTimer = setTimer(function () {
        reconnectTimer = null;
        connect();
      }, delay);
    }

    function connect() {
      if (stopped || !state.enabled || !WebSocketCtor) {
        return;
      }
      if (socket && socket.readyState <= 1) {
        return;
      }
      state.connectionState = "connecting";
      emit();
      socket = new WebSocketCtor(opts.wsURL);
      socket.addEventListener("open", function () {
        reconnectDelayMs = 1000;
        state.connectionState = "live";
        state.error = "";
        emit();
      });
      socket.addEventListener("message", function (event) {
        var payload;
        try {
          payload = JSON.parse(event.data);
        } catch (_) {
          return;
        }
        if (payload && payload.type === "back-end") {
          state.liveTrains = normalizeBackEndFrame(payload);
        }
        if (payload && payload.type === "active-stops") {
          state.activeStops = normalizeActiveStopsFrame(payload);
        }
        if (payload && (payload.type === "back-end" || payload.type === "active-stops")) {
          state.lastMessageAt = new Date().toISOString();
          emit();
        }
      });
      socket.addEventListener("error", function () {
        state.error = "external websocket error";
        emit();
      });
      socket.addEventListener("close", function () {
        if (stopped) {
          return;
        }
        state.connectionState = "offline";
        emit();
        scheduleReconnect();
      });
    }

    function loadGraph() {
      if (!state.enabled || !fetchImpl) {
        return Promise.resolve(snapshot());
      }
      var graphURL = stringValue(opts.graphURL) || (trimTrailingSlash(opts.baseURL) + GRAPH_PATH);
      return fetchImpl(graphURL, { method: "GET" })
        .then(function (response) {
          if (!response.ok) {
            throw new Error("external route graph request failed");
          }
          return response.json();
        })
        .then(function (payload) {
          state.routes = normalizeTrainGraphPayload(payload).routes;
          state.lastGraphAt = new Date().toISOString();
          state.error = "";
          emit();
          return snapshot();
        });
    }

    function start() {
      if (!state.enabled) {
        state.connectionState = "disabled";
        emit();
        return Promise.resolve(snapshot());
      }
      return loadGraph().catch(function (error) {
        state.error = error && error.message ? error.message : String(error);
        emit();
        return snapshot();
      }).then(function () {
        connect();
        return snapshot();
      });
    }

    function stop() {
      stopped = true;
      if (reconnectTimer) {
        clearTimer(reconnectTimer);
        reconnectTimer = null;
      }
      if (socket && socket.readyState <= 1) {
        socket.close();
      }
      socket = null;
      state.connectionState = "disabled";
      emit();
    }

    return {
      start: start,
      stop: stop,
      snapshot: snapshot,
      loadGraph: loadGraph,
    };
  }

  return {
    createExternalTrainMapClient: createExternalTrainMapClient,
    mapConfigMarkerSignature: mapConfigMarkerSignature,
    mapConfigSignature: mapConfigSignature,
    matchLocalTrain: matchLocalTrain,
    normalizeActiveStopsFrame: normalizeActiveStopsFrame,
    normalizeBackEndFrame: normalizeBackEndFrame,
    normalizeStationKey: normalizeStationKey,
    normalizeTrainGraphPayload: normalizeTrainGraphPayload,
    planMarkerReconcile: planMarkerReconcile,
    sameExternalFeedState: sameExternalFeedState,
    sameMaterialValue: sameMaterialValue,
    sameNetworkMapPayload: sameNetworkMapPayload,
    samePublicDashboard: samePublicDashboard,
    samePublicStationDepartures: samePublicStationDepartures,
    stableExternalTrainIdentity: stableExternalTrainIdentity,
    sameTrainStopsPayload: sameTrainStopsPayload,
    stableMaterialValue: stableMaterialValue,
    stableSerialize: stableSerialize,
  };
});
