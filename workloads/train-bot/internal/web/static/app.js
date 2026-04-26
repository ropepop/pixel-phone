(function () {
  const cfg = window.TRAIN_APP_CONFIG || {};
  const reportsChannelURL = "https://t.me/vivi_kontrole_reports";
  const debugTrainStateTransitions = (() => {
    try {
      if (window.location && /\btrainDebugState=1\b/.test(window.location.search || "")) {
        return true;
      }
      if (window.localStorage && window.localStorage.getItem("trainAppDebugState") === "1") {
        return true;
      }
      if (window.localStorage && window.localStorage.getItem("trainAppDebugState") === "true") {
        return true;
      }
    } catch (_) {}
    return false;
  })();
  function createInitialState() {
    return {
      lang: "EN",
      messages: {},
      tab: "dashboard",
      window: "now",
      authenticated: false,
      me: null,
      currentRide: null,
      windowTrains: [],
      selectedTrain: null,
      pinnedDetailTrainId: "",
      pinnedDetailFromUser: false,
      publicDashboard: [],
      publicDashboardAll: [],
      publicTrain: null,
      mapTrainDetail: null,
      publicStationMatches: [],
      publicStationDepartures: null,
      publicStationSelected: null,
      mapData: null,
      networkMapData: null,
      mapTrainId: "",
      mapPinnedTrainId: "",
      mapFollowTrainId: "",
      mapFollowPaused: false,
      publicMapPopupKey: "",
      publicMapSelectedMarkerKey: "",
      publicMapFollowPaused: false,
      stations: [],
      selectedStation: null,
      stationDepartures: [],
      stationRecentSightings: [],
      stationSightingDestinations: [],
      stationSightingDestinationId: "",
      selectedSightingTrainId: "",
      expandedStationContextTrainId: "",
      expandedStopContextKey: "",
      favorites: [],
      originResults: [],
      destinationResults: [],
      routeResults: [],
      chosenOrigin: null,
      chosenDestination: null,
      dashboardFilter: "",
      publicFilter: "",
      publicFilterDraft: "",
      publicStationQuery: "",
      stationQuery: "",
      originQuery: "",
      destinationQuery: "",
      statusText: "",
      scheduleMeta: null,
      toast: null,
      checkInDropdownOpen: false,
      selectedCheckInTrainId: "",
      debugTrainStateTransitions,
      externalFeed: {
        enabled: Boolean(cfg.externalTrainMapEnabled),
        connectionState: cfg.externalTrainMapEnabled ? "idle" : "disabled",
        routes: [],
        liveTrains: [],
        activeStops: [],
        lastGraphAt: "",
        lastMessageAt: "",
        error: "",
      },
    };
  }

  function resetStateForTest(overrides) {
    const nextState = Object.assign(createInitialState(), overrides || {});
    Object.keys(state).forEach((key) => {
      delete state[key];
    });
    Object.assign(state, nextState);
  }

  const appEl = document.getElementById("app");
  const state = createInitialState();
  const mapController = createMapController();
  const MAP_MARKER_ANIMATION_DEFAULT_MS = 900;
  const MAP_MARKER_ANIMATION_MIN_MS = 250;
  const MAP_MARKER_ANIMATION_MAX_MS = 2500;
  const MAP_MARKER_COORD_EPSILON = 0.000001;
  let toastTimer = null;
  let externalFeedClient = null;
  let externalFeedRenderTimer = null;
  let selectedDetailSnapshot = null;

  function publicRoot() {
    return cfg.publicBaseURL || cfg.basePath || "/";
  }

  function publicStationRoot() {
    return publicRoot();
  }

  function publicDashboardRoot() {
    return `${cfg.basePath || ""}/departures` || "/departures";
  }

  function publicNetworkMapRoot() {
    return `${cfg.basePath || ""}/map` || "/map";
  }

  function publicTrainMapRoot(trainId) {
    return `${cfg.basePath || ""}/t/${encodeURIComponent(trainId)}/map`;
  }

  const fallbackMessages = {
    app_title: "vivi kontrole bot",
    app_loading: "Loading train app…",
    app_public_dashboard_eyebrow: "Public dashboard",
    app_public_train_eyebrow: "Public train",
    app_public_station_eyebrow: "Station search",
    app_public_dashboard_title: "Public departures dashboard",
    app_public_dashboard_note: "Read-only live status for active departures today.",
    app_public_train_title: "Public train status",
    app_public_train_note: "Read-only live status and recent reports for this departure.",
    app_public_map_eyebrow: "Stops map",
    app_public_map_title: "Train stops map",
    app_public_map_note: "Browse the scheduled stops and recent platform sightings for this departure.",
    app_public_dashboard_empty: "No departures are currently visible on the dashboard.",
    app_auth_required: "Open this page from Telegram to use private ride controls.",
    app_auth_required_body: "The departures dashboard and public station search remain available without Telegram sign-in.",
    app_status_ready: "Live view connected.",
    app_status_public: "Public read-only view.",
    app_status_telegram: "Telegram session active.",
    app_status_error: "Request failed.",
    app_status_error_with_code: "Request failed (%s).",
    app_section_dashboard: "Dashboard",
    app_section_checkin: "Check in",
    app_section_my_ride: "My ride",
    app_section_report: "Report",
    app_section_sightings: "Sightings",
    app_section_map: "Map",
    app_section_settings: "Settings",
    app_find_station: "Find station",
    app_report_sighting: "Report Sighting",
    app_find_origin: "Find origin",
    app_find_destination: "Find destination",
    app_find_route: "Find route",
    app_live_status: "Live status",
    app_public_page: "Public page",
    app_open_public: "Open public page",
    app_open_departures: "Departures",
    app_open_station_search: "Station search",
    app_refresh: "Refresh",
    app_search: "Search",
    app_search_placeholder: "Type a station prefix",
    app_station_results: "Station matches",
    app_route_results: "Route departures",
    app_dashboard_filter: "Filter departures",
    app_dashboard_intro: "Browse departures, inspect live status, and check in directly from a train card.",
    app_status_hint: "Select a departure to inspect status and timeline.",
    app_status_empty: "No departure selected.",
    app_current_ride_none: "You are not checked into a ride.",
    app_saved_routes: "Saved routes",
    app_report_success: "Report sent.",
    app_report_deduped: "Already captured. No duplicate report sent.",
    app_report_cooldown: "You can report again in %s min.",
    app_report_notice: "Only report what you personally observe on your current departure. Alerts are also shared with riders following the same corridor in either direction and with matching saved routes.",
    app_settings_saved: "Settings saved.",
    app_checked_in: "Checked in.",
    app_checked_out: "Checked out.",
    app_undo_restored: "Ride restored.",
    app_subscribed: "Subscription updated.",
    app_unsubscribed: "Subscription removed.",
    app_muted: "Alerts muted.",
    app_favorite_saved: "Route saved.",
    app_favorite_removed: "Saved route removed.",
    app_search_complete: "Search complete.",
    app_refresh_success: "Refreshed.",
    app_status_loaded: "Status loaded.",
    app_map_loaded: "Stops map loaded.",
    app_route_loaded: "Route departures updated.",
    app_choose_origin: "Choose origin first.",
    app_choose_destination: "Choose destination.",
    app_from: "From",
    app_to: "To",
    app_passes: "Passes",
    app_public_station_title: "Search departures by station",
    app_public_station_note: "Find a station and see its most recent departure plus the next departures today.",
    app_public_station_search_label: "Station",
    app_public_station_search_placeholder: "Type the start of a station name",
    app_public_station_matches: "Matching stations",
    app_public_station_no_matches: "No stations matched that search.",
    app_public_station_prompt: "Search for a station to load departures.",
    app_public_station_selected: "Selected station",
    app_public_station_last: "Last departure",
    app_public_station_upcoming: "Upcoming departures",
    app_public_station_empty: "No departures found for this station today.",
    app_public_station_last_empty: "No earlier departures today.",
    app_public_station_upcoming_empty: "No upcoming departures today.",
    app_public_station_search_success: "Station results updated.",
    app_public_station_departures_loaded: "Station departures updated.",
    app_recent_platform_sightings: "Recent platform sightings",
    app_station_sighting_empty: "No recent platform sightings.",
    app_station_sighting_title: "Report platform sighting",
    app_station_sighting_note: "Choose the departure you saw. Destination filtering is optional. Alerts are shared with riders following the same corridor in either direction and with matching saved routes.",
    app_station_sighting_destination_label: "Destination (optional)",
    app_station_sighting_destination_any: "No destination specified",
    app_station_sighting_submit: "Report platform sighting",
    app_station_sighting_candidates: "Candidate departures",
    app_station_sighting_selected_departure: "Selected departure",
    app_station_sighting_select_departure: "Use this departure",
    app_station_sighting_select_departure_toast: "Select a departure before reporting a platform sighting.",
    app_station_sighting_departures_empty: "No departures in this station window match the current filter.",
    app_station_sighting_success: "Platform sighting reported.",
    app_station_sighting_deduped: "That platform sighting was already captured.",
    app_station_sighting_cooldown: "You can report this platform again in %s min.",
    app_station_sighting_matched: "Matched to a departure.",
    app_station_sighting_unmatched: "Saved as a station-level sighting.",
    app_sighting_metric_zero: "0 sightings",
    app_sighting_metric_one: "1 sighting",
    app_sighting_metric_many: "%s sightings",
    app_sighting_context_open_full: "Open full sightings view",
    app_sighting_context_title: "Sighting context",
    app_sightings_empty: "Select a station from Dashboard before reporting a platform sighting.",
    app_sightings_choose_station: "Choose station",
    app_map_prompt: "Choose a departure to load its stop map.",
    app_map_empty: "No stops are available for this departure right now.",
    app_map_missing_coords: "Map coordinates are unavailable for some stops. The full ordered stop list is still shown below.",
    app_network_map_title: "Network map",
    app_network_map_note: "No active ride is selected. Showing today’s full station map with recent platform sightings.",
    app_network_map_empty: "No station coordinates are available for the network map right now.",
    app_public_network_map_title: "Today’s full network map",
    app_public_network_map_note: "See all stations with coordinates and recent platform sightings across today’s visible departures.",
    app_map_popup_destination: "Destination",
    app_map_popup_status: "Status",
    app_map_popup_age: "Age",
    app_map_popup_seen_at: "Seen at",
    app_map_popup_next_stop: "Next stop",
    app_map_popup_last_update: "Last update",
    app_map_popup_live_train: "Live train",
    app_map_popup_recent_reports: "Recent reports",
    app_map_popup_recent_sightings: "Recent sightings",
    app_map_popup_crew: "Crew",
    app_map_popup_schedule: "Schedule",
    app_map_popup_live_now: "Live now",
    app_map_tag_now: "now",
    app_detail_state_no_reports: "No reports today",
    app_detail_state_last_sighting: "Inspection sighting",
    app_detail_state_mixed_reports: "Mixed reports",
    app_detail_last_update_aria: "Last update %s",
    app_live_overlay_ready: "Live overlay connected.",
    app_live_overlay_connecting: "Live overlay reconnecting.",
    app_live_overlay_offline: "Live overlay unavailable. Local map data is still shown.",
    app_live_overlay_unavailable: "Live overlay disabled.",
    app_stop_list: "Ordered stops",
    app_stop_context_open_full: "Open station sightings",
    app_view_stops_map: "Stops map",
    settings_alerts_label: "Notifications",
    settings_alert_style_label: "Notification style",
    settings_language_label: "Language",
    settings_reports_channel_label: "Reports channel",
    settings_style_detailed_option: "Detailed",
    settings_style_discreet_option: "Discreet",
    btn_open_reports_channel: "Open vivi kontrole Reports",
    link_reports_channel: reportsChannelURL,
    app_relative_now: "just now",
    app_relative_one_min: "1 min ago",
    app_relative_many_mins: "%s min ago",
    app_schedule_unavailable: "Schedule unavailable right now.",
    schedule_fallback_notice: "Showing schedule for %s while new-day data is still loading.",
    btn_open_app: "Open app",
  };

  document.addEventListener("DOMContentLoaded", () => {
    boot().catch((err) => renderFatal(err));
  });

  async function boot() {
    bindGlobalDocumentEvents();
    await loadMessages(state.lang);
    renderLoading();
    startExternalFeedIfNeeded();

    if (cfg.mode === "public-dashboard") {
      try {
        await refreshPublicDashboard();
      } catch (err) {
        rememberErrorStatus(err);
      }
      renderPublicDashboard();
      setInterval(() => refreshPublicDashboard().then(renderPublicDashboard).catch(setStatusFromError), cfg.publicRefreshMs || 30000);
      return;
    }

    if (cfg.mode === "public-train") {
      try {
        await refreshPublicTrain();
      } catch (err) {
        rememberErrorStatus(err);
      }
      renderPublicTrain();
      setInterval(() => refreshPublicTrain().then(renderPublicTrain).catch(setStatusFromError), cfg.publicRefreshMs || 30000);
      return;
    }

    if (cfg.mode === "public-map") {
      try {
        await refreshMapData(cfg.trainId, true);
      } catch (err) {
        rememberErrorStatus(err);
      }
      renderPublicMap();
      setInterval(async () => {
        try {
          if (await refreshMapData(cfg.trainId, true)) {
            renderPublicMap();
          }
        } catch (err) {
          setStatusFromError(err);
        }
      }, cfg.publicRefreshMs || 30000);
      return;
    }

    if (cfg.mode === "public-network-map") {
      try {
        await Promise.all([refreshPublicNetworkMap(), refreshPublicDashboardAll()]);
      } catch (err) {
        rememberErrorStatus(err);
      }
      renderPublicNetworkMap();
      setInterval(async () => {
        try {
          const results = await Promise.all([refreshPublicNetworkMap(), refreshPublicDashboardAll()]);
          if (results.some(Boolean)) {
            renderPublicNetworkMap();
          }
        } catch (err) {
          setStatusFromError(err);
        }
      }, cfg.publicRefreshMs || 30000);
      return;
    }

    if (cfg.mode === "public-stations") {
      renderPublicStationSearch();
      setInterval(async () => {
        try {
          let shouldRender = false;
          if (state.publicStationSelected) {
            shouldRender = await refreshPublicStationDepartures(state.publicStationSelected.id) || shouldRender;
          }
          if (shouldRender) {
            renderPublicStationSearch();
          }
        } catch (err) {
          setStatusFromError(err);
        }
      }, cfg.publicRefreshMs || 30000);
      return;
    }

    await authenticateMiniApp();
    if (!state.authenticated) {
      renderAuthRequired();
      return;
    }
    const initialResults = await Promise.allSettled([refreshWindowTrains(), refreshFavorites(), refreshNetworkMapData(true), refreshPublicDashboardAll()]);
    initialResults.forEach((result) => {
      if (result.status === "rejected") {
        rememberErrorStatus(result.reason);
      }
    });
    renderMiniApp();
    setInterval(async () => {
      if (!state.authenticated) return;
      const previousSelectedTrainId = detailTargetTrainId();
      try {
        await refreshCurrentRide();
        if (state.tab === "dashboard") {
          await refreshWindowTrains();
        }
        if (state.tab === "map") {
          await refreshActiveMapView();
        }
        if (state.tab === "sightings" && state.selectedStation) {
          await fetchStationDepartures(state.selectedStation.id);
        }
        if (state.selectedTrain && state.selectedTrain.trainCard) {
          const activeSelectedTrainId = state.selectedTrain.trainCard.train.id;
          if (!currentRideTrainId() || activeSelectedTrainId !== currentRideTrainId()) {
            const next = await api(`/trains/${encodeURIComponent(activeSelectedTrainId)}/status`);
            if (!samePayloadIgnoringSchedule(state.selectedTrain, next)) {
              state.selectedTrain = next;
            }
          }
        }
        renderMiniApp({ preserveDetail: true, previousSelectedTrainId });
      } catch (err) {
        setStatusFromError(err);
      }
    }, cfg.miniAppRefreshMs || 15000);
  }

  async function authenticateMiniApp() {
    const tg = window.Telegram && window.Telegram.WebApp;
    if (!tg || !tg.initData) {
      state.authenticated = false;
      return;
    }
    tg.ready();
    if (typeof tg.expand === "function") {
      tg.expand();
    }
    const payload = await api("/auth/telegram", {
      method: "POST",
      body: JSON.stringify({ initData: tg.initData }),
    }, true);
    state.authenticated = Boolean(payload && payload.ok);
    const me = await api("/me");
    state.me = me;
    state.currentRide = me.currentRide;
    syncSelectedTrainToCurrentRide({ focusActiveRide: true });
    syncMapSelectionToCurrentRide();
    state.lang = normalizeLang((me.settings && me.settings.language) || payload.lang);
    await loadMessages(state.lang);
  }

  async function loadMessages(lang) {
    state.lang = normalizeLang(lang);
    try {
      const payload = await fetchJSON(`${cfg.basePath}/api/v1/messages?lang=${encodeURIComponent(state.lang)}`, { method: "GET" });
      state.messages = Object.assign({}, fallbackMessages, payload.messages || {});
    } catch (_) {
      state.messages = Object.assign({}, fallbackMessages);
    }
  }

  function startExternalFeedIfNeeded() {
    if (!cfg.externalTrainMapEnabled || externalFeedClient || !window.TrainExternalFeed || typeof window.TrainExternalFeed.createExternalTrainMapClient !== "function") {
      return;
    }
    if (cfg.mode !== "public-network-map" && cfg.mode !== "public-map" && cfg.mode !== "mini-app") {
      return;
    }
    externalFeedClient = window.TrainExternalFeed.createExternalTrainMapClient({
      enabled: cfg.externalTrainMapEnabled,
      baseURL: cfg.externalTrainMapBaseURL,
      wsURL: cfg.externalTrainMapWsURL,
      graphURL: cfg.externalTrainGraphURL || "",
      onState(nextState) {
        if (sameExternalFeedMaterialState(state.externalFeed, nextState || null)) {
          return;
        }
        state.externalFeed = nextState || state.externalFeed;
        scheduleExternalFeedRender();
      },
    });
    externalFeedClient.start().catch((err) => {
      state.externalFeed = Object.assign({}, state.externalFeed, {
        connectionState: "offline",
        error: err && err.message ? err.message : String(err),
      });
      scheduleExternalFeedRender();
    });
  }

  function scheduleExternalFeedRender() {
    if (externalFeedRenderTimer) {
      return;
    }
    externalFeedRenderTimer = window.setTimeout(() => {
      externalFeedRenderTimer = null;
      if (cfg.mode === "public-map") {
        if (!patchPublicMapMainPanel({ mapOnly: true })) {
          renderPublicMap();
        }
        return;
      }
      if (cfg.mode === "public-network-map") {
        if (!patchPublicNetworkMapPanel({ mapOnly: true })) {
          renderPublicNetworkMap();
        }
        return;
      }
      if (cfg.mode === "mini-app" && state.tab === "map") {
        const mainPanel = document.getElementById("mini-app-main-panel");
        if (mainPanel && patchMiniMapPanel(mainPanel)) {
          return;
        }
        renderMiniApp({ preserveDetail: true, previousSelectedTrainId: detailTargetTrainId() });
      }
    }, 250);
  }

  async function refreshPublicDashboard() {
    const payload = await fetchJSON(`${cfg.basePath}/api/v1/public/dashboard`, { method: "GET" });
    state.publicDashboard = Array.isArray(payload.trains) ? payload.trains : [];
    state.statusText = t("app_status_public");
  }

  async function refreshPublicDashboardAll() {
    const previousSchedule = state.scheduleMeta;
    const previousItems = state.publicDashboardAll;
    const payload = await fetchJSON(`${cfg.basePath}/api/v1/public/dashboard?limit=0`, { method: "GET" });
    const nextItems = Array.isArray(payload.trains) ? payload.trains : [];
    const dataChanged = !samePublicDashboardPayload(previousItems, nextItems);
    const scheduleChanged = !sameMaterialValue(previousSchedule, state.scheduleMeta);
    if (dataChanged) {
      state.publicDashboardAll = nextItems;
    }
    return dataChanged || scheduleChanged;
  }

  function applyPublicFilter() {
    state.publicFilter = state.publicFilterDraft;
    renderPublicDashboard({ preserveInputFocus: true });
  }

  async function refreshPublicTrain() {
    const payload = await fetchJSON(`${cfg.basePath}/api/v1/public/trains/${encodeURIComponent(cfg.trainId)}`, { method: "GET" });
    state.publicTrain = payload;
    state.statusText = t("app_status_public");
  }

  async function refreshPublicNetworkMap() {
    const previousSchedule = state.scheduleMeta;
    const previousMapData = state.networkMapData;
    const payload = await fetchJSON(`${cfg.basePath}/api/v1/public/map`, { method: "GET" });
    const nextMapData = payload || null;
    const dataChanged = !sameNetworkMapPayload(previousMapData, nextMapData);
    const scheduleChanged = !sameMaterialValue(previousSchedule, state.scheduleMeta);
    if (dataChanged) {
      state.networkMapData = nextMapData;
    }
    state.statusText = t("app_status_public");
    return dataChanged || scheduleChanged;
  }

  async function searchPublicStations(query) {
    state.publicStationQuery = query || "";
    const payload = await fetchJSON(`${cfg.basePath}/api/v1/public/stations?q=${encodeURIComponent(state.publicStationQuery)}`, { method: "GET" });
    state.publicStationMatches = Array.isArray(payload.stations) ? payload.stations : [];
    renderPublicStationSearch({ preserveInputFocus: true });
  }

  async function refreshPublicStationDepartures(stationId) {
    const previousSchedule = state.scheduleMeta;
    const previousDepartures = state.publicStationDepartures;
    const payload = await fetchJSON(`${cfg.basePath}/api/v1/public/stations/${encodeURIComponent(stationId)}/departures`, { method: "GET" });
    const nextDepartures = payload || null;
    const dataChanged = !samePublicStationDeparturesPayload(previousDepartures, nextDepartures);
    const scheduleChanged = !sameMaterialValue(previousSchedule, state.scheduleMeta);
    if (dataChanged) {
      state.publicStationDepartures = nextDepartures;
      state.publicStationSelected = nextDepartures && nextDepartures.station ? nextDepartures.station : null;
    }
    state.statusText = t("app_status_public");
    return dataChanged || scheduleChanged;
  }

  async function refreshWindowTrains() {
    const payload = await api(`/windows/${encodeURIComponent(state.window)}`);
    state.windowTrains = Array.isArray(payload.trains) ? payload.trains : [];
    state.statusText = t("app_status_ready");
  }

  function applyCurrentRidePayload(payload, options = {}) {
    const previousRideTrainId = currentRideTrainId();
    state.currentRide = payload && payload.currentRide ? payload.currentRide : null;
    if (state.me) {
      state.me.currentRide = state.currentRide;
    }
    syncSelectedTrainToCurrentRide({
      focusActiveRide: Boolean(options.focusActiveRide),
      previousRideTrainId,
    });
    syncMapSelectionToCurrentRide();
  }

  async function refreshCurrentRide(options = {}) {
    const payload = await api("/checkins/current");
    applyCurrentRidePayload(payload, options);
  }

  async function refreshFavorites() {
    const payload = await api("/favorites");
    state.favorites = Array.isArray(payload.favorites) ? payload.favorites : [];
  }

  async function fetchStationMatches(query) {
    const payload = await api(`/stations?q=${encodeURIComponent(query || "")}`);
    state.stations = Array.isArray(payload.stations) ? payload.stations : [];
    state.selectedStation = null;
    state.stationDepartures = [];
    state.selectedCheckInTrainId = "";
    state.checkInDropdownOpen = false;
    state.stationRecentSightings = [];
    state.stationSightingDestinations = [];
    state.stationSightingDestinationId = "";
    state.selectedSightingTrainId = "";
    state.expandedStationContextTrainId = "";
    renderMiniApp();
  }

  async function fetchStationDepartures(stationId) {
    const previousStationId = state.selectedStation && state.selectedStation.id ? state.selectedStation.id : "";
    const previousDestinationId = state.stationSightingDestinationId || "";
    const previousSelectedTrainId = state.selectedSightingTrainId || "";
    const previousCheckInTrainId = state.selectedCheckInTrainId || "";
    const previousExpandedTrainId = state.expandedStationContextTrainId || "";
    const payload = await api(`/stations/${encodeURIComponent(stationId)}/departures`);
    state.selectedStation = payload && payload.station ? payload.station : null;
    state.stationDepartures = Array.isArray(payload.trains) ? payload.trains : [];
    state.checkInDropdownOpen = false;
    state.stationRecentSightings = Array.isArray(payload.recentSightings) ? payload.recentSightings : [];
    state.stationSightingDestinations = [];
    if (state.selectedStation) {
      await fetchStationSightingDestinations(state.selectedStation.id);
    }
    const sameStation = Boolean(state.selectedStation && state.selectedStation.id === previousStationId);
    if (sameStation && previousDestinationId && state.stationSightingDestinations.some((item) => item.id === previousDestinationId)) {
      state.stationSightingDestinationId = previousDestinationId;
    } else {
      state.stationSightingDestinationId = "";
    }
    if (sameStation && previousSelectedTrainId && state.stationDepartures.some((item) => item.trainCard && item.trainCard.train && item.trainCard.train.id === previousSelectedTrainId)) {
      state.selectedSightingTrainId = previousSelectedTrainId;
    } else {
      state.selectedSightingTrainId = "";
    }
    if (sameStation && previousCheckInTrainId && state.stationDepartures.some((item) => item.trainCard && item.trainCard.train && item.trainCard.train.id === previousCheckInTrainId)) {
      state.selectedCheckInTrainId = previousCheckInTrainId;
    } else {
      state.selectedCheckInTrainId = defaultCheckInTrainId(state.stationDepartures);
    }
    if (sameStation && state.stationDepartures.some((item) => item.trainCard && item.trainCard.train && item.trainCard.train.id === previousExpandedTrainId)) {
      state.expandedStationContextTrainId = previousExpandedTrainId;
    } else {
      state.expandedStationContextTrainId = "";
    }
    renderMiniApp();
  }

  async function fetchStationSightingDestinations(stationId) {
    const payload = await api(`/stations/${encodeURIComponent(stationId)}/sighting-destinations`);
    state.stationSightingDestinations = Array.isArray(payload.stations) ? payload.stations : [];
  }

  async function fetchOriginMatches(query) {
    const payload = await api(`/stations?q=${encodeURIComponent(query || "")}`);
    state.originResults = Array.isArray(payload.stations) ? payload.stations : [];
    renderMiniApp();
  }

  async function fetchDestinationMatches(query) {
    if (!state.chosenOrigin) {
      throw new Error(t("app_choose_origin"));
    }
    const payload = await api(`/routes/destinations?originStationId=${encodeURIComponent(state.chosenOrigin.id)}&q=${encodeURIComponent(query || "")}`);
    state.destinationResults = Array.isArray(payload.stations) ? payload.stations : [];
    renderMiniApp();
  }

  async function fetchRouteResults() {
    if (!state.chosenOrigin) throw new Error(t("app_choose_origin"));
    if (!state.chosenDestination) throw new Error(t("app_choose_destination"));
    const payload = await api(`/routes/trains?originStationId=${encodeURIComponent(state.chosenOrigin.id)}&destinationStationId=${encodeURIComponent(state.chosenDestination.id)}`);
    state.routeResults = Array.isArray(payload.trains) ? payload.trains : [];
    renderMiniApp();
  }

  async function openStatus(trainId) {
    state.selectedTrain = await api(`/trains/${encodeURIComponent(trainId)}/status`);
    setPinnedDetailTrain(trainId, { fromUser: true, reason: "open-status" });
    alignMiniMapToSelectedTrain(trainId);
    renderMiniApp();
    return t("app_status_loaded");
  }

  async function refreshMapData(trainId, allowAnonymous, previousTrainIdOverride) {
    const previousTrainId = previousTrainIdOverride || state.mapTrainId || "";
    if (!trainId) {
      const changed = Boolean(state.mapData || state.mapTrainId || state.mapTrainDetail);
      state.mapData = null;
      state.mapTrainId = "";
      state.mapTrainDetail = null;
      state.expandedStopContextKey = "";
      return changed;
    }
    const previousSchedule = state.scheduleMeta;
    const previousMapData = state.mapData;
    const previousMapDetail = state.mapTrainDetail;
    state.mapTrainId = trainId;
    if (previousTrainId && previousTrainId !== trainId) {
      state.expandedStopContextKey = "";
    }
    const path = allowAnonymous
      ? `/public/trains/${encodeURIComponent(trainId)}/stops`
      : `/trains/${encodeURIComponent(trainId)}/stops`;
    const payload = allowAnonymous
      ? await fetchJSON(`${cfg.basePath}/api/v1${path}`, { method: "GET" })
      : await api(path);
    const nextMapData = payload || null;
    let nextMapDetail = null;
    try {
      nextMapDetail = allowAnonymous
        ? await fetchJSON(`${cfg.basePath}/api/v1/public/trains/${encodeURIComponent(trainId)}`, { method: "GET" })
        : await api(`/trains/${encodeURIComponent(trainId)}/status`);
    } catch (_) {
      nextMapDetail = null;
    }
    const mapChanged = !sameTrainStopsPayload(previousMapData, nextMapData);
    const detailChanged = !samePayloadIgnoringSchedule(previousMapDetail, nextMapDetail);
    const scheduleChanged = !sameMaterialValue(previousSchedule, state.scheduleMeta);
    if (mapChanged) {
      state.mapData = nextMapData;
    }
    if (detailChanged) {
      state.mapTrainDetail = nextMapDetail;
    }
    state.statusText = cfg.mode === "mini-app" ? t("app_status_ready") : t("app_status_public");
    return mapChanged || detailChanged || scheduleChanged;
  }

  async function refreshNetworkMapData(allowAnonymous) {
    const previousSchedule = state.scheduleMeta;
    const previousMapData = state.networkMapData;
    const payload = allowAnonymous
      ? await fetchJSON(`${cfg.basePath}/api/v1/public/map`, { method: "GET" })
      : await fetchJSON(`${cfg.basePath}/api/v1/public/map`, { method: "GET" });
    const nextMapData = payload || null;
    const dataChanged = !sameNetworkMapPayload(previousMapData, nextMapData);
    const scheduleChanged = !sameMaterialValue(previousSchedule, state.scheduleMeta);
    if (dataChanged) {
      state.networkMapData = nextMapData;
      state.expandedStopContextKey = "";
    }
    state.statusText = cfg.mode === "mini-app" ? t("app_status_ready") : t("app_status_public");
    return dataChanged || scheduleChanged;
  }

  function selectedTrainId() {
    if (state.selectedTrain && state.selectedTrain.trainCard && state.selectedTrain.trainCard.train) {
      return state.selectedTrain.trainCard.train.id || "";
    }
    return "";
  }

  function normalizeTrainId(trainId) {
    return String(trainId || "").trim();
  }

  function resolveTrainIdFromPayload(payload) {
    if (!payload) {
      return "";
    }
    if (payload.trainCard && payload.trainCard.train && payload.trainCard.train.id) {
      return normalizeTrainId(payload.trainCard.train.id);
    }
    if (payload.train && payload.train.trainCard && payload.train.trainCard.train && payload.train.trainCard.train.id) {
      return normalizeTrainId(payload.train.trainCard.train.id);
    }
    if (payload.train && payload.train.id) {
      return normalizeTrainId(payload.train.id);
    }
    return "";
  }

  function detailTargetTrainId() {
    return state.pinnedDetailTrainId || selectedTrainId();
  }

  function emitTrainStateTransition(eventName, details) {
    if (!state.debugTrainStateTransitions) {
      return;
    }
    const payload = Object.assign({
      event: eventName,
      selectedTrainId: selectedTrainId(),
      pinnedDetailTrainId: state.pinnedDetailTrainId,
      pinnedDetailFromUser: state.pinnedDetailFromUser,
      mapTrainId: state.mapTrainId,
      mapPinnedTrainId: state.mapPinnedTrainId,
      mapFollowTrainId: state.mapFollowTrainId,
      mapFollowPaused: state.mapFollowPaused,
    }, details || {});
    console.debug("[train-app-state]", payload);
  }

  function setPinnedDetailTrain(trainId, options) {
    const nextTrainId = normalizeTrainId(trainId);
    const nextFromUser = Boolean(options && options.fromUser);
    if (state.pinnedDetailTrainId === nextTrainId && state.pinnedDetailFromUser === nextFromUser) {
      return;
    }
    state.pinnedDetailTrainId = nextTrainId;
    state.pinnedDetailFromUser = nextFromUser;
    emitTrainStateTransition("detail-pin", {
      toTrainId: nextTrainId,
      fromUser: nextFromUser,
      reason: options && options.reason ? options.reason : "",
    });
  }

  function clearPinnedDetailTrain(reason) {
    if (!state.pinnedDetailTrainId) {
      return;
    }
    state.pinnedDetailTrainId = "";
    state.pinnedDetailFromUser = false;
    emitTrainStateTransition("detail-unpin", {
      reason: reason || "manual",
      toTrainId: "",
    });
  }

  function setPinnedMapTrain(trainId, reason) {
    const nextTrainId = normalizeTrainId(trainId);
    if (state.mapPinnedTrainId === nextTrainId) {
      return;
    }
    state.mapPinnedTrainId = nextTrainId;
    emitTrainStateTransition("map-pin", {
      reason: reason || "",
      toTrainId: nextTrainId,
    });
  }

  function clearPinnedMapTrain(reason) {
    if (!state.mapPinnedTrainId && !state.mapFollowTrainId && !state.mapFollowPaused) {
      return;
    }
    state.mapPinnedTrainId = "";
    state.mapFollowTrainId = "";
    state.mapFollowPaused = false;
    emitTrainStateTransition("map-unpin", {
      reason: reason || "",
      toTrainId: "",
    });
  }

  function isPinnedTrainKnown(trainId) {
    const target = normalizeTrainId(trainId);
    if (!target) {
      return false;
    }
    if (normalizeTrainId(state.mapTrainId) === target) {
      return true;
    }
    if (resolveTrainIdFromPayload(state.mapData) === target) {
      return true;
    }
    if (resolveTrainIdFromPayload(state.mapTrainDetail) === target) {
      return true;
    }
    if (resolveTrainIdFromPayload(state.currentRide) === target) {
      return true;
    }
    if (resolveTrainIdFromPayload(state.selectedTrain) === target) {
      return true;
    }
    if (Array.isArray(state.windowTrains) && state.windowTrains.some((item) => resolveTrainIdFromPayload(item) === target)) {
      return true;
    }
    if (Array.isArray(state.stationDepartures) && state.stationDepartures.some((item) => resolveTrainIdFromPayload(item) === target)) {
      return true;
    }
    return false;
  }

  function preferredMapTrainId() {
    return state.mapPinnedTrainId || detailTargetTrainId() || currentRideTrainId() || "";
  }

  function setMapFollow(trainId) {
    const nextTrainId = normalizeTrainId(trainId);
    const changed = state.mapFollowTrainId !== nextTrainId;
    if (!changed && !state.mapFollowPaused) {
      return;
    }
    state.mapFollowTrainId = nextTrainId;
    state.mapFollowPaused = false;
    emitTrainStateTransition("map-follow-state", {
      action: changed ? "set" : "refresh",
      mapFollowTrainId: nextTrainId,
    });
  }

  function pauseMiniMapFollow(reason) {
    if (cfg.mode !== "mini-app" || state.tab !== "map" || !state.mapTrainId || state.mapFollowPaused) {
      return;
    }
    state.mapFollowPaused = true;
    emitTrainStateTransition("map-follow-paused", {
      action: "pause",
      reason: reason || "user-moved-map",
      mapFollowTrainId: state.mapFollowTrainId,
    });
  }

  function resetMapFollow(trainId) {
    setMapFollow(trainId);
  }

  function alignMiniMapToSelectedTrain(trainId) {
    if (cfg.mode !== "mini-app") {
      return;
    }
    const nextTrainId = normalizeTrainId(trainId);
    setPinnedMapTrain(nextTrainId, "align-mini-map");
    state.mapTrainId = nextTrainId;
    state.expandedStopContextKey = "";
    resetMapFollow(state.mapTrainId);
  }

  function isPublicMapMode() {
    return cfg.mode === "public-network-map" || cfg.mode === "public-map";
  }

  function isLiveTrainPopupKey(popupKey) {
    return String(popupKey || "").startsWith("live-train:");
  }

  function resetPublicMapSelection() {
    state.publicMapPopupKey = "";
    state.publicMapSelectedMarkerKey = "";
    state.publicMapFollowPaused = false;
  }

  function setPublicMapPopupSelection(popupKey) {
    state.publicMapPopupKey = popupKey || "";
    state.publicMapSelectedMarkerKey = isLiveTrainPopupKey(popupKey) ? popupKey : "";
    state.publicMapFollowPaused = false;
  }

  function clearPublicMapPopupSelection(popupKey) {
    if (popupKey && state.publicMapPopupKey && state.publicMapPopupKey !== popupKey) {
      return;
    }
    resetPublicMapSelection();
  }

  function syncActivePublicMap() {
    if (!isPublicMapMode()) {
      return;
    }
    if (cfg.mode === "public-map") {
      syncMapFromDOM("public-train-map", state.mapData);
    } else {
      syncMapFromDOM("public-network-map", state.networkMapData);
    }
    applyPublicMapFollow();
  }

  function applyPublicMapFollow() {
    if (!isPublicMapMode() || !state.publicMapSelectedMarkerKey || state.publicMapFollowPaused) {
      return;
    }
    if (!mapController.panToMarker(state.publicMapSelectedMarkerKey)) {
      clearPublicMapPopupSelection(state.publicMapPopupKey);
    }
  }

  function syncMapSelectionToCurrentRide() {
    const nextTrainId = preferredMapTrainId();
    if (!nextTrainId) {
      state.mapTrainId = "";
      state.mapData = null;
      state.mapTrainDetail = null;
      return;
    }
    if (state.mapTrainId !== nextTrainId) {
      state.expandedStopContextKey = "";
    }
    state.mapTrainId = nextTrainId;
    emitTrainStateTransition("map-sync", {
      nextTrainId,
    });
  }

  function setMiniAppTab(nextTab, reason) {
    const normalizedTab = String(nextTab || "").trim() || "dashboard";
    if (state.tab === "map" && normalizedTab !== "map") {
      clearPinnedMapTrain(reason || `tab:${normalizedTab}`);
    }
    state.tab = normalizedTab;
  }

  async function refreshActiveMapView() {
    syncMapSelectionToCurrentRide();
    if (state.mapTrainId) {
      return refreshMapData(state.mapTrainId);
    }
    const results = await Promise.all([refreshNetworkMapData(true), refreshPublicDashboardAll()]);
    return results.some(Boolean);
  }

  async function openMap(trainId) {
    const previousMapTrainId = state.mapTrainId || "";
    alignMiniMapToSelectedTrain(trainId);
    await refreshMapData(trainId, false, previousMapTrainId);
    setMiniAppTab("map", "open-map");
    renderMiniApp();
    return t("app_map_loaded");
  }

  async function checkIn(trainId, boardingStationId) {
    const payload = await api("/checkins/current", {
      method: "PUT",
      body: JSON.stringify({
        trainId,
        boardingStationId: boardingStationId || "",
      }),
    });
    applyCurrentRidePayload(payload, { focusActiveRide: true });
    state.checkInDropdownOpen = false;
    setMiniAppTab("dashboard", "check-in");
    state.statusText = t("app_checked_in");
    renderMiniApp();
    return t("app_checked_in");
  }

  async function checkoutRide() {
    await api("/checkins/current", { method: "DELETE" });
    await refreshCurrentRide();
    state.statusText = t("app_checked_out");
    renderMiniApp();
    return t("app_checked_out");
  }

  async function undoCheckout() {
    const payload = await api("/checkins/current/undo", { method: "POST" });
    if (payload.restored) {
      await refreshCurrentRide({ focusActiveRide: true });
      setMiniAppTab("dashboard", "undo-checkout");
      state.statusText = t("app_undo_restored");
      renderMiniApp();
      return t("app_undo_restored");
    }
    return null;
  }

  async function submitReport(signal) {
    if (!state.currentRide || !state.currentRide.checkIn) {
      throw new Error(t("app_current_ride_none"));
    }
    const trainId = state.currentRide.checkIn.trainInstanceId;
    const payload = await api(`/trains/${encodeURIComponent(trainId)}/reports`, {
      method: "POST",
      body: JSON.stringify({ signal }),
    });
    let message = t("app_report_success");
    let kind = "success";
    if (payload.deduped) {
      message = t("app_report_deduped");
      kind = "info";
    } else if (payload.cooldownRemaining > 0) {
      const mins = Math.max(1, Math.ceil(Number(payload.cooldownRemaining) / 60000000000));
      message = t("app_report_cooldown", mins);
      kind = "info";
    }
    state.statusText = message;
    await refreshCurrentRide();
    if (state.selectedTrain && state.selectedTrain.trainCard && state.selectedTrain.trainCard.train.id === trainId) {
      state.selectedTrain = await api(`/trains/${encodeURIComponent(trainId)}/status`);
    }
    if (state.mapTrainId === trainId) {
      await refreshMapData(trainId);
    }
    renderMiniApp();
    return { message, kind };
  }

  async function submitStationSighting() {
    if (!state.selectedStation || !state.selectedStation.id) {
      throw new Error(t("app_public_station_prompt"));
    }
    if (!state.selectedSightingTrainId) {
      throw new Error(t("app_station_sighting_select_departure_toast"));
    }
    const payload = await api(`/stations/${encodeURIComponent(state.selectedStation.id)}/sightings`, {
      method: "POST",
      body: JSON.stringify({
        destinationStationId: state.stationSightingDestinationId || "",
        trainId: state.selectedSightingTrainId || "",
      }),
    });
    let message = t("app_station_sighting_success");
    let kind = "success";
    if (payload.deduped) {
      message = t("app_station_sighting_deduped");
      kind = "info";
    } else if (payload.cooldownRemaining > 0) {
      const mins = Math.max(1, Math.ceil(Number(payload.cooldownRemaining) / 60000000000));
      message = t("app_station_sighting_cooldown", mins);
      kind = "info";
    } else if (payload.event && payload.event.matchedTrainInstanceId) {
      message = `${t("app_station_sighting_success")} ${t("app_station_sighting_matched")}`;
    } else {
      message = `${t("app_station_sighting_success")} ${t("app_station_sighting_unmatched")}`;
    }

    await fetchStationDepartures(state.selectedStation.id);
    await refreshNetworkMapData(true);
    if (payload.event && payload.event.matchedTrainInstanceId) {
      const matchedTrainId = payload.event.matchedTrainInstanceId;
      if (state.selectedTrain && state.selectedTrain.trainCard && state.selectedTrain.trainCard.train.id === matchedTrainId) {
        state.selectedTrain = await api(`/trains/${encodeURIComponent(matchedTrainId)}/status`);
      }
      if (state.mapTrainId === matchedTrainId) {
        await refreshMapData(matchedTrainId);
      }
    }
    state.statusText = message;
    renderMiniApp();
    return { message, kind };
  }

  async function muteTrain(trainId) {
    await api(`/trains/${encodeURIComponent(trainId)}/mute`, {
      method: "PUT",
      body: JSON.stringify({ durationMinutes: 30 }),
    });
    state.statusText = t("app_muted");
    renderMiniApp();
    return t("app_muted");
  }

  async function saveFavorite(fromStationId, toStationId) {
    await api("/favorites", {
      method: "PUT",
      body: JSON.stringify({ fromStationId, toStationId }),
    });
    await refreshFavorites();
    state.statusText = t("app_favorite_saved");
    renderMiniApp();
    return t("app_favorite_saved");
  }

  async function removeFavorite(fromStationId, toStationId) {
    await api("/favorites", {
      method: "DELETE",
      body: JSON.stringify({ fromStationId, toStationId }),
    });
    await refreshFavorites();
    state.statusText = t("app_favorite_removed");
    renderMiniApp();
    return t("app_favorite_removed");
  }

  async function saveSettings() {
    const alertsEnabled = document.getElementById("settings-alerts").checked;
    const alertStyle = document.getElementById("settings-style").value;
    const language = document.getElementById("settings-language").value;
    const payload = await api("/settings", {
      method: "PATCH",
      body: JSON.stringify({ alertsEnabled, alertStyle, language }),
    });
    state.me = state.me || {};
    state.me.settings = payload;
    state.lang = normalizeLang(payload.language);
    await loadMessages(state.lang);
    state.statusText = t("app_settings_saved");
    renderMiniApp();
    return t("app_settings_saved");
  }

  async function api(path, options = {}, allowAnonymous) {
    if (!state.authenticated && !allowAnonymous) {
      throw new Error(t("app_auth_required"));
    }
    return fetchJSON(`${cfg.basePath}/api/v1${path}`, options);
  }

  async function fetchJSON(url, options) {
    const response = await fetch(url, Object.assign({
      headers: { "Content-Type": "application/json" },
      credentials: "include",
    }, options || {}));
    const text = await response.text();
    let payload = {};
    try {
      payload = text ? JSON.parse(text) : {};
    } catch (_) {
      payload = {};
    }
    if (!response.ok) {
      const err = new Error(payload.error || t("app_status_error_with_code", response.status));
      err.status = response.status;
      throw err;
    }
    if (payload && payload.schedule) {
      state.scheduleMeta = payload.schedule;
    }
    return payload;
  }

  function rememberErrorStatus(err) {
    if (err && err.status === 503) {
      state.scheduleMeta = null;
    }
    state.statusText = err && err.message ? err.message : String(err);
  }

  function rerenderCurrent(options) {
    if (cfg.mode === "mini-app") {
      renderMiniApp();
      return;
    }
    if (cfg.mode === "public-dashboard") {
      renderPublicDashboard(options);
      return;
    }
    if (cfg.mode === "public-stations") {
      renderPublicStationSearch(options);
      return;
    }
    if (cfg.mode === "public-network-map") {
      renderPublicNetworkMap();
      return;
    }
    if (cfg.mode === "public-map") {
      renderPublicMap();
      return;
    }
    renderPublicTrain();
  }

  function currentRideTrainId() {
    if (state.currentRide && state.currentRide.train && state.currentRide.train.trainCard && state.currentRide.train.trainCard.train) {
      return state.currentRide.train.trainCard.train.id || "";
    }
    if (state.currentRide && state.currentRide.checkIn) {
      return state.currentRide.checkIn.trainInstanceId || "";
    }
    return "";
  }

  function syncSelectedTrainToCurrentRide(options = {}) {
    const activeSelectedTrainId = selectedTrainId();
    const nextRideTrainId = currentRideTrainId();
    const currentRideTrain = state.currentRide && state.currentRide.train ? state.currentRide.train : null;
    const pinnedTrainId = state.pinnedDetailTrainId;
    const previousRideTrainId = options.previousRideTrainId || "";
    const rideTransition = Boolean(previousRideTrainId) && previousRideTrainId !== nextRideTrainId;

    if (state.pinnedDetailFromUser && pinnedTrainId) {
      if (!isPinnedTrainKnown(pinnedTrainId)) {
        clearPinnedDetailTrain("pinned-train-not-known");
      } else {
        emitTrainStateTransition("detail-sync-preserve", {
          reason: "user-pinned",
          pinnedTrainId,
        });
        return;
      }
    }

    if (rideTransition && !state.pinnedDetailFromUser && nextRideTrainId) {
      setMapFollow(nextRideTrainId);
    }

    if (currentRideTrain && (options.focusActiveRide || !state.selectedTrain || activeSelectedTrainId === nextRideTrainId)) {
      state.selectedTrain = currentRideTrain;
      setPinnedDetailTrain(nextRideTrainId, { fromUser: false, reason: "current-ride-sync" });
      emitTrainStateTransition("detail-sync-ride", {
        toTrainId: nextRideTrainId,
        previousRideTrainId,
        focusActiveRide: Boolean(options.focusActiveRide),
      });
      return;
    }
    if (!nextRideTrainId && options.previousRideTrainId && activeSelectedTrainId === options.previousRideTrainId) {
      clearPinnedDetailTrain("ride-ended");
      state.selectedTrain = null;
      emitTrainStateTransition("detail-sync-ride-clear", {
        fromTrainId: activeSelectedTrainId,
      });
    }
  }

  function showToast(message, kind) {
    if (!message) return;
    state.toast = { message, kind: kind || "success" };
    if (toastTimer) {
      clearTimeout(toastTimer);
    }
    rerenderCurrent({ preserveInputFocus: true });
    toastTimer = setTimeout(() => {
      state.toast = null;
      rerenderCurrent({ preserveInputFocus: true });
    }, 3200);
  }

  async function runUserAction(action, success) {
    try {
      const result = await action();
      const toast = typeof success === "function" ? success(result) : success;
      if (toast) {
        if (typeof toast === "string") {
          showToast(toast, "success");
        } else {
          showToast(toast.message, toast.kind || "success");
        }
      }
      return result;
    } catch (err) {
      setStatusFromError(err);
      showToast(err && err.message ? err.message : t("app_status_error"), "error");
      return null;
    }
  }

  function detachMapHost() {
    mapController.detach();
  }

  function setAppHTML(html) {
    detachMapHost();
    appEl.innerHTML = html;
  }

  function syncMapFromDOM(containerId, mapModel) {
    mapController.sync(containerId, mapModel);
  }

  function materialCompare(methodName, left, right) {
    const api = externalFeedAPI();
    if (api && typeof api[methodName] === "function") {
      return api[methodName](left, right);
    }
    return JSON.stringify(left) === JSON.stringify(right);
  }

  function sameMaterialValue(left, right) {
    return materialCompare("sameMaterialValue", left, right);
  }

  function samePayloadIgnoringSchedule(left, right) {
    const api = externalFeedAPI();
    if (api && typeof api.sameMaterialValue === "function") {
      return api.sameMaterialValue(left, right, { schedule: true, generatedAt: true });
    }
    return JSON.stringify(left) === JSON.stringify(right);
  }

  function sameTrainStopsPayload(left, right) {
    return materialCompare("sameTrainStopsPayload", left, right);
  }

  function sameNetworkMapPayload(left, right) {
    return materialCompare("sameNetworkMapPayload", left, right);
  }

  function samePublicDashboardPayload(left, right) {
    return materialCompare("samePublicDashboard", left, right);
  }

  function samePublicStationDeparturesPayload(left, right) {
    return materialCompare("samePublicStationDepartures", left, right);
  }

  function sameExternalFeedMaterialState(left, right) {
    return materialCompare("sameExternalFeedState", left, right);
  }

  function materialMapConfigKey(config) {
    const api = externalFeedAPI();
    if (api && typeof api.mapConfigSignature === "function") {
      return api.mapConfigSignature(config);
    }
    return JSON.stringify(config);
  }

  function materialMarkerSignature(item) {
    const api = externalFeedAPI();
    if (api && typeof api.mapConfigMarkerSignature === "function") {
      return api.mapConfigMarkerSignature(item);
    }
    return JSON.stringify(item);
  }

  function markerReconcilePlan(previousItems, nextItems, openPopupKey) {
    const api = externalFeedAPI();
    if (api && typeof api.planMarkerReconcile === "function") {
      return api.planMarkerReconcile(previousItems, nextItems, openPopupKey);
    }
    return {
      addKeys: [],
      updateKeys: [],
      removeKeys: [],
      retainPopupKey: "",
      clearPopup: false,
      hasChanges: true,
    };
  }

  function markerMovementTimestampMs(item) {
    const candidates = [
      item && item.movementObservedAt,
      item && item.updatedAt,
      item && item.external && item.external.updatedAt,
    ];
    for (const value of candidates) {
      if (!value) {
        continue;
      }
      const timestampMs = new Date(value).getTime();
      if (Number.isFinite(timestampMs)) {
        return timestampMs;
      }
    }
    return 0;
  }

  function markerMovementDurationMs(previousTimestampMs, nextTimestampMs, fallbackElapsedMs) {
    if (
      Number.isFinite(previousTimestampMs) &&
      previousTimestampMs > 0 &&
      Number.isFinite(nextTimestampMs) &&
      nextTimestampMs > previousTimestampMs
    ) {
      return Math.min(
        MAP_MARKER_ANIMATION_MAX_MS,
        Math.max(MAP_MARKER_ANIMATION_MIN_MS, Math.round((nextTimestampMs - previousTimestampMs) * 0.85))
      );
    }
    if (Number.isFinite(fallbackElapsedMs) && fallbackElapsedMs > 0) {
      return Math.min(
        MAP_MARKER_ANIMATION_MAX_MS,
        Math.max(MAP_MARKER_ANIMATION_MIN_MS, Math.round(fallbackElapsedMs * 0.85))
      );
    }
    return MAP_MARKER_ANIMATION_DEFAULT_MS;
  }

  function markerLatLngFromLeaflet(marker) {
    if (!marker || typeof marker.getLatLng !== "function") {
      return null;
    }
    const latLng = marker.getLatLng();
    if (!latLng || !Number.isFinite(latLng.lat) || !Number.isFinite(latLng.lng)) {
      return null;
    }
    return [latLng.lat, latLng.lng];
  }

  function markerLatLngEqual(left, right) {
    if (!Array.isArray(left) || !Array.isArray(right) || left.length !== 2 || right.length !== 2) {
      return false;
    }
    return Math.abs(left[0] - right[0]) <= MAP_MARKER_COORD_EPSILON
      && Math.abs(left[1] - right[1]) <= MAP_MARKER_COORD_EPSILON;
  }

  function markerMotionEase(progress) {
    if (progress <= 0) {
      return 0;
    }
    if (progress >= 1) {
      return 1;
    }
    if (progress < 0.5) {
      return 4 * progress * progress * progress;
    }
    return 1 - Math.pow(-2 * progress + 2, 3) / 2;
  }

  function createMapController() {
    return {
      map: null,
      hostEl: null,
      tileLayer: null,
      baseLayer: null,
      sightingLayer: null,
      trainLayer: null,
      routeLine: null,
      containerId: "",
      modelKey: "",
      viewKey: "",
      routeSignature: "",
      viewCache: new Map(),
      markerIndex: new Map(),
      markerState: new Map(),
      baseMarkerKeys: new Set(),
      sightingMarkerKeys: new Set(),
      trainMarkerKeys: new Set(),
      openPopupKey: "",
      pendingPopupKey: "",
      syncingLayers: false,
      programmaticViewUntil: 0,
      layoutFrame: 0,

      ensureHost() {
        if (this.hostEl) {
          return this.hostEl;
        }
        const hostEl = document.createElement("div");
        hostEl.className = "train-map";
        this.hostEl = hostEl;
        return hostEl;
      },

      ensureMap() {
        if (this.map || !window.L) {
          return this.map;
        }
        const map = window.L.map(this.ensureHost(), {
          zoomControl: true,
          closePopupOnClick: true,
        });
        this.tileLayer = window.L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
          attribution: "&copy; OpenStreetMap contributors",
          maxZoom: 19,
        }).addTo(map);
        this.baseLayer = window.L.layerGroup().addTo(map);
        this.sightingLayer = window.L.layerGroup().addTo(map);
        this.trainLayer = window.L.layerGroup().addTo(map);
        const popupKeyFromEvent = (event) => event && event.popup && event.popup.options
          ? event.popup.options.popupKey || ""
          : "";
        const persistView = () => {
          if (this.isProgrammaticViewChange()) {
            return;
          }
          this.saveCurrentView();
          if (isPublicMapMode() && state.publicMapSelectedMarkerKey) {
            if (!state.publicMapFollowPaused) {
              state.publicMapFollowPaused = true;
            }
          }
        };
        map.on("moveend", persistView);
        map.on("zoomend", persistView);
        map.on("dragend", () => {
          pauseMiniMapFollow("user-dragged-map");
        });
        map.on("popupopen", (event) => {
          const popupKey = popupKeyFromEvent(event);
          if (popupKey) {
            this.openPopupKey = popupKey;
            if (isPublicMapMode()) {
              setPublicMapPopupSelection(popupKey);
            }
          }
        });
        map.on("popupclose", (event) => {
          const popupKey = popupKeyFromEvent(event);
          if (!this.syncingLayers && popupKey && this.openPopupKey === popupKey) {
            this.openPopupKey = "";
            if (isPublicMapMode()) {
              clearPublicMapPopupSelection(popupKey);
            }
          }
        });
        this.map = map;
        return this.map;
      },

      detach() {
        if (this.layoutFrame) {
          window.cancelAnimationFrame(this.layoutFrame);
          this.layoutFrame = 0;
        }
        if (this.hostEl && this.hostEl.parentNode) {
          this.hostEl.parentNode.removeChild(this.hostEl);
        }
        this.containerId = "";
      },

      storageKey(viewKey) {
        return `pixel-train-map-view:${viewKey}`;
      },

      loadStoredView(viewKey) {
        if (!viewKey) {
          return null;
        }
        if (this.viewCache.has(viewKey)) {
          return this.viewCache.get(viewKey);
        }
        try {
          const raw = window.localStorage.getItem(this.storageKey(viewKey));
          if (!raw) {
            return null;
          }
          const parsed = JSON.parse(raw);
          if (!parsed || !Array.isArray(parsed.center) || parsed.center.length !== 2) {
            return null;
          }
          const lat = Number(parsed.center[0]);
          const lng = Number(parsed.center[1]);
          const zoom = Number(parsed.zoom);
          if (!Number.isFinite(lat) || !Number.isFinite(lng) || !Number.isFinite(zoom)) {
            return null;
          }
          const view = { center: [lat, lng], zoom: zoom };
          this.viewCache.set(viewKey, view);
          return view;
        } catch (_) {
          return null;
        }
      },

      saveCurrentView() {
        if (!this.map || !this.viewKey || !this.map._loaded) {
          return;
        }
        const center = this.map.getCenter();
        const view = {
          center: [roundMapCoord(center.lat), roundMapCoord(center.lng)],
          zoom: this.map.getZoom(),
        };
        this.viewCache.set(this.viewKey, view);
        try {
          window.localStorage.setItem(this.storageKey(this.viewKey), JSON.stringify(view));
        } catch (_) {
          // Ignore storage failures and keep the in-memory view only.
        }
      },

      isProgrammaticViewChange() {
        return Date.now() < this.programmaticViewUntil;
      },

      markProgrammaticView() {
        this.programmaticViewUntil = Date.now() + 300;
      },

      applySavedView(view) {
        if (!this.map || !view) {
          return;
        }
        this.markProgrammaticView();
        this.map.setView(view.center, view.zoom, { animate: false });
        this.saveCurrentView();
      },

      panToMarker(markerKey) {
        if (!this.map || !markerKey || !this.markerIndex.has(markerKey)) {
          return false;
        }
        const marker = this.markerIndex.get(markerKey);
        const entry = this.markerState.get(markerKey) || null;
        const targetLatLng = entry && Array.isArray(entry.targetLatLng) && entry.targetLatLng.length === 2
          ? window.L.latLng(entry.targetLatLng[0], entry.targetLatLng[1])
          : null;
        if (!targetLatLng && (!marker || typeof marker.getLatLng !== "function")) {
          return false;
        }
        this.markProgrammaticView();
        this.map.panTo(targetLatLng || marker.getLatLng(), { animate: false });
        return true;
      },

      currentPopupKey() {
        return this.openPopupKey || "";
      },

      reset() {
        if (this.layoutFrame) {
          window.cancelAnimationFrame(this.layoutFrame);
          this.layoutFrame = 0;
        }
        if (this.map && typeof this.map.remove === "function") {
          this.map.remove();
        }
        this.map = null;
        this.tileLayer = null;
        this.baseLayer = null;
        this.sightingLayer = null;
        this.trainLayer = null;
        this.routeLine = null;
        this.containerId = "";
        this.modelKey = "";
        this.viewKey = "";
        this.routeSignature = "";
        this.markerState.forEach((entry) => {
          this.cancelMarkerAnimation(entry);
        });
        this.markerIndex.clear();
        this.markerState.clear();
        this.baseMarkerKeys.clear();
        this.sightingMarkerKeys.clear();
        this.trainMarkerKeys.clear();
        this.openPopupKey = "";
        this.pendingPopupKey = "";
        this.syncingLayers = false;
        this.programmaticViewUntil = 0;
        if (isPublicMapMode()) {
          resetPublicMapSelection();
        }
        if (this.hostEl && this.hostEl.parentNode) {
          this.hostEl.parentNode.removeChild(this.hostEl);
        }
        this.hostEl = null;
      },

      sync(containerId, mapModel) {
        const container = document.getElementById(containerId);
        if (!container || !mapModel || !window.L) {
          return;
        }
        const config = mapModel.train ? buildTrainMapConfig(mapModel) : buildNetworkMapConfig(mapModel);
        if (!config.bounds.length) {
          return;
        }
        const hostEl = this.ensureHost();
        if (hostEl.parentNode !== container) {
          container.appendChild(hostEl);
        }
        this.ensureMap();
        const viewChanged = this.viewKey !== config.viewKey;
        const savedView = viewChanged ? this.loadStoredView(config.viewKey) : null;
        if (viewChanged) {
          if (this.map && this.map.closePopup) {
            this.map.closePopup();
          }
          this.openPopupKey = "";
          this.pendingPopupKey = "";
        }
        const nextModelKey = config.modelKey;
        const modelChanged = this.modelKey !== nextModelKey;
        const shouldRestore = viewChanged && Boolean(savedView);
        const shouldFit = viewChanged && !savedView;

        this.containerId = containerId;
        this.modelKey = nextModelKey;
        this.viewKey = config.viewKey;
        if (!viewChanged && !modelChanged) {
          this.scheduleLayout({
            shouldFit: false,
            shouldRestore: false,
            savedView: null,
            bounds: config.bounds,
          });
          return;
        }
        try {
          this.updateLayers(config);
          this.scheduleLayout({
            shouldFit: shouldFit,
            shouldRestore: shouldRestore,
            savedView: savedView,
            bounds: config.bounds,
          });
        } catch (err) {
          this.reset();
          const retryContainer = document.getElementById(containerId);
          if (!retryContainer) {
            throw err;
          }
          const retryHost = this.ensureHost();
          if (retryHost.parentNode !== retryContainer) {
            retryContainer.appendChild(retryHost);
          }
          this.ensureMap();
          this.containerId = containerId;
          this.modelKey = nextModelKey;
          this.viewKey = config.viewKey;
          this.updateLayers(config);
          this.scheduleLayout({
            shouldFit: shouldFit,
            shouldRestore: shouldRestore,
            savedView: savedView,
            bounds: config.bounds,
          });
        }
      },

      scheduleLayout(options) {
        if (this.layoutFrame) {
          window.cancelAnimationFrame(this.layoutFrame);
        }
        this.layoutFrame = window.requestAnimationFrame(() => {
          this.layoutFrame = 0;
          if (!this.map) {
            return;
          }
          this.map.invalidateSize(false);
          if (options && options.shouldRestore && options.savedView) {
            this.applySavedView(options.savedView);
          } else if (options && options.shouldFit) {
            this.fitBounds(options.bounds);
          }
          this.restorePendingPopup();
        });
      },

      updateLayers(config) {
        if (!this.map) {
          return;
        }
        this.syncingLayers = true;
        this.pendingPopupKey = "";
        try {
          this.reconcileRouteLine(config.polyline);
          this.reconcileMarkerLayer("base", this.baseLayer, config.baseMarkers, "base-marker");
          this.reconcileMarkerLayer("sighting", this.sightingLayer, config.sightingMarkers, "sighting-marker");
          this.reconcileMarkerLayer("train", this.trainLayer, config.trainMarkers || [], "train-marker");
        } finally {
          this.syncingLayers = false;
        }
      },

      markerKeysForLayer(layerKey) {
        if (layerKey === "base") {
          return this.baseMarkerKeys;
        }
        if (layerKey === "sighting") {
          return this.sightingMarkerKeys;
        }
        return this.trainMarkerKeys;
      },

      reconcileRouteLine(polyline) {
        const nextPolyline = Array.isArray(polyline) ? polyline : [];
        const nextSignature = materialMapConfigKey({ polyline: nextPolyline });
        if (this.routeSignature === nextSignature) {
          return;
        }
        if (this.routeLine) {
          this.map.removeLayer(this.routeLine);
          this.routeLine = null;
        }
        if (nextPolyline.length > 1) {
          try {
            this.routeLine = window.L.polyline(nextPolyline, { color: "#9f3d22", weight: 4, opacity: 0.9 }).addTo(this.map);
          } catch (err) {
            throw new Error(`route-line: ${err && err.message ? err.message : String(err)}`);
          }
        }
        this.routeSignature = nextSignature;
      },

      reconcileMarkerLayer(layerKey, layer, items, debugKey) {
        const nextItems = Array.isArray(items) ? items : [];
        const markerKeys = this.markerKeysForLayer(layerKey);
        const previousItems = Array.from(markerKeys)
          .map((key) => this.markerState.has(key) ? this.markerState.get(key).item : null)
          .filter(Boolean);
        const plan = markerReconcilePlan(previousItems, nextItems, this.openPopupKey);
        const addKeys = new Set(plan.addKeys);
        const updateKeys = new Set(plan.updateKeys);

        if (!plan.hasChanges) {
          return;
        }

        nextItems.forEach((item) => {
          const markerKey = item && item.markerKey ? item.markerKey : "";
          if (!markerKey) {
            return;
          }
          let entry = this.markerState.get(markerKey) || null;
          if (entry && entry.layerKey !== layerKey) {
            this.removeMarkerEntry(markerKey, { preservePopup: markerKey === this.openPopupKey });
            entry = null;
          }
          if (addKeys.has(markerKey) || !entry) {
            this.addMarker(layerKey, layer, item, debugKey);
            markerKeys.add(markerKey);
            return;
          }
          if (updateKeys.has(markerKey)) {
            this.updateMarkerEntry(entry, item, debugKey);
          }
          entry = this.markerState.get(markerKey);
          if (entry) {
            entry.layerKey = layerKey;
            entry.layer = layer;
            entry.item = item;
            this.markerIndex.set(markerKey, entry.marker);
            markerKeys.add(markerKey);
          }
        });

        plan.removeKeys.forEach((markerKey) => {
          this.removeMarkerEntry(markerKey, { preservePopup: markerKey === this.openPopupKey });
          markerKeys.delete(markerKey);
        });
      },

      addMarker(layerKey, layer, item, debugKey) {
        try {
          const marker = this.buildMarker(item);
          this.bindMarkerPopup(marker, item);
          if (item.markerKey) {
            this.markerIndex.set(item.markerKey, marker);
            this.markerState.set(item.markerKey, {
              layerKey: layerKey,
              layer: layer,
              marker: marker,
              signature: materialMarkerSignature(item),
              item: item,
              animationFrame: 0,
              positionLatLng: Array.isArray(item.latLng) ? item.latLng.slice() : markerLatLngFromLeaflet(marker),
              targetLatLng: Array.isArray(item.latLng) ? item.latLng.slice() : markerLatLngFromLeaflet(marker),
              positionObservedAt: markerMovementTimestampMs(item),
              lastMovementSyncAt: Date.now(),
            });
          }
          marker.addTo(layer);
        } catch (err) {
          throw new Error(`${debugKey} ${item.latLng.join(",")}: ${err && err.message ? err.message : String(err)}`);
        }
      },

      removeMarkerEntry(markerKey, options) {
        if (!markerKey || !this.markerState.has(markerKey)) {
          return;
        }
        const entry = this.markerState.get(markerKey);
        this.cancelMarkerAnimation(entry);
        if (entry && entry.layer && entry.marker) {
          entry.layer.removeLayer(entry.marker);
        }
        this.markerState.delete(markerKey);
        this.markerIndex.delete(markerKey);
        this.baseMarkerKeys.delete(markerKey);
        this.sightingMarkerKeys.delete(markerKey);
        this.trainMarkerKeys.delete(markerKey);
        if (options && options.preservePopup) {
          this.pendingPopupKey = markerKey;
        }
      },

      updateMarkerEntry(entry, item, debugKey) {
        if (!entry || !entry.marker) {
          this.addMarker(entry ? entry.layerKey : "base", entry ? entry.layer : this.baseLayer, item, debugKey);
          return;
        }
        const signature = materialMarkerSignature(item);
        if (entry.signature === signature) {
          entry.item = item;
          return;
        }
        const marker = entry.marker;
        const canReuse = entry.item && entry.item.kind === item.kind;
        if (!canReuse) {
          const markerKey = item && item.markerKey ? item.markerKey : "";
          const preservePopup = markerKey && markerKey === this.openPopupKey;
          this.removeMarkerEntry(markerKey, { preservePopup: preservePopup });
          this.addMarker(entry.layerKey, entry.layer, item, debugKey);
          return;
        }
        if (typeof marker.setLatLng === "function" && Array.isArray(item.latLng)) {
          if (item.animateMovement) {
            this.animateMarkerTo(entry, item.latLng, item);
          } else {
            this.cancelMarkerAnimation(entry);
            marker.setLatLng(item.latLng);
            entry.positionLatLng = item.latLng.slice();
            entry.targetLatLng = item.latLng.slice();
            entry.positionObservedAt = markerMovementTimestampMs(item);
            entry.lastMovementSyncAt = Date.now();
          }
        }
        if ((item.kind === "html" || item.kind === "tag") && typeof marker.setIcon === "function") {
          marker.setIcon(this.buildMarkerIcon(item));
        }
        if (typeof marker.setZIndexOffset === "function") {
          marker.setZIndexOffset(item.zIndexOffset || 0);
        }
        this.bindMarkerPopup(marker, item);
        entry.signature = signature;
        entry.item = item;
      },

      cancelMarkerAnimation(entry) {
        if (!entry || !entry.animationFrame || typeof window.cancelAnimationFrame !== "function") {
          if (entry) {
            entry.animationFrame = 0;
          }
          return;
        }
        window.cancelAnimationFrame(entry.animationFrame);
        entry.animationFrame = 0;
      },

      animateMarkerTo(entry, nextLatLng, item) {
        if (!entry || !entry.marker || !Array.isArray(nextLatLng) || nextLatLng.length !== 2) {
          return;
        }
        const marker = entry.marker;
        const startLatLng = markerLatLngFromLeaflet(marker) || (Array.isArray(entry.positionLatLng) ? entry.positionLatLng.slice() : null);
        const observedAt = markerMovementTimestampMs(item);
        const now = Date.now();
        const durationMs = markerMovementDurationMs(
          entry.positionObservedAt,
          observedAt,
          entry.lastMovementSyncAt ? now - entry.lastMovementSyncAt : 0
        );
        entry.targetLatLng = nextLatLng.slice();
        entry.positionObservedAt = observedAt;
        entry.lastMovementSyncAt = now;
        if (!startLatLng || markerLatLngEqual(startLatLng, nextLatLng) || typeof window.requestAnimationFrame !== "function") {
          this.cancelMarkerAnimation(entry);
          marker.setLatLng(nextLatLng);
          entry.positionLatLng = nextLatLng.slice();
          entry.targetLatLng = nextLatLng.slice();
          return;
        }
        this.cancelMarkerAnimation(entry);
        const startTime = window.performance && typeof window.performance.now === "function"
          ? window.performance.now()
          : now;
        const tick = (frameNow) => {
          const elapsed = frameNow - startTime;
          const progress = Math.min(1, elapsed / durationMs);
          const eased = markerMotionEase(progress);
          const lat = startLatLng[0] + ((nextLatLng[0] - startLatLng[0]) * eased);
          const lng = startLatLng[1] + ((nextLatLng[1] - startLatLng[1]) * eased);
          marker.setLatLng([lat, lng]);
          entry.positionLatLng = [lat, lng];
          if (progress >= 1) {
            entry.animationFrame = 0;
            entry.positionLatLng = nextLatLng.slice();
            entry.targetLatLng = nextLatLng.slice();
            return;
          }
          entry.animationFrame = window.requestAnimationFrame(tick);
        };
        entry.animationFrame = window.requestAnimationFrame(tick);
      },

      restorePendingPopup() {
        if (!this.pendingPopupKey) {
          return;
        }
        if (this.markerIndex.has(this.pendingPopupKey)) {
          this.markerIndex.get(this.pendingPopupKey).openPopup();
          this.openPopupKey = this.pendingPopupKey;
        } else if (this.openPopupKey === this.pendingPopupKey) {
          this.openPopupKey = "";
          if (isPublicMapMode()) {
            clearPublicMapPopupSelection(this.pendingPopupKey);
          }
        }
        this.pendingPopupKey = "";
      },

      popupOptions(item) {
        return Object.assign({
          autoClose: true,
          closeButton: false,
          autoPan: false,
          className: "map-popup",
          popupKey: item && item.markerKey ? item.markerKey : "",
        }, item && item.popupOptions ? item.popupOptions : {});
      },

      bindMarkerPopup(marker, item) {
        if (!marker) {
          return;
        }
        const popup = typeof marker.getPopup === "function" ? marker.getPopup() : null;
        if (!popup) {
          marker.bindPopup(item.popupHTML, this.popupOptions(item));
          return;
        }
        popup.options = Object.assign(popup.options || {}, this.popupOptions(item));
        if (typeof popup.setContent === "function") {
          popup.setContent(item.popupHTML || "");
        }
      },

      buildMarkerIcon(item) {
        if (item.kind === "html") {
          return window.L.divIcon({
            className: item.className || "map-html-marker",
            html: item.html || "",
            iconSize: item.iconSize || [120, 54],
            iconAnchor: item.iconAnchor || [60, 27],
            popupAnchor: item.popupAnchor || [0, -24],
          });
        }
        if (item.kind === "tag") {
          const tagOffset = item.pixelOffset || [0, 0];
          return window.L.divIcon({
            className: "map-tag-marker",
            html: `<span class="map-tag ${escapeAttr(item.bucketClass)}">${escapeHtml(item.tagText)}</span>`,
            iconSize: [64, 40],
            iconAnchor: [32 - tagOffset[0], 20 - tagOffset[1]],
            popupAnchor: [tagOffset[0], tagOffset[1] - 14],
          });
        }
        return null;
      },

      buildMarker(item) {
        if (item.kind === "html") {
          return window.L.marker(item.latLng, {
            zIndexOffset: item.zIndexOffset || 0,
            icon: this.buildMarkerIcon(item),
          });
        }
        if (item.kind === "tag") {
          return window.L.marker(item.latLng, {
            zIndexOffset: item.zIndexOffset || 0,
            icon: this.buildMarkerIcon(item),
          });
        }
        return window.L.circleMarker(item.latLng, item.options);
      },

      fitBounds(bounds) {
        if (!this.map) {
          return;
        }
        this.markProgrammaticView();
        this.map.fitBounds(window.L.latLngBounds(bounds).pad(0.22), { animate: false });
        this.saveCurrentView();
      },

      handleDocumentClick(event) {
        if (!this.hasOpenPopup()) {
          return;
        }
        const target = event && event.target && typeof event.target.closest === "function"
          ? event.target
          : null;
        if (!target) {
          return;
        }
        if (target.closest(".leaflet-popup") || target.closest(".train-map")) {
          return;
        }
        this.closePopup();
      },

      hasOpenPopup() {
        return Boolean(this.map && this.map._popup && this.map.hasLayer(this.map._popup));
      },

      closePopup() {
        if (this.map && this.map.closePopup) {
          this.map.closePopup();
        }
        this.openPopupKey = "";
      },
    };
  }

  function buildTrainMapConfig(mapData) {
    const stops = Array.isArray(mapData.stops) ? mapData.stops : [];
    const locatedStops = stops.filter((stop) => typeof stop.latitude === "number" && typeof stop.longitude === "number");
    const liveItem = buildSelectedTrainLiveItem(mapData);
    const polyline = liveItem && Array.isArray(liveItem.external.polyline) && liveItem.external.polyline.length > 1
      ? liveItem.external.polyline.map(pointToLatLng).filter(Boolean)
      : locatedStops.map((stop) => [stop.latitude, stop.longitude]);
    const baseMarkers = locatedStops.map((stop, index) => {
      const stopSightingsList = stopSightings(stop, mapData);
      const stopLiveItems = liveItem && liveItemTouchesStation(liveItem, stop.stationName || stop.stationId) ? [liveItem] : [];
      return buildStationMarkerConfig({
        name: stop.stationName,
        markerKey: `train-stop:${mapData.train && mapData.train.id ? mapData.train.id : "unknown"}:${stopContextKey(stop, index)}`,
        latLng: [stop.latitude, stop.longitude],
        sightings: stopSightingsList,
        liveItems: stopLiveItems,
        popupHTML: buildTrainStopPopupHTML(stop, index, mapData, stopLiveItems),
      });
    });
    const trainMarkers = liveItem && liveItem.external && liveItem.external.position
      ? [buildLiveTrainMarkerConfig(liveItem)]
      : [];
    const bounds = polyline.concat(baseMarkers.map((item) => item.latLng), trainMarkers.map((item) => item.latLng));
    const trainId = mapData.train && mapData.train.id ? mapData.train.id : "unknown";
    const config = {
      viewKey: `${cfg.mode === "public-map" ? "train-public" : "train"}:${trainId}`,
      bounds: bounds,
      polyline: polyline,
      baseMarkers: baseMarkers,
      sightingMarkers: [],
      trainMarkers: trainMarkers,
    };
    return {
      modelKey: materialMapConfigKey(config),
      viewKey: config.viewKey,
      bounds: config.bounds,
      polyline: config.polyline,
      baseMarkers: config.baseMarkers,
      sightingMarkers: config.sightingMarkers,
      trainMarkers: config.trainMarkers,
    };
  }

  function buildNetworkMapConfig(mapData) {
    const stations = Array.isArray(mapData.stations) ? mapData.stations : [];
    const locatedStations = stations.filter((station) => typeof station.latitude === "number" && typeof station.longitude === "number");
    const liveItems = buildMatchedLiveItems(state.publicDashboardAll, mapData && mapData.recentSightings);
    const stationActivity = buildStationActivityMap(mapData, liveItems);
    const baseMarkers = locatedStations.map((station) => {
      const key = stationKeyValue(station.normalizedKey || station.name || station.id);
      const activity = stationActivity.get(key) || emptyStationActivity(station.name);
      return buildStationMarkerConfig({
        name: station.name,
        markerKey: `network-station:${key}`,
        latLng: [station.latitude, station.longitude],
        sightings: activity.sightings,
        liveItems: activity.liveItems,
        popupHTML: buildStationPopupHTML(station.name, activity),
      });
    });
    const trainMarkers = liveItems
      .filter((item) => item.external && item.external.position)
      .map((item) => buildLiveTrainMarkerConfig(item));
    const bounds = baseMarkers.map((item) => item.latLng).concat(trainMarkers.map((item) => item.latLng));
    const config = {
      viewKey: cfg.mode === "public-network-map" ? "network:public-network-map" : "network:mini-app",
      bounds: bounds,
      polyline: [],
      baseMarkers: baseMarkers,
      sightingMarkers: [],
      trainMarkers: trainMarkers,
    };
    return {
      modelKey: materialMapConfigKey(config),
      viewKey: config.viewKey,
      bounds: config.bounds,
      polyline: config.polyline,
      baseMarkers: config.baseMarkers,
      sightingMarkers: config.sightingMarkers,
      trainMarkers: config.trainMarkers,
    };
  }

  function externalFeedAPI() {
    return window.TrainExternalFeed || null;
  }

  function pointToLatLng(point) {
    if (!point || typeof point.lat !== "number" || typeof point.lng !== "number") {
      return null;
    }
    return [point.lat, point.lng];
  }

  function stationKeyValue(value) {
    if (externalFeedAPI() && typeof externalFeedAPI().normalizeStationKey === "function") {
      return externalFeedAPI().normalizeStationKey(value);
    }
    return String(value || "")
      .normalize("NFKD")
      .replace(/[\u0300-\u036f]/g, "")
      .replace(/\*/g, "")
      .replace(/[^a-zA-Z0-9]+/g, " ")
      .trim()
      .toLowerCase();
  }

  function extractTrainNumberFromValue(value) {
    const match = String(value || "").match(/(\d{3,5})$/);
    return match ? match[1] : "";
  }

  function stableExternalTrainIdentity(raw) {
    const api = externalFeedAPI();
    if (!api || typeof api.stableExternalTrainIdentity !== "function") {
      return "";
    }
    return api.stableExternalTrainIdentity(raw);
  }

  function liveTrainMarkerKeyFromIdentity(identity) {
    return identity ? `live-train:${identity}` : "";
  }

  function liveTrainMarkerKeyForExternal(raw) {
    return liveTrainMarkerKeyFromIdentity(stableExternalTrainIdentity(raw));
  }

  function comparableLocalTrainData(raw) {
    const train = raw && raw.trainCard && raw.trainCard.train
      ? raw.trainCard.train
      : raw && raw.train
        ? raw.train
        : raw;
    if (!train) {
      return null;
    }
    const trainNumber = String(train.trainNumber || (raw && raw.trainNumber) || extractTrainNumberFromValue(train.id)).trim();
    return {
      routeId: train.routeId || (raw && raw.routeId) || "",
      serviceDate: train.serviceDate || (raw && raw.serviceDate) || "",
      trainNumber: trainNumber,
      origin: train.fromStation || (raw && raw.fromStation) || (raw && raw.origin) || (raw && raw.originName) || "",
      destination: train.toStation || (raw && raw.toStation) || (raw && raw.destination) || (raw && raw.destinationName) || "",
      departureTime: train.departureAt || train.departure || (raw && raw.departureTime) || (raw && raw.departureAt) || "",
    };
  }

  function liveTrainMarkerKeyForLocal(raw) {
    const comparable = comparableLocalTrainData(raw);
    if (!comparable) {
      return "";
    }
    return liveTrainMarkerKeyForExternal(comparable);
  }

  function buildSelectedTrainLiveItem(mapData) {
    if (!mapData || !mapData.train) {
      return null;
    }
    const locals = [];
    if (state.mapTrainDetail) {
      locals.push(state.mapTrainDetail);
    }
    locals.push(mapData.train);
    const items = buildMatchedLiveItems(locals, mapData.stationSightings);
    const exact = items.find((item) => item.trainId === mapData.train.id);
    if (exact) {
      return exact;
    }
    const fallbackMarkerKeys = [
      state.publicMapSelectedMarkerKey || "",
      liveTrainMarkerKeyForLocal(state.mapTrainDetail),
      liveTrainMarkerKeyForLocal(mapData.train),
    ].filter(Boolean);
    for (const markerKey of fallbackMarkerKeys) {
      const candidate = items.find((item) => item.markerKey === markerKey);
      if (candidate) {
        return candidate;
      }
    }
    return null;
  }

  function buildMatchedLiveItems(localItems, fallbackSightings) {
    const feedApi = externalFeedAPI();
    if (!feedApi || typeof feedApi.matchLocalTrain !== "function") {
      return [];
    }
    const feedLiveTrains = Array.isArray(state.externalFeed.liveTrains) ? state.externalFeed.liveTrains : [];
    return feedLiveTrains.map((external) => {
      const mergedExternal = mergeExternalTrain(external, findExternalRoute(external));
      const matchInfo = feedApi.matchLocalTrain(mergedExternal, localItems || []);
      const localMatch = matchInfo && matchInfo.match ? matchInfo.match : null;
      const trainId = matchInfo && matchInfo.localTrainId ? matchInfo.localTrainId : localTrainId(localMatch);
      const markerKey = liveTrainMarkerKeyForExternal(mergedExternal);
      return {
        external: mergedExternal,
        localMatch: localMatch,
        matchInfo: matchInfo,
        markerKey: markerKey,
        trainId: trainId,
        status: localTrainStatus(localMatch),
        timeline: localTrainTimeline(localMatch),
        sightings: localTrainSightings(localMatch, trainId, fallbackSightings),
      };
    }).filter((item) => item.external && item.external.position);
  }

  function findExternalRoute(external) {
    const routes = Array.isArray(state.externalFeed.routes) ? state.externalFeed.routes : [];
    const routeId = external && external.routeId ? String(external.routeId) : "";
    if (routeId) {
      const exact = routes.find((item) => String(item.routeId || "") === routeId);
      if (exact) {
        return exact;
      }
    }
    const trainNumber = external && external.trainNumber ? String(external.trainNumber) : "";
    const serviceDate = external && external.serviceDate ? String(external.serviceDate) : "";
    return routes.find((item) => (
      String(item.trainNumber || "") === trainNumber &&
      String(item.serviceDate || "") === serviceDate
    )) || null;
  }

  function mergeExternalTrain(external, route) {
    const merged = Object.assign({}, route || {}, external || {});
    merged.stops = Array.isArray(external && external.stops) && external.stops.length
      ? external.stops
      : Array.isArray(route && route.stops)
        ? route.stops
        : [];
    merged.polyline = Array.isArray(external && external.polyline) && external.polyline.length
      ? external.polyline
      : Array.isArray(route && route.polyline)
        ? route.polyline
        : [];
    merged.origin = merged.origin || (route && route.origin) || "";
    merged.destination = merged.destination || (route && route.destination) || "";
    merged.originKey = merged.originKey || (route && route.originKey) || stationKeyValue(merged.origin);
    merged.destinationKey = merged.destinationKey || (route && route.destinationKey) || stationKeyValue(merged.destination);
    return merged;
  }

  function localTrainId(item) {
    if (!item) {
      return "";
    }
    if (item.train && item.train.id) {
      return item.train.id;
    }
    if (item.trainCard && item.trainCard.train && item.trainCard.train.id) {
      return item.trainCard.train.id;
    }
    return item.id || "";
  }

  function localTrainStatus(item) {
    if (!item) {
      return null;
    }
    if (item.trainCard && item.trainCard.status) {
      return item.trainCard.status;
    }
    return item.status || null;
  }

  function localTrainTimeline(item) {
    return item && Array.isArray(item.timeline) ? item.timeline : [];
  }

  function localTrainSightings(item, trainId, fallbackSightings) {
    if (item && Array.isArray(item.stationSightings) && item.stationSightings.length) {
      return item.stationSightings;
    }
    const sightings = Array.isArray(fallbackSightings) ? fallbackSightings : [];
    if (!trainId) {
      return [];
    }
    return sightings.filter((entry) => entry && entry.matchedTrainInstanceId === trainId);
  }

  function liveItemTouchesStation(item, stationName) {
    const stationKey = stationKeyValue(stationName);
    const external = item && item.external ? item.external : null;
    if (!external) {
      return false;
    }
    if (external.currentStop && stationKeyValue(external.currentStop.title) === stationKey) {
      return true;
    }
    if (external.nextStop && stationKeyValue(external.nextStop.title) === stationKey) {
      return true;
    }
    return false;
  }

  function buildStationActivityMap(mapData, liveItems) {
    const activity = new Map();
    const sightings = Array.isArray(mapData && mapData.recentSightings) ? mapData.recentSightings : [];
    sightings.forEach((item) => {
      const key = stationKeyValue(item.stationName || item.stationId);
      const bucket = ensureStationActivity(activity, key, item.stationName || item.stationId);
      bucket.sightings.push(item);
    });

    const liveIndex = new Map();
    liveItems.forEach((item) => {
      if (item.external && item.external.routeId) {
        liveIndex.set(`route:${item.external.routeId}`, item);
      }
      if (item.external && item.external.trainNumber) {
        liveIndex.set(`train:${item.external.trainNumber}:${item.external.serviceDate || ""}`, item);
      }
    });

    const activeStops = Array.isArray(state.externalFeed.activeStops) ? state.externalFeed.activeStops : [];
    activeStops.forEach((entry) => {
      if (!entry || !entry.hasTrain) {
        return;
      }
      const key = stationKeyValue(entry.title || entry.stationId);
      const bucket = ensureStationActivity(activity, key, entry.title || entry.stationId);
      const liveItem = liveIndex.get(`route:${entry.routeId}`) || liveIndex.get(`train:${entry.trainNumber}:${entry.serviceDate || ""}`) || null;
      if (liveItem) {
        pushStationLiveItem(bucket, liveItem);
      }
    });

    liveItems.forEach((item) => {
      [item.external && item.external.currentStop, item.external && item.external.nextStop].forEach((stop) => {
        if (!stop || !stop.title) {
          return;
        }
        const key = stationKeyValue(stop.title);
        const bucket = ensureStationActivity(activity, key, stop.title);
        pushStationLiveItem(bucket, item);
      });
    });

    return activity;
  }

  function ensureStationActivity(activity, key, name) {
    if (!activity.has(key)) {
      activity.set(key, emptyStationActivity(name));
    }
    const bucket = activity.get(key);
    if (!bucket.name && name) {
      bucket.name = name;
    }
    return bucket;
  }

  function emptyStationActivity(name) {
    return {
      name: name || "",
      sightings: [],
      liveItems: [],
      liveKeys: new Set(),
    };
  }

  function pushStationLiveItem(bucket, liveItem) {
    const key = liveItem.markerKey || liveItem.trainId || `${liveItem.external.routeId}:${liveItem.external.trainNumber}`;
    if (bucket.liveKeys.has(key)) {
      return;
    }
    bucket.liveKeys.add(key);
    bucket.liveItems.push(liveItem);
  }

  function buildStationMarkerConfig(options) {
    const sightings = Array.isArray(options.sightings) ? options.sightings : [];
    const liveItems = Array.isArray(options.liveItems) ? options.liveItems : [];
    return {
      kind: "html",
      className: "map-html-marker",
      markerKey: options.markerKey || "",
      latLng: options.latLng,
      html: buildStationMarkerHTML(options.name, sightings.length, liveItems),
      iconSize: [30, 30],
      iconAnchor: [15, 15],
      popupAnchor: [0, -18],
      popupHTML: options.popupHTML,
      popupOptions: options.popupOptions,
    };
  }

  function buildLiveTrainMarkerConfig(item) {
    return {
      kind: "html",
      className: "map-html-marker",
      markerKey: item.markerKey || "",
      latLng: pointToLatLng(item.external.position),
      animateMovement: true,
      movementObservedAt: item.external && item.external.updatedAt ? item.external.updatedAt : "",
      html: buildLiveTrainMarkerHTML(item),
      iconSize: [48, 28],
      iconAnchor: [24, 14],
      popupAnchor: [0, -18],
      zIndexOffset: 1300,
      popupHTML: buildTrainPopupHTML(item),
    };
  }

  function buildStationMarkerHTML(name, sightingCount, liveItems) {
    const crewCount = liveItems.filter((item) => hasCrewActivity(item.status)).length;
    const liveCount = liveItems.length;
    const stateClass = crewCount > 0
      ? "crew-active"
      : liveCount > 0
        ? "live-active"
        : sightingCount > 0
          ? "sighting-active"
          : "idle";
    const markerCount = sightingCount;
    const markerLabel = [
      name || "Station",
      liveCount ? `${liveCount} ${t("app_map_popup_live_now")}` : "",
      sightingCount ? `${sightingCount} ${t("app_map_popup_recent_sightings")}` : "",
    ].filter(Boolean).join(" • ");
    return `
      <div class="map-station-marker ${escapeAttr(stateClass)}" title="${escapeAttr(markerLabel)}" aria-label="${escapeAttr(markerLabel)}">
        ${markerCount ? `<span class="map-marker-count">${escapeHtml(compactMarkerCount(markerCount))}</span>` : ""}
      </div>
    `;
  }

  function buildLiveTrainMarkerHTML(item) {
    const number = item.external && item.external.trainNumber ? item.external.trainNumber : trainNumberLabel(item.trainId);
    const gpsClass = liveTrainGpsClass(item.external);
    const reporterCount = item.status && typeof item.status.uniqueReporters === "number" ? item.status.uniqueReporters : 0;
    const markerCount = reporterCount;
    return `
      <div class="map-train-marker ${escapeAttr(gpsClass)} ${hasCrewActivity(item.status) ? "crew-active" : "crew-idle"}">
        <span class="map-marker-label">${escapeHtml(number)}</span>
        ${markerCount ? `<span class="map-marker-count">${escapeHtml(compactMarkerCount(markerCount))}</span>` : ""}
      </div>
    `;
  }

  function buildTrainPopupHTML(item) {
    const routeName = [item.external.origin, item.external.destination].filter(Boolean).join(" → ");
    const nextStop = item.external.nextStop && item.external.nextStop.title
      ? `${item.external.nextStop.title}${item.external.nextStop.departureTime ? ` • ${clockLabel(item.external.nextStop.departureTime)}` : ""}`
      : "";
    const crewSummary = item.status
      ? `${statusSummary(item.status)}${typeof item.status.uniqueReporters === "number" && item.status.uniqueReporters > 0 ? ` • ${item.status.uniqueReporters} crew` : ""}`
      : "";
    const recentReports = Array.isArray(item.timeline) && item.timeline.length
      ? item.timeline.slice(0, 3).map((entry) => `${clockLabel(entry.at)} ${signalLabel(entry.signal)}`)
      : [];
    const recentSightings = Array.isArray(item.sightings) && item.sightings.length
      ? item.sightings.slice(0, 3).map((entry) => `${entry.stationName || entry.stationId} • ${relativeAgo(entry.createdAt)}`)
      : [];
    return buildPopupCard({
      title: item.external.trainNumber || trainNumberLabel(item.trainId),
      subtitle: routeName,
      sections: [
        popupInfoRow(t("app_map_popup_next_stop"), nextStop),
        popupInfoRow(t("app_map_popup_last_update"), item.external.updatedAt ? relativeAgo(item.external.updatedAt) : t("app_map_popup_schedule")),
        popupInfoRow(t("app_map_popup_crew"), crewSummary),
        popupListSection(t("app_map_popup_recent_reports"), recentReports),
        popupListSection(t("app_map_popup_recent_sightings"), recentSightings),
      ],
    });
  }

  function buildStationPopupHTML(name, bucket) {
    const liveNow = bucket.liveItems.length
      ? bucket.liveItems.slice(0, 3).map((item) => {
        const crewSummary = item.status ? ` • ${statusSummary(item.status)}` : "";
        const nextStop = item.external && item.external.nextStop && item.external.nextStop.title ? ` • ${item.external.nextStop.title}` : "";
        return `${item.external.trainNumber || trainNumberLabel(item.trainId)}${crewSummary}${nextStop}`;
      })
      : [];
    const recentSightings = bucket.sightings.length
      ? bucket.sightings.slice(0, 3).map((entry) => `${relativeAgo(entry.createdAt)}${entry.destinationStationName ? ` • ${entry.destinationStationName}` : ""}`)
      : [t("app_station_sighting_empty")];
    return buildPopupCard({
      title: name || bucket.name || "Station",
      sections: [
        popupListSection(t("app_map_popup_live_now"), liveNow),
        popupListSection(t("app_map_popup_recent_sightings"), recentSightings),
      ],
    });
  }

  function buildTrainStopPopupHTML(stop, index, mapData, liveItems) {
    const liveTrainSummary = Array.isArray(liveItems) && liveItems.length
      ? `${liveItems[0].external.trainNumber || trainNumberLabel(liveItems[0].trainId)}`
      : "";
    const sightings = stopSightings(stop, mapData);
    const recentSightings = sightings.length
      ? sightings.slice(0, 3).map((entry) => `${relativeAgo(entry.createdAt)}${entry.destinationStationName ? ` • ${entry.destinationStationName}` : ""}`)
      : [t("app_station_sighting_empty")];
    return buildPopupCard({
      title: stop.stationName || stop.stationId || "",
      sections: [
        popupInfoRow(t("app_map_popup_schedule"), stopTimeLabel(stop, index)),
        popupInfoRow(t("app_map_popup_live_train"), liveTrainSummary),
        popupListSection(t("app_map_popup_recent_sightings"), recentSightings),
      ],
    });
  }

  function liveTrainGpsLabel(external) {
    if (!external || !external.updatedAt) {
      return "sched";
    }
    if (!external.isGpsActive) {
      return "sched";
    }
    const ageMinutes = sightingAgeMinutes(external.updatedAt);
    if (ageMinutes <= 1) {
      return "gps";
    }
    return `${ageMinutes}m`;
  }

  function liveTrainGpsClass(external) {
    if (!external || !external.isGpsActive) {
      return "gps-scheduled";
    }
    const ageMinutes = sightingAgeMinutes(external.updatedAt);
    if (ageMinutes <= 2) {
      return "gps-fresh";
    }
    if (ageMinutes <= 6) {
      return "gps-warm";
    }
    return "gps-stale";
  }

  function hasCrewActivity(status) {
    return Boolean(status && status.state && status.state !== "NO_REPORTS");
  }

  function clockLabel(value) {
    const date = new Date(value);
    if (!Number.isNaN(date.getTime())) {
      return formatClock(date);
    }
    const text = String(value || "");
    const match = text.match(/(\d{2}:\d{2})/);
    return match ? match[1] : text;
  }

  function externalFeedStatusText() {
    if (!cfg.externalTrainMapEnabled) {
      return t("app_live_overlay_unavailable");
    }
    if (!state.externalFeed || state.externalFeed.connectionState === "disabled") {
      return t("app_live_overlay_unavailable");
    }
    if (state.externalFeed.connectionState === "live") {
      return t("app_live_overlay_ready");
    }
    if (state.externalFeed.connectionState === "connecting") {
      return t("app_live_overlay_connecting");
    }
    return t("app_live_overlay_offline");
  }

  function buildCoordinateLookup(items, idKey) {
    const byId = new Map();
    const byName = new Map();
    items.forEach((item) => {
      const latitude = typeof item.latitude === "number" ? item.latitude : null;
      const longitude = typeof item.longitude === "number" ? item.longitude : null;
      if (latitude === null || longitude === null) {
        return;
      }
      const id = item[idKey];
      if (id) {
        byId.set(String(id), [latitude, longitude]);
      }
      if (item.stationName) {
        byName.set(String(item.stationName), [latitude, longitude]);
      }
      if (item.name) {
        byName.set(String(item.name), [latitude, longitude]);
      }
    });
    return { byId, byName };
  }

  function buildSightingMarkers(items, lookup) {
    const sightings = Array.isArray(items) ? items : [];
    const stationCounts = Object.create(null);
    return sightings.map((item) => {
      const baseLatLng = lookup.byId.get(String(item.stationId || "")) || lookup.byName.get(String(item.stationName || ""));
      if (!baseLatLng) {
        return null;
      }
      const stationKey = String(item.stationId || item.stationName || "unknown");
      const offsetIndex = stationCounts[stationKey] || 0;
      stationCounts[stationKey] = offsetIndex + 1;
      return {
        latLng: baseLatLng,
        markerKey: `sighting:${stationKey}:${item.createdAt || offsetIndex}:${item.destinationStationName || ""}`,
        pixelOffset: sightingPixelOffset(offsetIndex),
        zIndexOffset: 1000 - offsetIndex,
        tagText: sightingTagText(item.createdAt),
        bucketClass: `bucket-${sightingRecencyBucket(item.createdAt)}`,
        popupHTML: sightingPopupHTML(item),
      };
    }).filter(Boolean);
  }

  function sightingPixelOffset(index) {
    const offsets = [
      [0, 0],
      [34, 0],
      [0, 28],
      [-34, 0],
      [0, -28],
      [26, 22],
      [-26, 22],
      [26, -22],
      [-26, -22],
    ];
    return offsets[index % offsets.length];
  }

  function sightingRecencyBucket(raw) {
    const minutes = sightingAgeMinutes(raw);
    if (minutes <= 5) return "fresh";
    if (minutes <= 15) return "warm";
    return "stale";
  }

  function sightingAgeMinutes(raw) {
    const date = new Date(raw);
    if (Number.isNaN(date.getTime())) {
      return 999;
    }
    return Math.max(0, Math.round((Date.now() - date.getTime()) / 60000));
  }

  function sightingTagText(raw) {
    const minutes = sightingAgeMinutes(raw);
    if (minutes <= 0) return t("app_map_tag_now");
    return `${minutes}m`;
  }

  function sightingPopupHTML(item) {
    const details = [
      `<strong>${escapeHtml(item.stationName || item.stationId || "")}</strong>`,
    ];
    if (item.destinationStationName) {
      details.push(`${escapeHtml(t("app_map_popup_destination"))}: ${escapeHtml(item.destinationStationName)}`);
    }
    details.push(`${escapeHtml(t("app_map_popup_status"))}: ${escapeHtml(item.matchedTrainInstanceId ? t("app_station_sighting_matched") : t("app_station_sighting_unmatched"))}`);
    details.push(`${escapeHtml(t("app_map_popup_age"))}: ${escapeHtml(relativeAgo(item.createdAt))}`);
    details.push(`${escapeHtml(t("app_map_popup_seen_at"))}: ${escapeHtml(formatDateTime(item.createdAt))}`);
    return details.join("<br>");
  }

  function renderToast() {
    if (!state.toast || !state.toast.message) return "";
    return `
      <div class="toast-stack" aria-live="polite">
        <div class="toast ${escapeAttr(state.toast.kind || "success")}">${escapeHtml(state.toast.message)}</div>
      </div>
    `;
  }

  function renderLoading() {
    setAppHTML(`<div class="shell"><section class="hero"><h1>${escapeHtml(t("app_loading"))}</h1></section></div>`);
  }

  function renderAuthRequired() {
    setAppHTML(`
      <div class="shell">
        <section class="hero">
          <h1>${escapeHtml(t("app_auth_required"))}</h1>
          <p>${escapeHtml(t("app_auth_required_body"))}</p>
          <div class="hero-actions">
            <a class="button primary" href="${escapeAttr(publicStationRoot())}">${escapeHtml(t("app_open_station_search"))}</a>
            <a class="button ghost" href="${escapeAttr(publicDashboardRoot())}">${escapeHtml(t("app_open_departures"))}</a>
          </div>
        </section>
      </div>`);
  }

  function renderFatal(err) {
    setAppHTML(`<div class="shell"><section class="hero"><h1>${escapeHtml(t("app_status_error"))}</h1><p>${escapeHtml(err && err.message ? err.message : String(err))}</p></section></div>`);
  }

  function bindGlobalDocumentEvents() {
    if (document.body && document.body.dataset.trainMapGlobalsBound === "1") {
      return;
    }
    if (document.body) {
      document.body.dataset.trainMapGlobalsBound = "1";
    }
    document.addEventListener("click", (event) => {
      const target = event && event.target && typeof event.target.closest === "function"
        ? event.target
        : null;
      if (state.tab === "map" && state.expandedStopContextKey && target
        && !target.closest(".stop-row")
        && !target.closest(".leaflet-popup")) {
        state.expandedStopContextKey = "";
        collapseExpandedStopContextUI();
      }
      mapController.handleDocumentClick(event);
    });
  }

  function collapseExpandedStopContextUI() {
    const expandedToggle = document.querySelector("[data-action='toggle-stop-context'][aria-expanded='true']");
    if (!expandedToggle) {
      return;
    }
    expandedToggle.setAttribute("aria-expanded", "false");
    const row = expandedToggle.closest(".stop-row");
    if (!row) {
      return;
    }
    row.classList.remove("expanded");
    const context = row.querySelector(".stop-context");
    if (context && context.parentNode) {
      context.parentNode.removeChild(context);
    }
  }

  function renderPublicDashboard(options) {
    const inputFocus = snapshotFocusedInput("public-filter");
    const filter = state.publicFilter.trim().toLowerCase();
    const items = state.publicDashboard.filter((item) => {
      if (!filter) return true;
      const route = `${item.train.fromStation} ${item.train.toStation}`.toLowerCase();
      return route.includes(filter) || String(item.train.departureAt).toLowerCase().includes(filter);
    });
    setAppHTML(`
      <div class="shell">
        ${renderHero(t("app_public_dashboard_eyebrow"), t("app_public_dashboard_title"), t("app_public_dashboard_note"))}
        <section class="status-bar">
          <span>${escapeHtml(state.statusText || t("app_status_public"))}</span>
          <div class="button-row">
            <a class="button ghost small" href="${escapeAttr(publicStationRoot())}">${escapeHtml(t("app_open_station_search"))}</a>
            <a class="button ghost small" href="${escapeAttr(publicNetworkMapRoot())}">${escapeHtml(t("app_section_map"))}</a>
            <span class="status-pill">${escapeHtml(formatClock(new Date()))}</span>
          </div>
        </section>
        <section class="panel">
          <div class="form-grid">
            <div class="field">
              <label>${escapeHtml(t("app_dashboard_filter"))}</label>
              <input id="public-filter" type="text" value="${escapeAttr(state.publicFilterDraft)}" placeholder="${escapeAttr(t("app_dashboard_filter"))}">
            </div>
            <div class="button-row">
              <button class="secondary" id="public-search">${escapeHtml(t("app_search"))}</button>
              <button class="primary" id="public-refresh">${escapeHtml(t("app_refresh"))}</button>
            </div>
          </div>
        </section>
        <section class="panel">
          <div class="card-list">${items.length ? items.map(renderPublicCard).join("") : `<div class="empty">${escapeHtml(t("app_public_dashboard_empty"))}</div>`}</div>
        </section>
      </div>
      ${renderToast()}`);
    bindPublicDashboardEvents();
    restoreFocusedInput(inputFocus);
  }

  function renderPublicTrain() {
    const item = state.publicTrain;
    setAppHTML(`
      <div class="shell">
        ${renderHero(t("app_public_train_eyebrow"), t("app_public_train_title"), t("app_public_train_note"))}
        <section class="status-bar"><span>${escapeHtml(state.statusText || t("app_status_public"))}</span><div class="button-row"><a class="button ghost small" href="${escapeAttr(publicDashboardRoot())}">${escapeHtml(t("app_open_departures"))}</a><a class="button ghost small" href="${escapeAttr(publicStationRoot())}">${escapeHtml(t("app_open_station_search"))}</a></div></section>
        <section class="panel">
          ${item ? renderPublicDetail(item) : `<div class="empty">${escapeHtml(t("app_public_dashboard_empty"))}</div>`}
        </section>
      </div>
      ${renderToast()}`);
  }

  function renderToastRoot(rootId) {
    return `<div id="${escapeAttr(rootId)}">${renderToast()}</div>`;
  }

  function snapshotFocusedInput(inputId) {
    const activeElement = document.activeElement;
    const wasFocused = Boolean(activeElement && activeElement.id === inputId);
    return {
      inputId,
      wasFocused,
      selectionStart: wasFocused && typeof activeElement.selectionStart === "number" ? activeElement.selectionStart : null,
      selectionEnd: wasFocused && typeof activeElement.selectionEnd === "number" ? activeElement.selectionEnd : null,
    };
  }

  function restoreFocusedInput(snapshot) {
    if (!snapshot || !snapshot.wasFocused) {
      return;
    }
    const input = document.getElementById(snapshot.inputId);
    if (!input) {
      return;
    }
    input.focus();
    if (snapshot.selectionStart !== null && snapshot.selectionEnd !== null && typeof input.setSelectionRange === "function") {
      input.setSelectionRange(snapshot.selectionStart, snapshot.selectionEnd);
    }
  }

  function trainMapShellState(containerId, mapData, includeSelectionPrompt) {
    if (!mapData || !mapData.train) {
      return {
        hasTrain: false,
        hasMap: false,
        html: `<div class="empty">${escapeHtml(includeSelectionPrompt ? t("app_map_prompt") : t("app_map_empty"))}</div>`,
        missingCoordsText: "",
        stopListHTML: `<div class="empty">${escapeHtml(t("app_map_empty"))}</div>`,
      };
    }
    const stops = Array.isArray(mapData.stops) ? mapData.stops : [];
    const locatedStops = stops.filter((stop) => typeof stop.latitude === "number" && typeof stop.longitude === "number");
    return {
      hasTrain: true,
      hasMap: locatedStops.length > 0,
      html: locatedStops.length
        ? `<div id="${escapeAttr(containerId)}" class="train-map-shell" aria-label="${escapeAttr(t("app_section_map"))}"></div>`
        : `<div class="empty">${escapeHtml(t("app_map_empty"))}</div>`,
      missingCoordsText: stops.length && locatedStops.length !== stops.length ? t("app_map_missing_coords") : "",
      stopListHTML: stops.length
        ? stops.map((stop, index) => renderStopRow(stop, index, mapData)).join("")
        : `<div class="empty">${escapeHtml(t("app_map_empty"))}</div>`,
    };
  }

  function networkMapShellState(containerId, mapData) {
    const stations = mapData && Array.isArray(mapData.stations) ? mapData.stations : [];
    return {
      hasMap: stations.length > 0,
      html: stations.length
        ? `<div id="${escapeAttr(containerId)}" class="train-map-shell" aria-label="${escapeAttr(t("app_network_map_title"))}"></div>`
        : `<div class="empty">${escapeHtml(t("app_network_map_empty"))}</div>`,
    };
  }

  function syncPublicMapShellSlot(slotEl, containerId, shellState) {
    if (!slotEl) {
      return false;
    }
    const currentMapEl = slotEl.querySelector(`#${containerId}`);
    if (shellState.hasMap) {
      if (!currentMapEl) {
        slotEl.innerHTML = shellState.html;
      }
      return true;
    }
    if (currentMapEl && mapController.containerId === containerId) {
      mapController.closePopup();
      mapController.detach();
      clearPublicMapPopupSelection();
    }
    if (!currentMapEl || slotEl.innerHTML !== shellState.html) {
      slotEl.innerHTML = shellState.html;
    }
    return false;
  }

  function publicTrainMapSummaryItem() {
    if (!state.mapData || !state.mapData.train) {
      return null;
    }
    const fallbackStatus = state.selectedTrain && state.selectedTrain.trainCard && state.selectedTrain.trainCard.train
      && state.selectedTrain.trainCard.train.id === state.mapData.train.id
      ? state.selectedTrain.trainCard.status
      : null;
    const fallbackRiders = state.selectedTrain && state.selectedTrain.trainCard && state.selectedTrain.trainCard.train
      && state.selectedTrain.trainCard.train.id === state.mapData.train.id
      ? state.selectedTrain.trainCard.riders
      : 0;
    return state.mapData.trainCard
      ? { trainCard: state.mapData.trainCard }
      : { trainCard: { train: state.mapData.train, status: fallbackStatus, riders: fallbackRiders } };
  }

  function renderPublicMapStatusBar() {
    return `
      <span id="public-map-status-text">${escapeHtml(state.statusText || t("app_status_public"))}</span>
      <div class="button-row">
        <a class="button ghost small" href="${escapeAttr(`${cfg.basePath}/t/${cfg.trainId}`)}">${escapeHtml(t("btn_view_status"))}</a>
        <a class="button ghost small" href="${escapeAttr(publicDashboardRoot())}">${escapeHtml(t("app_open_departures"))}</a>
      </div>
    `;
  }

  function renderPublicMapSightingsCard() {
    return `
      <h3>${escapeHtml(t("app_recent_platform_sightings"))}</h3>
      ${renderStationSightings(state.mapData && state.mapData.stationSightings)}
    `;
  }

  function renderPublicMapStopListCard(shellState) {
    return `
      <h3>${escapeHtml(t("app_stop_list"))}</h3>
      <div class="stop-list">${shellState.stopListHTML}</div>
    `;
  }

  function renderPublicMapMainPanel() {
    const shellState = trainMapShellState("public-train-map", state.mapData, false);
    if (!shellState.hasTrain) {
      return shellState.html;
    }
    return `
      <div class="stack">
        <div id="public-map-shell-slot">${shellState.html}</div>
        <p class="panel-subtitle map-live-status" id="public-map-live-status">${escapeHtml(externalFeedStatusText())}</p>
        <p class="panel-subtitle" id="public-map-missing-coords" ${shellState.missingCoordsText ? "" : "hidden"}>${escapeHtml(shellState.missingCoordsText || "")}</p>
      </div>
    `;
  }

  function renderPublicMapDetailsPanel() {
    const shellState = trainMapShellState("public-train-map", state.mapData, false);
    if (!shellState.hasTrain) {
      return shellState.html;
    }
    return `
      <div class="stack">
        <div id="public-map-summary">${renderRideSummary(publicTrainMapSummaryItem())}</div>
        <section class="detail-card" id="public-map-sightings-card">${renderPublicMapSightingsCard()}</section>
        <section class="detail-card" id="public-map-stop-list-card">${renderPublicMapStopListCard(shellState)}</section>
      </div>
    `;
  }

  function patchPublicMapMainPanel(options) {
    const mainPanel = document.getElementById("public-map-main-panel");
    if (!mainPanel) {
      return false;
    }
    const renderOptions = options || {};
    const shellState = trainMapShellState("public-train-map", state.mapData, false);
    if (!shellState.hasTrain) {
      if (mapController.containerId === "public-train-map") {
        mapController.closePopup();
        mapController.detach();
        clearPublicMapPopupSelection();
      }
      if (!renderOptions.mapOnly) {
        mainPanel.innerHTML = renderPublicMapMainPanel();
      }
      return true;
    }
    const slotEl = document.getElementById("public-map-shell-slot");
    const liveStatusEl = document.getElementById("public-map-live-status");
    const missingCoordsEl = document.getElementById("public-map-missing-coords");
    if (!slotEl || !liveStatusEl || !missingCoordsEl) {
      mainPanel.innerHTML = renderPublicMapMainPanel();
    } else {
      syncPublicMapShellSlot(slotEl, "public-train-map", shellState);
      liveStatusEl.textContent = externalFeedStatusText();
      missingCoordsEl.hidden = !shellState.missingCoordsText;
      missingCoordsEl.textContent = shellState.missingCoordsText || "";
    }
    if (!renderOptions.mapOnly) {
      patchPublicMapDetailsPanel();
    }
    syncActivePublicMap();
    return true;
  }

  function patchPublicMapDetailsPanel() {
    const detailsPanel = document.getElementById("public-map-details-panel");
    if (!detailsPanel) {
      return false;
    }
    const shellState = trainMapShellState("public-train-map", state.mapData, false);
    if (!shellState.hasTrain) {
      detailsPanel.innerHTML = renderPublicMapDetailsPanel();
      return true;
    }
    const summaryEl = document.getElementById("public-map-summary");
    const sightingsCardEl = document.getElementById("public-map-sightings-card");
    const stopListCardEl = document.getElementById("public-map-stop-list-card");
    if (!summaryEl || !sightingsCardEl || !stopListCardEl) {
      detailsPanel.innerHTML = renderPublicMapDetailsPanel();
      return true;
    }
    summaryEl.innerHTML = renderRideSummary(publicTrainMapSummaryItem());
    sightingsCardEl.innerHTML = renderPublicMapSightingsCard();
    stopListCardEl.innerHTML = renderPublicMapStopListCard(shellState);
    return true;
  }

  function renderPublicMap() {
    const statusBar = document.getElementById("public-map-status-bar");
    const mainPanel = document.getElementById("public-map-main-panel");
    const detailsPanel = document.getElementById("public-map-details-panel");
    const toastRoot = document.getElementById("public-map-toast-root");
    if (statusBar && mainPanel && detailsPanel && toastRoot) {
      statusBar.innerHTML = renderPublicMapStatusBar();
      patchPublicMapMainPanel();
      toastRoot.innerHTML = renderToast();
      return;
    }
    setAppHTML(`
      <div class="shell">
        ${renderHero(t("app_public_map_eyebrow"), t("app_public_map_title"), t("app_public_map_note"))}
        <section class="status-bar" id="public-map-status-bar">${renderPublicMapStatusBar()}</section>
        <section class="panel" id="public-map-main-panel">${renderPublicMapMainPanel()}</section>
        <section class="panel" id="public-map-details-panel">${renderPublicMapDetailsPanel()}</section>
      </div>
      ${renderToastRoot("public-map-toast-root")}`);
    patchPublicMapMainPanel();
  }

  function renderPublicStationStatusBar() {
    return `
      <span id="public-station-status-text">${escapeHtml(state.statusText || t("app_status_public"))}</span>
      <div class="button-row">
        <a class="button ghost small" href="${escapeAttr(publicDashboardRoot())}">${escapeHtml(t("app_open_departures"))}</a>
        <a class="button ghost small" href="${escapeAttr(publicNetworkMapRoot())}">${escapeHtml(t("app_section_map"))}</a>
        <button class="ghost small" id="public-station-refresh">${escapeHtml(t("app_refresh"))}</button>
      </div>
    `;
  }

  function renderPublicStationSearchPanel() {
    const matches = state.publicStationMatches.length
      ? state.publicStationMatches.map(renderPublicStationMatch).join("")
      : `<div class="empty">${escapeHtml(state.publicStationQuery ? t("app_public_station_no_matches") : t("app_public_station_prompt"))}</div>`;
    return `
      <div class="form-grid">
        <div class="field">
          <label>${escapeHtml(t("app_public_station_search_label"))}</label>
          <input id="public-station-query" type="text" value="${escapeAttr(state.publicStationQuery)}" placeholder="${escapeAttr(t("app_public_station_search_placeholder"))}">
        </div>
        <div class="button-row">
          <button class="primary" id="public-station-search">${escapeHtml(t("app_search"))}</button>
        </div>
      </div>
      <div class="divider"></div>
      <h2>${escapeHtml(t("app_public_station_matches"))}</h2>
      <div class="card-list">${matches}</div>
    `;
  }

  function renderPublicStationDeparturesPanel() {
    const departures = state.publicStationDepartures;
    const lastDeparture = departures && departures.lastDeparture ? renderPublicStationDepartureCard(departures.lastDeparture) : `<div class="empty">${escapeHtml(t("app_public_station_last_empty"))}</div>`;
    const upcoming = departures && Array.isArray(departures.upcoming) && departures.upcoming.length
      ? departures.upcoming.map(renderPublicStationDepartureCard).join("")
      : `<div class="empty">${escapeHtml(t("app_public_station_upcoming_empty"))}</div>`;
    return `
      <div class="stack">
        <div class="badge">${escapeHtml(state.publicStationSelected ? `${t("app_public_station_selected")}: ${state.publicStationSelected.name}` : t("app_public_station_prompt"))}</div>
        <section class="detail-card">
          <h3>${escapeHtml(t("app_recent_platform_sightings"))}</h3>
          ${renderStationSightings(state.publicStationDepartures && state.publicStationDepartures.recentSightings)}
        </section>
        <div class="split">
          <section class="detail-card">
            <h3>${escapeHtml(t("app_public_station_last"))}</h3>
            <div class="card-list">${lastDeparture}</div>
          </section>
          <section class="detail-card">
            <h3>${escapeHtml(t("app_public_station_upcoming"))}</h3>
            <div class="card-list">${upcoming}</div>
          </section>
        </div>
      </div>
    `;
  }

  function renderPublicNetworkMapStatusBar() {
    return `
      <span id="public-network-map-status-text">${escapeHtml(state.statusText || t("app_status_public"))}</span>
      <div class="button-row">
        <a class="button ghost small" href="${escapeAttr(publicStationRoot())}">${escapeHtml(t("app_open_station_search"))}</a>
        <a class="button ghost small" href="${escapeAttr(publicDashboardRoot())}">${escapeHtml(t("app_open_departures"))}</a>
      </div>
    `;
  }

  function renderPublicNetworkMapSightingsCard() {
    return `
      <h3>${escapeHtml(t("app_recent_platform_sightings"))}</h3>
      ${renderStationSightings(state.networkMapData && state.networkMapData.recentSightings)}
    `;
  }

  function renderPublicNetworkMapPanel() {
    const shellState = networkMapShellState("public-network-map", state.networkMapData);
    return `
      <div class="stack">
        <div id="public-network-map-shell-slot">${shellState.html}</div>
        <p class="panel-subtitle map-live-status" id="public-network-map-live-status">${escapeHtml(externalFeedStatusText())}</p>
        <section class="detail-card" id="public-network-map-sightings-card">${renderPublicNetworkMapSightingsCard()}</section>
      </div>
    `;
  }

  function patchPublicNetworkMapPanel(options) {
    const mapPanel = document.getElementById("public-network-map-panel");
    if (!mapPanel) {
      return false;
    }
    const renderOptions = options || {};
    const slotEl = document.getElementById("public-network-map-shell-slot");
    const liveStatusEl = document.getElementById("public-network-map-live-status");
    const sightingsCardEl = document.getElementById("public-network-map-sightings-card");
    const shellState = networkMapShellState("public-network-map", state.networkMapData);
    if (!slotEl || !liveStatusEl || !sightingsCardEl) {
      mapPanel.innerHTML = renderPublicNetworkMapPanel();
    } else {
      syncPublicMapShellSlot(slotEl, "public-network-map", shellState);
      liveStatusEl.textContent = externalFeedStatusText();
      if (!renderOptions.mapOnly) {
        sightingsCardEl.innerHTML = renderPublicNetworkMapSightingsCard();
      }
    }
    syncActivePublicMap();
    return true;
  }

  function renderPublicNetworkMap() {
    const statusBar = document.getElementById("public-network-map-status-bar");
    const mapPanel = document.getElementById("public-network-map-panel");
    const toastRoot = document.getElementById("public-network-map-toast-root");
    if (statusBar && mapPanel && toastRoot) {
      statusBar.innerHTML = renderPublicNetworkMapStatusBar();
      patchPublicNetworkMapPanel();
      toastRoot.innerHTML = renderToast();
      return;
    }
    setAppHTML(`
      <div class="shell">
        ${renderHero(t("app_section_map"), t("app_public_network_map_title"), t("app_public_network_map_note"))}
        <section class="status-bar" id="public-network-map-status-bar">${renderPublicNetworkMapStatusBar()}</section>
        <section class="panel" id="public-network-map-panel">${renderPublicNetworkMapPanel()}</section>
      </div>
      ${renderToastRoot("public-network-map-toast-root")}`);
    patchPublicNetworkMapPanel();
  }

  function renderPublicStationSearch(options) {
    const inputFocus = snapshotFocusedInput("public-station-query");
    const statusBar = document.getElementById("public-stations-status-bar");
    const searchPanel = document.getElementById("public-stations-search-panel");
    const departuresPanel = document.getElementById("public-stations-departures-panel");
    const toastRoot = document.getElementById("public-stations-toast-root");
    if (statusBar && searchPanel && departuresPanel && toastRoot) {
      statusBar.innerHTML = renderPublicStationStatusBar();
      searchPanel.innerHTML = renderPublicStationSearchPanel();
      departuresPanel.innerHTML = renderPublicStationDeparturesPanel();
      toastRoot.innerHTML = renderToast();
      bindPublicStationEvents(statusBar);
      bindPublicStationEvents(searchPanel);
      bindPublicStationEvents(departuresPanel);
    } else {
      setAppHTML(`
        <div class="shell">
          ${renderHero(t("app_public_station_eyebrow"), t("app_public_station_title"), t("app_public_station_note"))}
          <section class="status-bar" id="public-stations-status-bar">${renderPublicStationStatusBar()}</section>
          <section class="panel" id="public-stations-search-panel">${renderPublicStationSearchPanel()}</section>
          <section class="panel" id="public-stations-departures-panel">${renderPublicStationDeparturesPanel()}</section>
        </div>
        ${renderToastRoot("public-stations-toast-root")}`);
      bindPublicStationEvents(appEl);
    }
    restoreFocusedInput(inputFocus);
  }

  function renderMiniStatusBar() {
    return `
      <span id="mini-app-status-text">${escapeHtml(state.statusText || t("app_status_telegram"))}</span>
      <div class="button-row">
        <a class="button ghost small" href="${escapeAttr(publicRoot())}" target="_blank" rel="noreferrer">${escapeHtml(t("app_open_public"))}</a>
        <button class="ghost small" id="global-refresh">${escapeHtml(t("app_refresh"))}</button>
      </div>
    `;
  }

  function renderMiniNavTabs() {
    return `
      <div class="nav-tabs">
        ${renderTabButton("dashboard", t("app_section_dashboard"))}
        ${renderTabButton("my-ride", t("app_section_my_ride"))}
        ${renderTabButton("report", t("app_section_report"))}
        ${renderTabButton("sightings", t("app_section_sightings"))}
        ${renderTabButton("map", t("app_section_map"))}
        ${renderTabButton("settings", t("app_section_settings"))}
      </div>
    `;
  }

  function syncSelectedDetailSnapshot() {
    selectedDetailSnapshot = state.selectedTrain
      ? buildStatusDetailSnapshot(state.selectedTrain, true, false)
      : null;
  }

  function syncActiveMiniMap() {
    if (state.tab !== "map") {
      return;
    }
    if (state.mapTrainId) {
      syncMapFromDOM("mini-train-map", state.mapData);
    } else {
      syncMapFromDOM("mini-network-map", state.networkMapData);
    }
    applyMiniMapFollow();
  }

  function resolveMiniMapFollowTarget() {
    return state.mapFollowTrainId || state.mapPinnedTrainId || detailTargetTrainId() || "";
  }

  function resolveMiniMapFollowMarkerKey() {
    const liveItem = buildSelectedTrainLiveItem(state.mapData);
    return liveItem && liveItem.markerKey ? liveItem.markerKey : "";
  }

  function applyMiniMapFollow() {
    if (cfg.mode !== "mini-app" || state.tab !== "map" || !state.mapTrainId || state.mapFollowPaused) {
      return;
    }
    const followTrainId = resolveMiniMapFollowTarget();
    if (!followTrainId || followTrainId !== state.mapTrainId) {
      emitTrainStateTransition("mini-map-follow-skip", {
        followTrainId,
        mapTrainId: state.mapTrainId,
      });
      return;
    }
    const markerKey = resolveMiniMapFollowMarkerKey();
    if (!markerKey) {
      emitTrainStateTransition("mini-map-follow-skip", {
        followTrainId,
        mapTrainId: state.mapTrainId,
        reason: "live-marker-missing",
      });
      return;
    }
    if (mapController.panToMarker(markerKey)) {
      emitTrainStateTransition("mini-map-follow", {
        followTrainId,
        mapTrainId: state.mapTrainId,
        markerKey,
      });
    }
  }

  function renderMiniApp(options) {
    const renderOptions = options || {};
    const settings = state.me && state.me.settings ? state.me.settings : {};
    if (renderOptions.preserveDetail && renderMiniAppPreservingDetail(renderOptions, settings)) {
      return;
    }
    setAppHTML(`
      <div class="shell">
        ${renderHero("", t("app_title"), "")}
        <section class="status-bar" id="mini-app-status-bar">${renderMiniStatusBar()}</section>
        <section class="panel" id="mini-app-nav-panel">${renderMiniNavTabs()}</section>
        <div class="layout">
          <section class="panel" id="mini-app-main-panel">${renderMiniMain(settings)}</section>
          <section class="panel" id="mini-app-sidebar-panel">${renderMiniSidebar()}</section>
        </div>
      </div>
      ${renderToast()}`);
    syncSelectedDetailSnapshot();
    syncActiveMiniMap();
    bindMiniAppEvents(appEl);
  }

  function renderMiniAppPreservingDetail(options, settings) {
    const statusBar = document.getElementById("mini-app-status-bar");
    const navPanel = document.getElementById("mini-app-nav-panel");
    const mainPanel = document.getElementById("mini-app-main-panel");
    const sidebarPanel = document.getElementById("mini-app-sidebar-panel");
    if (!statusBar || !navPanel || !mainPanel || !sidebarPanel) {
      return false;
    }

    statusBar.innerHTML = renderMiniStatusBar();
    navPanel.innerHTML = renderMiniNavTabs();
    const mainPanelPatched = state.tab === "map" && patchMiniMapPanel(mainPanel);
    if (!mainPanelPatched) {
      mainPanel.innerHTML = renderMiniMain(settings);
    }

    const currentPinnedTrainId = detailTargetTrainId();
    if (
      state.selectedTrain
      && currentPinnedTrainId
      && options.previousSelectedTrainId === currentPinnedTrainId
      && selectedTrainId() === currentPinnedTrainId
    ) {
      patchMiniSidebarDetail(sidebarPanel);
    } else {
      sidebarPanel.innerHTML = renderMiniSidebar();
      syncSelectedDetailSnapshot();
      bindMiniAppEvents(sidebarPanel);
    }

    bindMiniAppEvents(statusBar);
    bindMiniAppEvents(navPanel);
    if (!mainPanelPatched) {
      bindMiniAppEvents(mainPanel);
    }
    syncActiveMiniMap();
    return true;
  }

  function renderMiniMain(settings) {
    if (state.tab === "dashboard") {
      return renderDashboardTab();
    }
    if (state.tab === "my-ride") {
      return renderMyRideTab();
    }
    if (state.tab === "report") {
      return renderReportTab();
    }
    if (state.tab === "sightings") {
      return renderSightingsTab();
    }
    if (state.tab === "map") {
      return renderMapTab();
    }
    return renderSettingsTab(settings);
  }

  function renderMiniSidebar() {
    if (state.selectedTrain) {
      return `<h2>${escapeHtml(t("app_live_status"))}</h2>${renderStatusDetail(state.selectedTrain, true)}`;
    }
    return `
      <h2>${escapeHtml(t("app_live_status"))}</h2>
      <p class="panel-subtitle">${escapeHtml(t("app_status_hint"))}</p>
      <div class="empty">${escapeHtml(t("app_status_empty"))}</div>
    `;
  }

  function renderMiniTrainMapContent() {
    const shellState = trainMapShellState("mini-train-map", state.mapData, true);
    if (!shellState.hasTrain) {
      return shellState.html;
    }
    return `
      <div id="mini-map-summary">${renderRideSummary(publicTrainMapSummaryItem())}</div>
      <div id="mini-map-shell-slot">${shellState.html}</div>
      <p class="panel-subtitle map-live-status" id="mini-map-live-status">${escapeHtml(externalFeedStatusText())}</p>
      <p class="panel-subtitle" id="mini-map-missing-coords" ${shellState.missingCoordsText ? "" : "hidden"}>${escapeHtml(shellState.missingCoordsText || "")}</p>
      <section class="detail-card" id="mini-map-sightings-card">
        <h3>${escapeHtml(t("app_recent_platform_sightings"))}</h3>
        ${renderStationSightings(state.mapData && state.mapData.stationSightings)}
      </section>
      <section class="detail-card" id="mini-map-stop-list-card">
        <h3>${escapeHtml(t("app_stop_list"))}</h3>
        <div class="stop-list">${shellState.stopListHTML}</div>
      </section>
    `;
  }

  function renderMiniNetworkMapContent() {
    const shellState = networkMapShellState("mini-network-map", state.networkMapData);
    return `
      <p class="panel-subtitle" id="mini-map-network-note">${escapeHtml(t("app_network_map_note"))}</p>
      <div id="mini-network-map-shell-slot">${shellState.html}</div>
      <p class="panel-subtitle map-live-status" id="mini-map-live-status">${escapeHtml(externalFeedStatusText())}</p>
      <section class="detail-card" id="mini-network-map-sightings-card">
        <h3>${escapeHtml(t("app_recent_platform_sightings"))}</h3>
        ${renderStationSightings(state.networkMapData && state.networkMapData.recentSightings)}
      </section>
    `;
  }

  function patchMiniMapPanel(mainPanel) {
    if (!mainPanel || state.tab !== "map") {
      return false;
    }
    const tabRoot = mainPanel.querySelector("#mini-map-tab");
    if (!tabRoot) {
      return false;
    }
    const expectedMode = state.mapTrainId ? "train" : "network";
    if (tabRoot.getAttribute("data-map-mode") !== expectedMode) {
      mainPanel.innerHTML = renderMapTab();
      bindMiniAppEvents(mainPanel);
      syncActiveMiniMap();
      return true;
    }

    if (expectedMode === "train") {
      const shellState = trainMapShellState("mini-train-map", state.mapData, true);
      if (!shellState.hasTrain) {
        mainPanel.innerHTML = renderMapTab();
        bindMiniAppEvents(mainPanel);
        return true;
      }
      const summaryEl = mainPanel.querySelector("#mini-map-summary");
      const slotEl = mainPanel.querySelector("#mini-map-shell-slot");
      const liveStatusEl = mainPanel.querySelector("#mini-map-live-status");
      const missingCoordsEl = mainPanel.querySelector("#mini-map-missing-coords");
      const sightingsCardEl = mainPanel.querySelector("#mini-map-sightings-card");
      const stopListCardEl = mainPanel.querySelector("#mini-map-stop-list-card");
      if (!summaryEl || !slotEl || !liveStatusEl || !missingCoordsEl || !sightingsCardEl || !stopListCardEl) {
        mainPanel.innerHTML = renderMapTab();
        bindMiniAppEvents(mainPanel);
        syncActiveMiniMap();
        return true;
      }
      syncPublicMapShellSlot(slotEl, "mini-train-map", shellState);
      liveStatusEl.textContent = externalFeedStatusText();
      summaryEl.innerHTML = renderRideSummary(publicTrainMapSummaryItem());
      missingCoordsEl.hidden = !shellState.missingCoordsText;
      missingCoordsEl.textContent = shellState.missingCoordsText || "";
      const nextSightingsHTML = `
        <h3>${escapeHtml(t("app_recent_platform_sightings"))}</h3>
        ${renderStationSightings(state.mapData && state.mapData.stationSightings)}
      `;
      if (sightingsCardEl.innerHTML !== nextSightingsHTML) {
        sightingsCardEl.innerHTML = nextSightingsHTML;
      }
      const nextStopListHTML = `
        <h3>${escapeHtml(t("app_stop_list"))}</h3>
        <div class="stop-list">${shellState.stopListHTML}</div>
      `;
      if (stopListCardEl.innerHTML !== nextStopListHTML) {
        stopListCardEl.innerHTML = nextStopListHTML;
        bindMiniAppEvents(stopListCardEl);
      }
      syncActiveMiniMap();
      return true;
    }

    const shellState = networkMapShellState("mini-network-map", state.networkMapData);
    const noteEl = mainPanel.querySelector("#mini-map-network-note");
    const slotEl = mainPanel.querySelector("#mini-network-map-shell-slot");
    const liveStatusEl = mainPanel.querySelector("#mini-map-live-status");
    const sightingsCardEl = mainPanel.querySelector("#mini-network-map-sightings-card");
    if (!noteEl || !slotEl || !liveStatusEl || !sightingsCardEl) {
      mainPanel.innerHTML = renderMapTab();
      bindMiniAppEvents(mainPanel);
      syncActiveMiniMap();
      return true;
    }
    noteEl.textContent = t("app_network_map_note");
    syncPublicMapShellSlot(slotEl, "mini-network-map", shellState);
    liveStatusEl.textContent = externalFeedStatusText();
    const nextSightingsHTML = `
      <h3>${escapeHtml(t("app_recent_platform_sightings"))}</h3>
      ${renderStationSightings(state.networkMapData && state.networkMapData.recentSightings)}
    `;
    if (sightingsCardEl.innerHTML !== nextSightingsHTML) {
      sightingsCardEl.innerHTML = nextSightingsHTML;
    }
    syncActiveMiniMap();
    return true;
  }

  function renderDashboardTab() {
    const items = state.windowTrains || [];
    return `
      <div class="stack">
        <h2>${escapeHtml(t("app_section_dashboard"))}</h2>
        ${renderDashboardCheckInTools()}
        ${renderCurrentRideHighlight()}
        <p class="panel-subtitle">${escapeHtml(t("app_dashboard_intro"))}</p>
        <section class="detail-card">
          <div class="toolbar">
            ${renderWindowButton("now", "window_now")}
            ${renderWindowButton("next_hour", "window_next_hour")}
            ${renderWindowButton("today", "window_today")}
          </div>
          <div class="card-list">${items.length ? items.map((item) => renderTrainCard(item, false)).join("") : `<div class="empty">${escapeHtml(t("no_trains"))}</div>`}</div>
        </section>
      </div>
    `;
  }

  function renderCurrentRideHighlight() {
    if (!state.currentRide || !state.currentRide.train) {
      return "";
    }
    const ride = state.currentRide;
    const train = ride.train.trainCard.train;
    const boardingStation = ride.boardingStationName
      ? `<div class="badge">${escapeHtml(`${t("app_from")}: ${ride.boardingStationName}`)}</div>`
      : "";
    return `
      <section class="detail-card">
        <h3>${escapeHtml(t("app_section_my_ride"))}</h3>
        ${boardingStation}
        ${renderRideSummary(ride.train)}
        <div class="button-row">
          ${renderPrimaryRideAction(train.id, ride.boardingStationId || "", train.arrivalAt, "primary")}
          <button class="secondary" data-action="tab-report">${escapeHtml(t("btn_report_inspection"))}</button>
          <button class="ghost" data-action="open-map" data-train-id="${escapeAttr(train.id)}">${escapeHtml(t("app_view_stops_map"))}</button>
          <button class="ghost" data-action="mute-train" data-train-id="${escapeAttr(train.id)}">${escapeHtml(t("btn_mute_30m"))}</button>
        </div>
      </section>
    `;
  }

  function renderDashboardCheckInTools() {
    const selectedDeparture = selectedCheckInDeparture();
    const selectedTrain = selectedDeparture && selectedDeparture.trainCard ? selectedDeparture.trainCard.train : null;
    const hasSelection = Boolean(selectedDeparture && selectedTrain);
    const trainNumber = hasSelection ? trainNumberLabel(selectedTrain.id) : "0000";
    const dropdown = state.checkInDropdownOpen && state.stationDepartures.length
      ? `
        <div class="checkin-dropdown" id="checkin-dropdown-menu">
          ${state.stationDepartures.map((item) => {
            const train = item.trainCard && item.trainCard.train ? item.trainCard.train : null;
            if (!train) {
              return "";
            }
            const selected = state.selectedCheckInTrainId === train.id;
            return `
              <button
                class="checkin-dropdown-option ${selected ? "selected" : ""}"
                data-action="choose-checkin-train"
                data-train-id="${escapeAttr(train.id)}"
                id="${selected ? "selected-checkin-option" : ""}"
              >
                ${escapeHtml(stationDepartureLabel(item))}
              </button>
            `;
          }).join("")}
        </div>
      `
      : "";
    return `
      <div class="stack">
        <h3>${escapeHtml(t("app_section_checkin"))}</h3>
        <section class="detail-card checkin-single-menu">
          <h3>${escapeHtml(t("btn_checkin_by_station"))}</h3>
          <p class="panel-subtitle">${escapeHtml(t("checkin_station_prompt"))}</p>
          <div class="form-grid">
            <div class="field">
              <input id="station-query" value="${escapeAttr(state.stationQuery)}" placeholder="${escapeAttr(t("app_search_placeholder"))}">
            </div>
            <div class="button-row checkin-search-actions">
              <button class="primary" id="station-search">${escapeHtml(t("app_find_station"))}</button>
              <button class="secondary" data-action="tab-sightings" ${state.selectedStation ? "" : "disabled"}>${escapeHtml(t("app_report_sighting"))}</button>
            </div>
          </div>
          <div class="divider"></div>
          <div class="card-list">${state.stations.length ? state.stations.map(renderStationMatch).join("") : `<div class="empty">${escapeHtml(t("app_station_results"))}</div>`}</div>
          ${state.selectedStation ? `<div class="badge">${escapeHtml(state.selectedStation.name || state.selectedStation.id)}</div>` : ""}
          ${state.selectedStation && !state.stationDepartures.length ? `<div class="empty">${escapeHtml(t("no_station_trains"))}</div>` : ""}
          ${state.stationDepartures.length ? `
            <div class="divider"></div>
            <div class="checkin-selector-stack">
              <button
                class="checkin-hero-button"
                data-action="toggle-checkin-dropdown"
                aria-expanded="${state.checkInDropdownOpen ? "true" : "false"}"
              >
                <span class="checkin-hero-time">${escapeHtml(formatClock(selectedDeparture.passAt))}</span>
                <span class="checkin-hero-arrow">→</span>
                <span class="checkin-hero-destination">${escapeHtml(selectedTrain.toStation)}</span>
              </button>
              ${dropdown}
              <button
                class="checkin-register-button"
                data-action="selected-checkin"
                data-train-id="${escapeAttr(selectedTrain.id)}"
                data-station-id="${escapeAttr(state.selectedStation.id)}"
              >
                <span class="checkin-register-metric">${escapeHtml(trainNumber)}</span>
                <span class="checkin-register-label">${escapeHtml(t("btn_checkin_confirm"))}</span>
                <span class="checkin-register-metric">${escapeHtml(trainNumber)}</span>
              </button>
              <button
                class="checkin-map-button"
                data-action="selected-checkin-map"
                data-train-id="${escapeAttr(selectedTrain.id)}"
              >${escapeHtml(t("app_view_stops_map"))}</button>
            </div>
          ` : ""}
        </section>
      </div>
    `;
  }

  function renderMyRideTab() {
    if (!state.currentRide || !state.currentRide.train) {
      return `<div class="stack"><h2>${escapeHtml(t("app_section_my_ride"))}</h2><div class="empty">${escapeHtml(t("my_ride_none"))}</div></div>`;
    }
    const ride = state.currentRide;
    const card = ride.train.trainCard;
    return `
      <div class="stack">
        <h2>${escapeHtml(t("app_section_my_ride"))}</h2>
        ${renderStatusDetail(ride.train, true)}
        <div class="button-row">
          <button class="primary" data-action="tab-report">${escapeHtml(t("btn_report_inspection"))}</button>
          <button class="ghost" data-action="open-map" data-train-id="${escapeAttr(card.train.id)}">${escapeHtml(t("app_view_stops_map"))}</button>
          <button class="ghost" data-action="mute-train" data-train-id="${escapeAttr(card.train.id)}">${escapeHtml(t("btn_mute_30m"))}</button>
          <button class="ghost" data-action="undo-checkout">${escapeHtml(t("btn_undo"))}</button>
        </div>
      </div>
    `;
  }

  function renderReportTab() {
    if (!state.currentRide || !state.currentRide.checkIn) {
      return `
        <div class="stack">
          <h2>${escapeHtml(t("app_section_report"))}</h2>
          <div class="empty">${escapeHtml(t("report_requires_checkin"))}</div>
          <div class="button-row">
            <button class="primary" data-action="tab" data-tab="dashboard">${escapeHtml(t("btn_start_checkin"))}</button>
          </div>
        </div>
      `;
    }
    return `
      <div class="stack">
        <h2>${escapeHtml(t("report_prompt"))}</h2>
        <p class="panel-subtitle">${escapeHtml(t("app_report_notice"))}</p>
        ${renderRideSummary(state.currentRide.train)}
        <div class="split">
          <button class="primary" data-action="report" data-signal="INSPECTION_STARTED">${escapeHtml(t("btn_report_started"))}</button>
          <button class="secondary" data-action="report" data-signal="INSPECTION_IN_MY_CAR">${escapeHtml(t("btn_report_in_car"))}</button>
          <button class="warning" data-action="report" data-signal="INSPECTION_ENDED">${escapeHtml(t("btn_report_ended"))}</button>
        </div>
      </div>
    `;
  }

  function renderSightingsTab() {
    if (!state.selectedStation) {
      return `
        <div class="stack">
          <h2>${escapeHtml(t("app_section_sightings"))}</h2>
          <div class="empty">${escapeHtml(t("app_sightings_empty"))}</div>
          <div class="button-row">
            <button class="primary" data-action="tab" data-tab="dashboard">${escapeHtml(t("app_sightings_choose_station"))}</button>
          </div>
        </div>
      `;
    }
    const departures = filteredStationDepartures();
    const selectedDeparture = selectedSightingDeparture();
    const destinationOptions = [`<option value="">${escapeHtml(t("app_station_sighting_destination_any"))}</option>`]
      .concat(state.stationSightingDestinations.map((item) => `<option value="${escapeAttr(item.id)}" ${state.stationSightingDestinationId === item.id ? "selected" : ""}>${escapeHtml(item.name)}</option>`))
      .join("");
    return `
      <div class="stack">
        <h2>${escapeHtml(t("app_section_sightings"))}</h2>
        <p class="panel-subtitle">${escapeHtml(t("app_station_sighting_note"))}</p>
        <div class="badge">${escapeHtml(state.selectedStation.name || state.selectedStation.id)}</div>
        <section class="detail-card">
          <div class="sighting-toolbar">
            <div class="field">
              <label>${escapeHtml(t("app_station_sighting_destination_label"))}</label>
              <select id="station-sighting-destination">${destinationOptions}</select>
            </div>
            <div class="button-row">
              <button class="${selectedDeparture ? "secondary" : "washed-success"}" id="station-sighting-submit">${escapeHtml(t("app_station_sighting_submit"))}</button>
            </div>
          </div>
          ${selectedDeparture ? `<div class="badge">${escapeHtml(`${t("app_station_sighting_selected_departure")}: ${selectedDeparture.trainCard.train.fromStation} → ${selectedDeparture.trainCard.train.toStation}`)}</div>` : ""}
        </section>
        <section class="detail-card">
          <h3>${escapeHtml(t("app_station_sighting_candidates"))}</h3>
          <div class="card-list">${departures.length ? departures.map((item) => renderStationDepartureCard(item, "sightings")).join("") : `<div class="empty">${escapeHtml(t("app_station_sighting_departures_empty"))}</div>`}</div>
        </section>
        <section class="detail-card">
          <h3>${escapeHtml(t("app_recent_platform_sightings"))}</h3>
          ${renderStationSightings(state.stationRecentSightings)}
        </section>
      </div>
    `;
  }

  function renderMapTab() {
    if (state.mapTrainId) {
      return `
        <div class="stack" id="mini-map-tab" data-map-mode="train">
          <h2>${escapeHtml(t("app_section_map"))}</h2>
          ${renderMiniTrainMapContent()}
        </div>
      `;
    }
    return `
      <div class="stack" id="mini-map-tab" data-map-mode="network">
        <h2>${escapeHtml(t("app_section_map"))}</h2>
        ${renderMiniNetworkMapContent()}
      </div>
    `;
  }

  function renderSettingsTab(settings) {
    return `
      <div class="stack">
        <h2>${escapeHtml(t("settings_title"))}</h2>
        <div class="form-grid">
          <div class="field">
            <label>${escapeHtml(t("settings_alerts_label"))}</label>
            <input id="settings-alerts" type="checkbox" ${settings.alertsEnabled ? "checked" : ""}>
          </div>
          <div class="field">
            <label>${escapeHtml(t("settings_reports_channel_label"))}</label>
            <div class="button-row">
              <a class="button secondary" href="${escapeAttr(t("link_reports_channel"))}" target="_blank" rel="noopener noreferrer">${escapeHtml(t("btn_open_reports_channel"))}</a>
            </div>
          </div>
          <div class="field">
            <label>${escapeHtml(t("settings_alert_style_label"))}</label>
            <select id="settings-style">
              <option value="DETAILED" ${settings.alertStyle === "DETAILED" ? "selected" : ""}>${escapeHtml(t("settings_style_detailed_option"))}</option>
              <option value="DISCREET" ${settings.alertStyle === "DISCREET" ? "selected" : ""}>${escapeHtml(t("settings_style_discreet_option"))}</option>
            </select>
          </div>
          <div class="field">
            <label>${escapeHtml(t("settings_language_label"))}</label>
            <select id="settings-language">
              <option value="EN" ${settings.language === "EN" ? "selected" : ""}>EN</option>
              <option value="LV" ${settings.language === "LV" ? "selected" : ""}>LV</option>
            </select>
          </div>
          <div class="button-row">
            <button class="primary" id="save-settings">${escapeHtml(t("btn_confirm"))}</button>
          </div>
        </div>
      </div>
    `;
  }

  function renderTrainMapPanel(containerId, mapData, includeSelectionPrompt) {
    if (!mapData || !mapData.train) {
      return `<div class="empty">${escapeHtml(includeSelectionPrompt ? t("app_map_prompt") : t("app_map_empty"))}</div>`;
    }
    const fallbackStatus = state.selectedTrain && state.selectedTrain.trainCard && state.selectedTrain.trainCard.train
      && state.selectedTrain.trainCard.train.id === mapData.train.id
      ? state.selectedTrain.trainCard.status
      : null;
    const fallbackRiders = state.selectedTrain && state.selectedTrain.trainCard && state.selectedTrain.trainCard.train
      && state.selectedTrain.trainCard.train.id === mapData.train.id
      ? state.selectedTrain.trainCard.riders
      : 0;
    const summaryItem = mapData.trainCard
      ? { trainCard: mapData.trainCard }
      : { trainCard: { train: mapData.train, status: fallbackStatus, riders: fallbackRiders } };
    const stops = Array.isArray(mapData.stops) ? mapData.stops : [];
    const locatedStops = stops.filter((stop) => typeof stop.latitude === "number" && typeof stop.longitude === "number");
    const mapEmpty = locatedStops.length
      ? `<div id="${escapeAttr(containerId)}" class="train-map-shell" aria-label="${escapeAttr(t("app_section_map"))}"></div>`
      : `<div class="empty">${escapeHtml(t("app_map_empty"))}</div>`;
    const stopList = stops.length
      ? stops.map((stop, index) => renderStopRow(stop, index, mapData)).join("")
      : `<div class="empty">${escapeHtml(t("app_map_empty"))}</div>`;
    return `
      <div class="stack">
        ${renderRideSummary(summaryItem)}
        ${mapEmpty}
        <p class="panel-subtitle map-live-status">${escapeHtml(externalFeedStatusText())}</p>
        ${stops.length && locatedStops.length !== stops.length ? `<p class="panel-subtitle">${escapeHtml(t("app_map_missing_coords"))}</p>` : ""}
        <section class="detail-card">
          <h3>${escapeHtml(t("app_recent_platform_sightings"))}</h3>
          ${renderStationSightings(mapData.stationSightings)}
        </section>
        <section class="detail-card">
          <h3>${escapeHtml(t("app_stop_list"))}</h3>
          <div class="stop-list">${stopList}</div>
        </section>
      </div>
    `;
  }

  function renderNetworkMapPanel(containerId, mapData) {
    const stations = mapData && Array.isArray(mapData.stations) ? mapData.stations : [];
    const mapEmpty = stations.length
      ? `<div id="${escapeAttr(containerId)}" class="train-map-shell" aria-label="${escapeAttr(t("app_network_map_title"))}"></div>`
      : `<div class="empty">${escapeHtml(t("app_network_map_empty"))}</div>`;
    return `
      <div class="stack">
        ${mapEmpty}
        <p class="panel-subtitle map-live-status">${escapeHtml(externalFeedStatusText())}</p>
        <section class="detail-card">
          <h3>${escapeHtml(t("app_recent_platform_sightings"))}</h3>
          ${renderStationSightings(mapData && mapData.recentSightings)}
        </section>
      </div>
    `;
  }

  function renderStationSightings(items) {
    const sightings = Array.isArray(items) ? items : [];
    if (!sightings.length) {
      return `<div class="empty">${escapeHtml(t("app_station_sighting_empty"))}</div>`;
    }
    return `
      <div class="card-list">
        ${sightings.map((item) => {
          const destination = item.destinationStationName ? ` • ${item.destinationStationName}` : "";
          const matched = item.matchedTrainInstanceId ? ` • ${t("app_station_sighting_matched")}` : "";
          return `
            <article class="sighting-card">
              <strong>${escapeHtml(item.stationName || item.stationId)}${escapeHtml(destination)}</strong>
              <div class="meta">
                <span>${escapeHtml(relativeAgo(item.createdAt))}</span>
                <span>${escapeHtml(item.matchedTrainInstanceId ? t("app_station_sighting_matched") : t("app_station_sighting_unmatched"))}</span>
              </div>
            </article>
          `;
        }).join("")}
      </div>
    `;
  }

  function renderStopRow(stop, index, mapData) {
    const sightings = stopSightings(stop, mapData);
    const key = stopContextKey(stop, index);
    const expanded = state.expandedStopContextKey === key;
    const latestSighting = latestStationSighting(sightings);
    const bucketClass = latestSighting ? `bucket-${sightingRecencyBucket(latestSighting.createdAt)}` : "";
    const content = `
      <div class="stop-index">${escapeHtml(String(index + 1))}</div>
      <div class="stop-copy">
        <strong class="stop-title">${escapeHtml(stop.stationName || stop.stationId || "")}</strong>
        <div class="stop-meta">
          <span class="stop-primary-time">${escapeHtml(stopTimeLabel(stop, index))}</span>
          ${latestSighting ? `<span class="stop-recency-pill ${escapeAttr(bucketClass)}">${escapeHtml(relativeAgo(latestSighting.createdAt))}</span>` : ""}
        </div>
      </div>
    `;
    return `
      <article class="stop-row ${sightings.length ? `with-sighting ${escapeAttr(bucketClass)}` : ""} ${expanded ? "expanded" : ""}">
        <button class="stop-row-button" data-action="toggle-stop-context" data-stop-key="${escapeAttr(key)}" aria-expanded="${expanded ? "true" : "false"}">${content}</button>
        ${expanded ? `
          <div class="stop-context">
            <div class="stop-detail-pills">
              ${renderStopDetailPills(stop, index)}
            </div>
            ${sightings.length ? `
              <h4>${escapeHtml(t("app_sighting_context_title"))}</h4>
              ${renderStationSightings(sightings)}
              <div class="button-row">
                <button class="ghost small" data-action="open-sightings-stop" data-station-id="${escapeAttr(stop.stationId || "")}" data-train-id="${escapeAttr(mapData && mapData.train ? mapData.train.id : "")}">${escapeHtml(t("app_stop_context_open_full"))}</button>
              </div>
            ` : ""}
          </div>
        ` : ""}
      </article>
    `;
  }

  function renderHero(eyebrow, title, body) {
    return `
      <section class="hero">
        ${eyebrow ? `<span class="eyebrow">${escapeHtml(eyebrow)}</span>` : ""}
        <h1>${escapeHtml(title)}</h1>
        ${body ? `<p>${escapeHtml(body)}</p>` : ""}
        ${renderScheduleBanner()}
      </section>
    `;
  }

  function renderScheduleBanner() {
    const meta = state.scheduleMeta;
    if (!meta || !meta.fallbackActive || !meta.effectiveServiceDate) {
      return "";
    }
    return `<div class="schedule-banner">${escapeHtml(t("schedule_fallback_notice", meta.effectiveServiceDate))}</div>`;
  }

  function renderTabButton(id, label) {
    return `<button class="tab-button ${state.tab === id ? "active" : ""}" data-action="tab" data-tab="${escapeAttr(id)}">${escapeHtml(label)}</button>`;
  }

  function renderWindowButton(id, labelKey) {
    return `<button class="${state.window === id ? "primary" : "ghost"}" data-action="window" data-window="${escapeAttr(id)}">${escapeHtml(t(labelKey))}</button>`;
  }

  function defaultCheckInTrainId(items) {
    const departures = Array.isArray(items) ? items : [];
    if (!departures.length) {
      return "";
    }
    const nowMs = Date.now();
    let best = departures[0];
    let bestDelta = Math.abs(Date.parse(departures[0].passAt || "") - nowMs);
    departures.slice(1).forEach((item) => {
      const delta = Math.abs(Date.parse(item.passAt || "") - nowMs);
      if (delta < bestDelta) {
        best = item;
        bestDelta = delta;
      }
    });
    return best && best.trainCard && best.trainCard.train ? best.trainCard.train.id || "" : "";
  }

  function selectedCheckInDeparture() {
    const items = Array.isArray(state.stationDepartures) ? state.stationDepartures : [];
    if (!items.length) {
      return null;
    }
    if (state.selectedCheckInTrainId) {
      const explicit = items.find((item) => item.trainCard && item.trainCard.train && item.trainCard.train.id === state.selectedCheckInTrainId);
      if (explicit) {
        return explicit;
      }
    }
    return items.find(Boolean) || null;
  }

  function stationDepartureLabel(item) {
    if (!item || !item.trainCard || !item.trainCard.train) {
      return "";
    }
    return `${formatClock(item.passAt)} → ${item.trainCard.train.toStation}`;
  }

  function trainNumberLabel(trainId) {
    const match = String(trainId || "").match(/(\d{3,5})$/);
    return match ? match[1] : "0000";
  }

  function renderPublicCard(item) {
    const train = item.train;
    return `
      <article class="train-card">
        <h3>${escapeHtml(train.fromStation)} → ${escapeHtml(train.toStation)}</h3>
        <div class="meta">
          <span>${escapeHtml(train.departureAt ? formatDateTime(train.departureAt) : "")}</span>
          <span>${escapeHtml(statusSummary(item.status))}</span>
        </div>
        <div class="card-actions">
          <a class="button ghost small" href="${escapeAttr(`${cfg.basePath}/t/${train.id}`)}">${escapeHtml(t("btn_view_status"))}</a>
          <a class="button ghost small" href="${escapeAttr(publicTrainMapRoot(train.id))}">${escapeHtml(t("app_view_stops_map"))}</a>
        </div>
      </article>
    `;
  }

  function renderPublicStationMatch(item) {
    return `<button class="ghost" data-action="public-station-departures" data-station-id="${escapeAttr(item.id)}">${escapeHtml(item.name)}</button>`;
  }

  function renderPublicStationDepartureCard(item) {
    const card = item.trainCard;
    const train = card.train;
    return `
      <article class="train-card">
        <h3>${escapeHtml(train.fromStation)} → ${escapeHtml(train.toStation)}</h3>
        <div class="meta">
          <span>${escapeHtml(t("station_pass_line", item.stationName, formatClock(item.passAt)))}</span>
          <span>${escapeHtml(statusSummary(card.status))}</span>
        </div>
        <div class="card-actions">
          <a class="button ghost small" href="${escapeAttr(`${cfg.basePath}/t/${train.id}`)}">${escapeHtml(t("btn_view_status"))}</a>
          <a class="button ghost small" href="${escapeAttr(publicTrainMapRoot(train.id))}">${escapeHtml(t("app_view_stops_map"))}</a>
        </div>
      </article>
    `;
  }

  function renderPublicDetail(item) {
    return renderStatusDetail({
      trainCard: {
        train: item.train,
        status: item.status,
        riders: 0,
      },
      timeline: item.timeline || [],
      stationSightings: item.stationSightings || [],
    }, false, true);
  }

  function isCurrentRideTrain(trainId) {
    return Boolean(state.currentRide && state.currentRide.checkIn && state.currentRide.checkIn.trainInstanceId === trainId);
  }

  function canDirectCheckIn(...eligibleUntilAts) {
    let sawFiniteDeadline = false;
    for (const rawValue of eligibleUntilAts) {
      const eligibleUntilMs = Date.parse(rawValue || "");
      if (!Number.isFinite(eligibleUntilMs)) {
        continue;
      }
      sawFiniteDeadline = true;
      if (eligibleUntilMs + (10 * 60 * 1000) <= Date.now()) {
        return false;
      }
    }
    return true;
  }

  function renderPrimaryRideAction(trainId, stationId, eligibleUntilAt, className) {
    if (isCurrentRideTrain(trainId)) {
      return `<button class="${escapeAttr(className)}" data-action="checkout" data-train-id="${escapeAttr(trainId)}">${escapeHtml(t("btn_checkout"))}</button>`;
    }
    const deadlines = Array.isArray(eligibleUntilAt) ? eligibleUntilAt : [eligibleUntilAt];
    if (!canDirectCheckIn(...deadlines)) {
      return "";
    }
    return `<button class="${escapeAttr(className)}" data-action="checkin" data-train-id="${escapeAttr(trainId)}" data-station-id="${escapeAttr(stationId || "")}">${escapeHtml(t("btn_checkin_confirm"))}</button>`;
  }

  function renderRideSummary(item) {
    const card = item.trainCard;
    const train = card.train;
    return `
      <article class="detail-card">
        <h3>${escapeHtml(train.fromStation)} → ${escapeHtml(train.toStation)}</h3>
        <div class="meta">
          <span>${escapeHtml(`${formatClock(train.departureAt)} • ${formatClock(train.arrivalAt)}`)}</span>
          <span>${escapeHtml(statusSummary(card.status))}</span>
          <span>${escapeHtml(t("ride_riders", card.riders || 0))}</span>
        </div>
      </article>
    `;
  }

  function filteredStationDepartures() {
    const items = Array.isArray(state.stationDepartures) ? state.stationDepartures : [];
    const destinationName = selectedSightingDestinationName();
    if (!destinationName) {
      return items;
    }
    return items.filter((item) => {
      const train = item && item.trainCard ? item.trainCard.train : null;
      return Boolean(train && String(train.toStation || "").toLowerCase() === destinationName.toLowerCase());
    });
  }

  function selectedSightingDestinationName() {
    if (!state.stationSightingDestinationId) {
      return "";
    }
    const match = (state.stationSightingDestinations || []).find((item) => item.id === state.stationSightingDestinationId);
    return match ? match.name || "" : "";
  }

  function selectedSightingDeparture() {
    if (!state.selectedSightingTrainId) {
      return null;
    }
    return filteredStationDepartures().find((item) => item.trainCard && item.trainCard.train && item.trainCard.train.id === state.selectedSightingTrainId) || null;
  }

  function renderStationDepartureCard(item, mode) {
    const card = item.trainCard;
    const train = card.train;
    const trainId = train.id;
    const expanded = state.expandedStationContextTrainId === trainId;
    const selected = state.selectedSightingTrainId === trainId;
    const context = expanded ? renderStationDepartureContext(item, mode) : "";
    const primaryActions = mode === "sightings"
      ? ""
      : `${renderPrimaryRideAction(train.id, item.stationId, [item.passAt, train.arrivalAt], "primary small")}
          <button class="ghost small" data-action="open-status" data-train-id="${escapeAttr(train.id)}">${escapeHtml(t("btn_view_status"))}</button>
          <button class="ghost small" data-action="open-map" data-train-id="${escapeAttr(train.id)}">${escapeHtml(t("app_view_stops_map"))}</button>`;
    return `
      <article class="train-card station-departure-card ${selected ? "selected-train-card" : ""} ${expanded ? "expanded" : ""}">
        <div class="station-card-header">
          <h3>${escapeHtml(train.fromStation)} → ${escapeHtml(train.toStation)}</h3>
          ${selected ? `<span class="station-selected-pill">${escapeHtml(t("app_station_sighting_selected_departure"))}</span>` : ""}
        </div>
        ${primaryActions ? `<div class="card-actions station-primary-actions">
          ${primaryActions}
        </div>` : ""}
        <button class="station-info-toggle ${item.sightingCount > 0 ? "highlighted" : ""}" data-action="toggle-station-context" data-train-id="${escapeAttr(trainId)}" aria-expanded="${expanded ? "true" : "false"}">
          <span class="station-info-main">${escapeHtml(statusSummary(card.status))}</span>
          <span class="station-info-pill">${escapeHtml(sightingMetricLabel(item.sightingCount || 0))}</span>
        </button>
        ${context}
      </article>
    `;
  }

  function renderStationDepartureContext(item, mode) {
    const card = item.trainCard;
    const action = mode === "sightings"
      ? `<button class="${state.selectedSightingTrainId === card.train.id ? "secondary small" : "ghost small"}" data-action="select-sighting-train" data-train-id="${escapeAttr(card.train.id)}">${escapeHtml(state.selectedSightingTrainId === card.train.id ? t("app_station_sighting_selected_departure") : t("app_station_sighting_select_departure"))}</button>`
      : `<button class="ghost small" data-action="open-sightings-train" data-train-id="${escapeAttr(card.train.id)}">${escapeHtml(t("app_station_sighting_select_departure"))}</button>`;
    return `
      <section class="station-context">
        <div class="meta">
          <span>${escapeHtml(t("station_pass_line", item.stationName, formatClock(item.passAt)))}</span>
          <span>${escapeHtml(`${formatClock(card.train.departureAt)} • ${formatClock(card.train.arrivalAt)}`)}</span>
          <span>${escapeHtml(t("ride_riders", card.riders))}</span>
        </div>
        <h4>${escapeHtml(statusSummary(card.status))}</h4>
        ${renderStationSightings(item.sightingContext)}
        <div class="button-row">${action}</div>
      </section>
    `;
  }

  function sightingMetricLabel(count) {
    if (!count) return t("app_sighting_metric_zero");
    if (count === 1) return t("app_sighting_metric_one");
    return t("app_sighting_metric_many", count);
  }

  function renderTrainCard(item, stationMode, stationId) {
    const train = item.train;
    return `
      <article class="train-card">
        <h3>${escapeHtml(train.fromStation)} → ${escapeHtml(train.toStation)}</h3>
        <div class="meta">
          <span>${escapeHtml(`${formatClock(train.departureAt)} • ${formatClock(train.arrivalAt)}`)}</span>
          <span>${escapeHtml(statusSummary(item.status))}</span>
          <span>${escapeHtml(t("ride_riders", item.riders))}</span>
        </div>
        <div class="card-actions">
          ${renderPrimaryRideAction(train.id, stationMode ? stationId : "", train.arrivalAt, "primary small")}
          <button class="ghost small" data-action="open-status" data-train-id="${escapeAttr(train.id)}">${escapeHtml(t("btn_view_status"))}</button>
          <button class="ghost small" data-action="open-map" data-train-id="${escapeAttr(train.id)}">${escapeHtml(t("app_view_stops_map"))}</button>
        </div>
      </article>
    `;
  }

  function renderRouteCard(item) {
    const routeKey = `${item.fromStationId}:${item.toStationId}`;
    const isFavorite = state.favorites.some((entry) => `${entry.fromStationId}:${entry.toStationId}` === routeKey);
    return `
      <article class="favorite-card">
        <h3>${escapeHtml(item.fromStationName)} → ${escapeHtml(item.toStationName)}</h3>
        <div class="meta">
          <span>${escapeHtml(`${formatClock(item.fromPassAt)} → ${formatClock(item.toPassAt)}`)}</span>
          <span>${escapeHtml(statusSummary(item.trainCard.status))}</span>
        </div>
        <div class="card-actions">
          ${renderPrimaryRideAction(item.trainCard.train.id, "", item.trainCard.train.arrivalAt, "primary small")}
          <button class="ghost small" data-action="open-status" data-train-id="${escapeAttr(item.trainCard.train.id)}">${escapeHtml(t("btn_view_status"))}</button>
          <button class="ghost small" data-action="open-map" data-train-id="${escapeAttr(item.trainCard.train.id)}">${escapeHtml(t("app_view_stops_map"))}</button>
          <button class="ghost small" data-action="${isFavorite ? "remove-favorite" : "save-favorite"}" data-from-station-id="${escapeAttr(item.fromStationId)}" data-to-station-id="${escapeAttr(item.toStationId)}">${escapeHtml(isFavorite ? t("btn_remove_favorite") : t("btn_save_route"))}</button>
        </div>
      </article>
    `;
  }

  function renderFavoriteRoute(item) {
    return `
      <article class="favorite-card">
        <h3>${escapeHtml(item.fromStationName)} → ${escapeHtml(item.toStationName)}</h3>
        <div class="card-actions">
          <button class="ghost small" data-action="favorite-open" data-from-station-id="${escapeAttr(item.fromStationId)}" data-from-station-name="${escapeAttr(item.fromStationName)}" data-to-station-id="${escapeAttr(item.toStationId)}" data-to-station-name="${escapeAttr(item.toStationName)}">${escapeHtml(t("btn_view_status"))}</button>
          <button class="warning small" data-action="remove-favorite" data-from-station-id="${escapeAttr(item.fromStationId)}" data-to-station-id="${escapeAttr(item.toStationId)}">${escapeHtml(t("btn_remove_favorite"))}</button>
        </div>
      </article>
    `;
  }

  function renderStationMatch(item) {
    return `<button class="ghost" data-action="station-departures" data-station-id="${escapeAttr(item.id)}" data-station-name="${escapeAttr(item.name)}">${escapeHtml(item.name)}</button>`;
  }

  function renderOriginMatch(item) {
    return `<button class="ghost" data-action="choose-origin" data-station-id="${escapeAttr(item.id)}" data-station-name="${escapeAttr(item.name)}">${escapeHtml(item.name)}</button>`;
  }

  function renderDestinationMatch(item) {
    return `<button class="ghost" data-action="choose-destination" data-station-id="${escapeAttr(item.id)}" data-station-name="${escapeAttr(item.name)}">${escapeHtml(item.name)}</button>`;
  }

  function statusStateLabel(status) {
    if (!status || status.state === "NO_REPORTS" || !status.lastReportAt) {
      return t("app_detail_state_no_reports");
    }
    if (status.state === "MIXED_REPORTS") {
      return t("app_detail_state_mixed_reports");
    }
    return t("app_detail_state_last_sighting");
  }

  function renderStatusDetailLastUpdate(lastUpdateText) {
    if (!lastUpdateText) {
      return "";
    }
    const ariaLabel = t("app_detail_last_update_aria", lastUpdateText);
    return `
      <span class="detail-last-update-chip" title="${escapeAttr(ariaLabel)}" aria-label="${escapeAttr(ariaLabel)}">
        <span class="detail-last-update-icon" aria-hidden="true">🕒</span>
        <span class="detail-last-update-value">${escapeHtml(lastUpdateText)}</span>
      </span>
    `;
  }

  function buildStatusDetailSnapshot(item, includeActions, publicView) {
    const card = item.trainCard;
    const train = card.train;
    const lastUpdateText = card.status && card.status.lastReportAt ? relativeAgo(card.status.lastReportAt) : "";
    const timelineHTML = `
      <ul class="timeline">${(item.timeline || []).length ? item.timeline.map((entry) => `<li><span>${escapeHtml(formatClock(entry.at))}</span><span>${escapeHtml(signalLabel(entry.signal))} (${escapeHtml(String(entry.count))})</span></li>`).join("") : `<li><span>${escapeHtml(t("status_no_recent_events"))}</span></li>`}</ul>
    `;
    return {
      trainId: train.id || "",
      title: `${train.fromStation} → ${train.toStation}`,
      lastUpdateHTML: renderStatusDetailLastUpdate(lastUpdateText),
      metaHTML: `
        <span>${escapeHtml(`${formatClock(train.departureAt)} • ${formatClock(train.arrivalAt)}`)}</span>
        <span>${escapeHtml(statusStateLabel(card.status))}</span>
      `,
      ridersHTML: `<span>${escapeHtml(t("ride_riders", card.riders || 0))}</span>`,
      actionsHTML: includeActions ? `
        ${renderPrimaryRideAction(train.id, "", train.arrivalAt, "primary small")}
        <button class="ghost small" data-action="open-map" data-train-id="${escapeAttr(train.id)}">${escapeHtml(t("app_view_stops_map"))}</button>
        <button class="ghost small" data-action="mute-train" data-train-id="${escapeAttr(train.id)}">${escapeHtml(t("btn_mute_30m"))}</button>
      ` : (!includeActions && publicView ? `<a class="button ghost small" href="${escapeAttr(publicTrainMapRoot(train.id))}">${escapeHtml(t("app_view_stops_map"))}</a>` : ""),
      timelineHTML: timelineHTML,
      sightingsHTML: `
        <h3>${escapeHtml(t("app_recent_platform_sightings"))}</h3>
        ${renderStationSightings(item.stationSightings)}
      `,
    };
  }

  function renderStatusDetailFromSnapshot(snapshot) {
    return `
      <article class="detail-card detail-status-card" data-role="status-detail" data-train-id="${escapeAttr(snapshot.trainId)}">
        <div class="detail-card-header" data-detail-key="header">
          <h3 data-detail-key="title">${escapeHtml(snapshot.title)}</h3>
          <div class="detail-last-update-slot" data-detail-key="last-update">${snapshot.lastUpdateHTML}</div>
        </div>
        <div class="meta detail-status-meta" data-detail-key="meta">${snapshot.metaHTML}</div>
        <div class="detail-status-riders" data-detail-key="riders">${snapshot.ridersHTML}</div>
        ${snapshot.actionsHTML ? `<div class="card-actions detail-status-actions" data-detail-key="actions">${snapshot.actionsHTML}</div>` : ""}
        <div class="detail-status-section" data-detail-key="timeline">${snapshot.timelineHTML}</div>
        <div class="divider"></div>
        <div class="detail-status-section" data-detail-key="sightings">${snapshot.sightingsHTML}</div>
      </article>
    `;
  }

  function renderStatusDetail(item, includeActions, publicView) {
    return renderStatusDetailFromSnapshot(buildStatusDetailSnapshot(item, includeActions, publicView));
  }

  function flashDetailSection(sectionEl) {
    if (!sectionEl) {
      return;
    }
    sectionEl.classList.remove("detail-section-updated");
    void sectionEl.offsetWidth;
    sectionEl.classList.add("detail-section-updated");
    if (sectionEl._detailFlashTimer) {
      window.clearTimeout(sectionEl._detailFlashTimer);
    }
    sectionEl._detailFlashTimer = window.setTimeout(() => {
      sectionEl.classList.remove("detail-section-updated");
      sectionEl._detailFlashTimer = 0;
    }, 220);
  }

  function replaceDetailSection(cardEl, key, nextHTML, options) {
    const sectionEl = cardEl.querySelector(`[data-detail-key="${key}"]`);
    if (!sectionEl || sectionEl.innerHTML === nextHTML) {
      return false;
    }
    sectionEl.innerHTML = nextHTML;
    if (!options || options.flash !== false) {
      flashDetailSection(sectionEl);
    }
    return true;
  }

  function patchStatusDetailCard(cardEl, previousSnapshot, nextSnapshot) {
    const titleEl = cardEl.querySelector("[data-detail-key='title']");
    if (titleEl && titleEl.textContent !== nextSnapshot.title) {
      titleEl.textContent = nextSnapshot.title;
    }
    replaceDetailSection(cardEl, "last-update", nextSnapshot.lastUpdateHTML, { flash: false });
    replaceDetailSection(cardEl, "meta", nextSnapshot.metaHTML);
    replaceDetailSection(cardEl, "riders", nextSnapshot.ridersHTML);
    const actionsChanged = replaceDetailSection(cardEl, "actions", nextSnapshot.actionsHTML, { flash: false });
    replaceDetailSection(cardEl, "timeline", nextSnapshot.timelineHTML);
    replaceDetailSection(cardEl, "sightings", nextSnapshot.sightingsHTML);
    if (cardEl.getAttribute("data-train-id") !== nextSnapshot.trainId) {
      cardEl.setAttribute("data-train-id", nextSnapshot.trainId);
    }
    if (actionsChanged) {
      const actionsEl = cardEl.querySelector("[data-detail-key='actions']");
      if (actionsEl) {
        bindMiniAppEvents(actionsEl);
      }
    }
  }

  function patchMiniSidebarDetail(sidebarPanel) {
    if (!sidebarPanel || !state.selectedTrain) {
      return;
    }
    const titleEl = sidebarPanel.querySelector("h2");
    if (titleEl) {
      titleEl.textContent = t("app_live_status");
    }
    const cardEl = sidebarPanel.querySelector("[data-role='status-detail']");
    const nextSnapshot = buildStatusDetailSnapshot(state.selectedTrain, true, false);
    if (!cardEl || !selectedDetailSnapshot || selectedDetailSnapshot.trainId !== nextSnapshot.trainId) {
      sidebarPanel.innerHTML = renderMiniSidebar();
      syncSelectedDetailSnapshot();
      bindMiniAppEvents(sidebarPanel);
      return;
    }
    patchStatusDetailCard(cardEl, selectedDetailSnapshot, nextSnapshot);
    selectedDetailSnapshot = nextSnapshot;
  }

  function bindPublicDashboardEvents() {
    const input = document.getElementById("public-filter");
    if (input) {
      input.addEventListener("input", (event) => {
        state.publicFilterDraft = event.target.value;
      });
      input.addEventListener("keydown", (event) => {
        if (event.key === "Enter") {
          event.preventDefault();
          applyPublicFilter();
        }
      });
    }
    const search = document.getElementById("public-search");
    if (search) {
      search.addEventListener("click", () => {
        applyPublicFilter();
      });
    }
    const refresh = document.getElementById("public-refresh");
    if (refresh) {
      refresh.addEventListener("click", () => {
        runUserAction(async () => {
          await refreshPublicDashboard();
          renderPublicDashboard({ preserveInputFocus: true });
        }, t("app_refresh_success"));
      });
    }
  }

  function bindPublicStationEvents(root) {
    const scope = root || document;
    const stationSearch = scope.querySelector("#public-station-search");
    const stationQueryInput = scope.querySelector("#public-station-query");
    if (stationQueryInput) {
      stationQueryInput.addEventListener("input", (event) => {
        state.publicStationQuery = event.target.value;
      });
    }
    const searchAction = () => runUserAction(async () => {
      await searchPublicStations(state.publicStationQuery);
    }, t("app_public_station_search_success"));
    if (stationSearch) {
      stationSearch.addEventListener("click", searchAction);
    }
    bindEnterAction("public-station-query", searchAction, scope);
    scope.querySelectorAll("[data-action='public-station-departures']").forEach((el) => {
      el.addEventListener("click", () => {
        runUserAction(async () => {
          await refreshPublicStationDepartures(el.getAttribute("data-station-id"));
          renderPublicStationSearch({ preserveInputFocus: true });
        }, t("app_public_station_departures_loaded"));
      });
    });
    const refresh = scope.querySelector("#public-station-refresh");
    if (refresh) {
      refresh.addEventListener("click", () => {
        runUserAction(async () => {
          await refreshPublicNetworkMap();
          if (state.publicStationSelected) {
            await refreshPublicStationDepartures(state.publicStationSelected.id);
            renderPublicStationSearch({ preserveInputFocus: true });
            return t("app_public_station_departures_loaded");
          }
          if (state.publicStationQuery) {
            await searchPublicStations(state.publicStationQuery);
            return t("app_public_station_search_success");
          }
          return null;
        }, (message) => message);
      });
    }
  }

  function bindEnterAction(inputId, action, root) {
    const scope = root || document;
    const input = scope.querySelector(`#${inputId}`);
    if (!input) return;
    input.addEventListener("keydown", (event) => {
      if (event.key !== "Enter") return;
      event.preventDefault();
      action();
    });
  }

  function bindMiniAppEvents(root) {
    const scope = root || document;
    scope.querySelectorAll("[data-action='tab']").forEach((el) => {
      el.addEventListener("click", async () => {
        setMiniAppTab(el.getAttribute("data-tab"), "nav-tab");
        if (state.tab === "my-ride" || state.tab === "report") {
          await refreshCurrentRide();
        }
        if (state.tab === "sightings" && state.selectedStation) {
          await fetchStationDepartures(state.selectedStation.id);
        }
        if (state.tab === "map") {
          await refreshActiveMapView();
        }
        renderMiniApp();
      });
    });
    const globalRefresh = scope.querySelector("#global-refresh");
    if (globalRefresh) {
      globalRefresh.addEventListener("click", () => {
        runUserAction(async () => {
          await Promise.all([refreshCurrentRide(), refreshWindowTrains(), refreshFavorites(), refreshNetworkMapData(true)]);
          renderMiniApp();
        }, t("app_refresh_success"));
      });
    }
    scope.querySelectorAll("[data-action='window']").forEach((el) => {
      el.addEventListener("click", () => {
        state.window = el.getAttribute("data-window");
        runUserAction(async () => {
          await refreshWindowTrains();
          renderMiniApp();
        }, t("app_refresh_success"));
      });
    });
    scope.querySelectorAll("[data-action='open-status']").forEach((el) => {
      el.addEventListener("click", () => runUserAction(() => openStatus(el.getAttribute("data-train-id")), t("app_status_loaded")));
    });
    scope.querySelectorAll("[data-action='open-map']").forEach((el) => {
      el.addEventListener("click", () => runUserAction(() => openMap(el.getAttribute("data-train-id")), t("app_map_loaded")));
    });
    scope.querySelectorAll("[data-action='toggle-checkin-dropdown']").forEach((el) => {
      el.addEventListener("click", () => {
        if (!state.stationDepartures.length) {
          return;
        }
        state.checkInDropdownOpen = !state.checkInDropdownOpen;
        renderMiniApp();
      });
    });
    scope.querySelectorAll("[data-action='choose-checkin-train']").forEach((el) => {
      el.addEventListener("click", () => {
        state.selectedCheckInTrainId = el.getAttribute("data-train-id") || "";
        state.checkInDropdownOpen = false;
        renderMiniApp();
      });
    });
    scope.querySelectorAll("[data-action='selected-checkin']").forEach((el) => {
      el.addEventListener("click", () => runUserAction(() => checkIn(el.getAttribute("data-train-id"), el.getAttribute("data-station-id"))));
    });
    scope.querySelectorAll("[data-action='selected-checkin-map']").forEach((el) => {
      el.addEventListener("click", () => runUserAction(() => openMap(el.getAttribute("data-train-id")), t("app_map_loaded")));
    });
    scope.querySelectorAll("[data-action='checkin']").forEach((el) => {
      el.addEventListener("click", () => runUserAction(() => checkIn(el.getAttribute("data-train-id"), el.getAttribute("data-station-id"))));
    });
    scope.querySelectorAll("[data-action='mute-train']").forEach((el) => {
      el.addEventListener("click", () => runUserAction(() => muteTrain(el.getAttribute("data-train-id"))));
    });
    scope.querySelectorAll("[data-action='station-departures']").forEach((el) => {
      el.addEventListener("click", () => runUserAction(async () => {
        await fetchStationDepartures(el.getAttribute("data-station-id"));
      }, t("app_search_complete")));
    });
    scope.querySelectorAll("[data-action='select-sighting-train']").forEach((el) => {
      el.addEventListener("click", () => {
        state.selectedSightingTrainId = el.getAttribute("data-train-id") || "";
        state.expandedStationContextTrainId = state.selectedSightingTrainId;
        renderMiniApp();
      });
    });
    scope.querySelectorAll("[data-action='toggle-station-context']").forEach((el) => {
      el.addEventListener("click", () => {
        const trainId = el.getAttribute("data-train-id") || "";
        state.expandedStationContextTrainId = state.expandedStationContextTrainId === trainId ? "" : trainId;
        renderMiniApp();
      });
    });
    scope.querySelectorAll("[data-action='open-sightings-train']").forEach((el) => {
      el.addEventListener("click", () => {
        state.stationSightingDestinationId = "";
        state.selectedSightingTrainId = el.getAttribute("data-train-id") || "";
        state.expandedStationContextTrainId = state.selectedSightingTrainId;
        setMiniAppTab("sightings", "open-sightings-train");
        renderMiniApp();
      });
    });
    scope.querySelectorAll("[data-action='toggle-stop-context']").forEach((el) => {
      el.addEventListener("click", () => {
        const key = el.getAttribute("data-stop-key") || "";
        state.expandedStopContextKey = state.expandedStopContextKey === key ? "" : key;
        renderMiniApp();
      });
    });
    scope.querySelectorAll("[data-action='open-sightings-stop']").forEach((el) => {
      el.addEventListener("click", () => {
        const stationId = el.getAttribute("data-station-id");
        const trainId = el.getAttribute("data-train-id") || "";
        if (!stationId) {
          return;
        }
        runUserAction(async () => {
          await fetchStationDepartures(stationId);
          state.stationSightingDestinationId = "";
          state.selectedSightingTrainId = trainId;
          state.expandedStationContextTrainId = trainId;
          setMiniAppTab("sightings", "open-sightings-stop");
          renderMiniApp();
        });
      });
    });
    scope.querySelectorAll("[data-action='tab-sightings']").forEach((el) => {
      el.addEventListener("click", () => {
        if (!state.selectedStation) {
          return;
        }
        setMiniAppTab("sightings", "tab-sightings");
        renderMiniApp();
      });
    });
    const stationSightingDestination = scope.querySelector("#station-sighting-destination");
    if (stationSightingDestination) {
      stationSightingDestination.addEventListener("change", (event) => {
        state.stationSightingDestinationId = event.target.value;
        state.selectedSightingTrainId = "";
        state.expandedStationContextTrainId = "";
        renderMiniApp();
      });
    }
    const stationSightingSubmit = scope.querySelector("#station-sighting-submit");
    if (stationSightingSubmit) {
      stationSightingSubmit.addEventListener("click", () => {
        if (!state.selectedSightingTrainId) {
          showToast(t("app_station_sighting_select_departure_toast"), "info");
          return;
        }
        runUserAction(() => submitStationSighting(), (result) => result);
      });
    }
    scope.querySelectorAll("[data-action='choose-origin']").forEach((el) => {
      el.addEventListener("click", () => {
        state.chosenOrigin = { id: el.getAttribute("data-station-id"), name: el.getAttribute("data-station-name") };
        state.chosenDestination = null;
        state.destinationResults = [];
        state.routeResults = [];
        renderMiniApp();
      });
    });
    scope.querySelectorAll("[data-action='choose-destination']").forEach((el) => {
      el.addEventListener("click", () => {
        state.chosenDestination = { id: el.getAttribute("data-station-id"), name: el.getAttribute("data-station-name") };
        renderMiniApp();
      });
    });
    scope.querySelectorAll("[data-action='favorite-open']").forEach((el) => {
      el.addEventListener("click", () => {
        state.chosenOrigin = { id: el.getAttribute("data-from-station-id"), name: el.getAttribute("data-from-station-name") };
        state.chosenDestination = { id: el.getAttribute("data-to-station-id"), name: el.getAttribute("data-to-station-name") };
        runUserAction(async () => {
          await fetchRouteResults();
        }, t("app_route_loaded"));
      });
    });
    scope.querySelectorAll("[data-action='save-favorite']").forEach((el) => {
      el.addEventListener("click", () => runUserAction(() => saveFavorite(el.getAttribute("data-from-station-id"), el.getAttribute("data-to-station-id"))));
    });
    scope.querySelectorAll("[data-action='remove-favorite']").forEach((el) => {
      el.addEventListener("click", () => runUserAction(() => removeFavorite(el.getAttribute("data-from-station-id"), el.getAttribute("data-to-station-id"))));
    });
    scope.querySelectorAll("[data-action='report']").forEach((el) => {
      el.addEventListener("click", () => runUserAction(() => submitReport(el.getAttribute("data-signal")), (result) => result));
    });
    scope.querySelectorAll("[data-action='tab-report']").forEach((el) => {
      el.addEventListener("click", () => {
        setMiniAppTab("report", "tab-report");
        renderMiniApp();
      });
    });
    scope.querySelectorAll("[data-action='checkout']").forEach((el) => {
      el.addEventListener("click", () => runUserAction(() => checkoutRide()));
    });
    scope.querySelectorAll("[data-action='undo-checkout']").forEach((el) => {
      el.addEventListener("click", () => runUserAction(() => undoCheckout()));
    });
    const stationSearch = scope.querySelector("#station-search");
    const stationQueryInput = scope.querySelector("#station-query");
    if (stationQueryInput) {
      stationQueryInput.addEventListener("input", (event) => {
        state.stationQuery = event.target.value;
      });
    }
    const stationSearchAction = () => runUserAction(async () => {
      await fetchStationMatches(state.stationQuery);
    }, t("app_search_complete"));
    if (stationSearch) {
      stationSearch.addEventListener("click", stationSearchAction);
    }
    bindEnterAction("station-query", stationSearchAction, scope);
    const originSearch = scope.querySelector("#origin-search");
    const originQueryInput = scope.querySelector("#origin-query");
    if (originQueryInput) {
      originQueryInput.addEventListener("input", (event) => {
        state.originQuery = event.target.value;
      });
    }
    const originSearchAction = () => runUserAction(async () => {
      await fetchOriginMatches(state.originQuery);
    }, t("app_search_complete"));
    if (originSearch) {
      originSearch.addEventListener("click", originSearchAction);
    }
    bindEnterAction("origin-query", originSearchAction, scope);
    const destinationSearch = scope.querySelector("#destination-search");
    const destinationQueryInput = scope.querySelector("#destination-query");
    if (destinationQueryInput) {
      destinationQueryInput.addEventListener("input", (event) => {
        state.destinationQuery = event.target.value;
      });
    }
    const destinationSearchAction = () => runUserAction(async () => {
      await fetchDestinationMatches(state.destinationQuery);
    }, t("app_search_complete"));
    if (destinationSearch) {
      destinationSearch.addEventListener("click", destinationSearchAction);
    }
    bindEnterAction("destination-query", destinationSearchAction, scope);
    const routeSearch = scope.querySelector("#route-search");
    if (routeSearch) {
      routeSearch.addEventListener("click", () => runUserAction(() => fetchRouteResults(), t("app_route_loaded")));
    }
    const saveSettingsButton = scope.querySelector("#save-settings");
    if (saveSettingsButton) {
      saveSettingsButton.addEventListener("click", () => runUserAction(() => saveSettings()));
    }
    if (state.checkInDropdownOpen) {
      const selectedOption = scope.querySelector("#selected-checkin-option");
      if (selectedOption && typeof selectedOption.scrollIntoView === "function") {
        window.requestAnimationFrame(() => {
          selectedOption.scrollIntoView({ block: "center", inline: "nearest" });
        });
      }
    }
  }

  function setStatusFromError(err) {
    rememberErrorStatus(err);
    rerenderCurrent({ preserveInputFocus: true });
  }

  function normalizeLang(raw) {
    return String(raw || "EN").trim().toUpperCase() === "LV" ? "LV" : "EN";
  }

  function t(key) {
    const args = Array.prototype.slice.call(arguments, 1);
    let value = state.messages[key] || fallbackMessages[key] || key;
    args.forEach((arg) => {
      value = value.replace(/%[sd]/, String(arg));
    });
    return value;
  }

  function statusSummary(status) {
    if (!status) return t("status_no_reports");
    if (status.state === "MIXED_REPORTS") return t("status_mixed");
    if (status.state === "NO_REPORTS" || !status.lastReportAt) return t("status_no_reports");
    return t("status_last", relativeAgo(status.lastReportAt));
  }

  function signalLabel(signal) {
    if (signal === "INSPECTION_STARTED") return t("event_inspection_started");
    if (signal === "INSPECTION_IN_MY_CAR") return t("event_inspection_in_car");
    if (signal === "INSPECTION_ENDED") return t("event_inspection_ended");
    return signal || t("event_unknown");
  }

  function latestStationSighting(items) {
    return (Array.isArray(items) ? items : []).reduce((latest, item) => {
      if (!latest) {
        return item;
      }
      return new Date(item.createdAt).getTime() > new Date(latest.createdAt).getTime() ? item : latest;
    }, null);
  }

  function sightingBucketColor(bucket) {
    if (bucket === "fresh") return "#d94d1f";
    if (bucket === "warm") return "#0f6b62";
    return "#6c5f52";
  }

  function stopHasSighting(stop, mapData) {
    return stopSightings(stop, mapData).length > 0;
  }

  function stopSightingColor(stop, mapData) {
    const latest = latestStationSighting(stopSightings(stop, mapData));
    if (!latest) {
      return "#9f3d22";
    }
    return sightingBucketColor(sightingRecencyBucket(latest.createdAt));
  }

  function stopContextKey(stop, index) {
    const base = stop && (stop.stationId || stop.stationName || "");
    if (typeof index === "number" && Number.isFinite(index)) {
      return `${base}#${index}`;
    }
    return base;
  }

  function compactMarkerCount(count) {
    const numeric = Number(count) || 0;
    if (numeric <= 0) {
      return "";
    }
    return numeric > 9 ? "9+" : String(numeric);
  }

  function buildPopupCard(options) {
    const sections = Array.isArray(options && options.sections)
      ? options.sections.filter(Boolean).join("")
      : "";
    return `
      <div class="map-popup-card">
        <div class="map-popup-heading">
          <strong>${escapeHtml(options && options.title ? options.title : "")}</strong>
          ${options && options.subtitle ? `<span class="map-popup-subtitle">${escapeHtml(options.subtitle)}</span>` : ""}
        </div>
        ${sections ? `<div class="map-popup-sections">${sections}</div>` : ""}
      </div>
    `;
  }

  function popupInfoRow(label, value) {
    if (!value) {
      return "";
    }
    return `
      <div class="map-popup-row">
        <span class="map-popup-label">${escapeHtml(label)}</span>
        <span class="map-popup-value">${escapeHtml(value)}</span>
      </div>
    `;
  }

  function popupListSection(label, items) {
    const values = (Array.isArray(items) ? items : []).filter(Boolean);
    if (!values.length) {
      return "";
    }
    return `
      <div class="map-popup-row">
        <span class="map-popup-label">${escapeHtml(label)}</span>
        <ul class="map-popup-list">
          ${values.map((item) => `<li>${escapeHtml(item)}</li>`).join("")}
        </ul>
      </div>
    `;
  }

  function stopSightings(stop, mapData) {
    if (!mapData || !Array.isArray(mapData.stationSightings) || !stop) {
      return [];
    }
    return mapData.stationSightings.filter((item) => item.stationId === stop.stationId || item.stationName === stop.stationName);
  }

  function stopTimeLabel(stop, index) {
    const parts = [];
    if (stop.departureAt) {
      parts.push(formatClock(stop.departureAt));
    } else if (stop.arrivalAt) {
      parts.push(formatClock(stop.arrivalAt));
    }
    if (!parts.length) {
      parts.push(`#${index + 1}`);
    }
    return parts.join(" • ");
  }

  function renderStopDetailPills(stop, index) {
    const pills = [`<span>${escapeHtml(`#${index + 1}`)}</span>`];
    if (stop.arrivalAt && stop.departureAt) {
      pills.push(`<span>${escapeHtml(`↓ ${formatClock(stop.arrivalAt)}`)}</span>`);
      pills.push(`<span>${escapeHtml(`↑ ${formatClock(stop.departureAt)}`)}</span>`);
    }
    return pills.join("");
  }

  function relativeAgo(raw) {
    const date = new Date(raw);
    const deltaMin = Math.max(0, Math.round((Date.now() - date.getTime()) / 60000));
    if (deltaMin <= 0) return t("app_relative_now");
    if (deltaMin === 1) return t("app_relative_one_min");
    return t("app_relative_many_mins", deltaMin);
  }

  function formatDateTime(raw) {
    const date = new Date(raw);
    return `${date.toLocaleDateString()} ${date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}`;
  }

  function formatClock(raw) {
    const date = typeof raw === "string" ? new Date(raw) : raw;
    if (!(date instanceof Date) || Number.isNaN(date.getTime())) return "";
    return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;");
  }

  function escapeAttr(value) {
    return escapeHtml(value).replaceAll("'", "&#39;");
  }

  function roundMapCoord(value) {
    return Math.round(Number(value) * 1000000) / 1000000;
  }

  if (typeof module === "object" && module.exports) {
    module.exports = {
      __test__: {
        reportsChannelURL,
        markerMovementTimestampMs,
        markerMovementDurationMs,
        resetState(overrides) {
          resetStateForTest(overrides);
        },
        getState() {
          return JSON.parse(JSON.stringify(state));
        },
        setPinnedDetailTrain,
        clearPinnedDetailTrain,
        alignMiniMapToSelectedTrain,
        pauseMiniMapFollow,
        applyCurrentRidePayload,
        preferredMapTrainId,
        resolveMiniMapFollowTarget,
        setMiniAppTab,
        renderSettingsTab(settings, messages) {
          state.messages = Object.assign({}, fallbackMessages, messages || {});
          return renderSettingsTab(settings || {});
        },
      },
    };
  }
})();
