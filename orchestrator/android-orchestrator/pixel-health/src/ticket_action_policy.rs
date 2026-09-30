//! Admitted visual actions and retained outcomes. No navigation, capture, journals or phone input.
use serde_json::{Value, json};
fn text<'a>(v: &'a Value, k: &str) -> &'a str {
    v[k].as_str().unwrap_or("")
}
fn n(v: &Value, k: &str) -> i64 {
    v[k].as_i64().unwrap_or(0)
}
fn blank(v: &str) -> bool {
    crate::trim(v).is_empty()
}
fn activates(target: &str) -> bool {
    matches!(
        target,
        "OPEN_LATEST_AND_REGISTER"
            | "REGISTER_CURRENT"
            | "open_latest_and_register"
            | "register_current"
    )
}
fn target(v: &str) -> Option<&'static str> {
    match crate::trim(v).to_lowercase().as_str() {
        "open_latest_unactivated" => Some("OPEN_LATEST_UNACTIVATED"),
        "open_latest_and_register" => Some("OPEN_LATEST_AND_REGISTER"),
        "register_current" => Some("REGISTER_CURRENT"),
        "show_recent_activated" => Some("SHOW_RECENT_ACTIVATED"),
        "return_to_latest_unactivated" => Some("RETURN_TO_LATEST_UNACTIVATED"),
        "redetect_latest" => Some("REDETECT_LATEST"),
        "refresh_current_ticket" => Some("REFRESH_CURRENT_TICKET"),
        _ => None,
    }
}
fn view(v: &str) -> &str {
    match v {
        "latest_unactivated" => "LATEST_UNACTIVATED",
        "recent_activated" => "RECENT_ACTIVATED",
        "activated_current" => "ACTIVATED_CURRENT",
        _ => "UNKNOWN",
    }
}
fn state(v: &str) -> &str {
    match v {
        "unactivated_detail" => "UNACTIVATED_DETAIL",
        "activated_detail" => "ACTIVATED_DETAIL",
        "ticket_list" => "TICKET_LIST",
        "tickets_single_use_empty" => "TICKETS_SINGLE_USE_EMPTY",
        "tickets_time_empty" => "TICKETS_TIME_EMPTY",
        "vivi_home" => "VIVI_HOME",
        "vivi_profile" => "VIVI_PROFILE",
        "vivi_other_tab" => "VIVI_OTHER_TAB",
        "login_required" => "LOGIN_REQUIRED",
        "blocked" => "BLOCKED",
        _ => "UNKNOWN",
    }
}
fn terminal_view(target: &str, view: &str) -> bool {
    match target {
        "OPEN_LATEST_UNACTIVATED" | "RETURN_TO_LATEST_UNACTIVATED" | "REDETECT_LATEST" => {
            view == "LATEST_UNACTIVATED"
        }
        "OPEN_LATEST_AND_REGISTER" | "REGISTER_CURRENT" => view == "ACTIVATED_CURRENT",
        "SHOW_RECENT_ACTIVATED" => view == "RECENT_ACTIVATED",
        "REFRESH_CURRENT_TICKET" => matches!(view, "LATEST_UNACTIVATED" | "ACTIVATED_CURRENT"),
        _ => false,
    }
}
fn retained(j: &Value) -> bool {
    !blank(text(j, "actionId"))
        && text(j, "phase") == "terminal"
        && !blank(text(j, "terminalStatus"))
}
fn uncertain(j: &Value) -> bool {
    !blank(text(j, "actionId")) && text(j, "phase") == "navigation_dispatched"
}
fn bound_negative(j: &Value) -> bool {
    retained(j)
        && text(j, "target") == "redetect_latest"
        && text(j, "terminalStatus") == "failed"
        && text(j, "terminalReason") == "ticket_action_latest_not_detected"
        && text(j, "terminalView") == "unknown"
        && j["terminalOk"] != true
        && (j["semanticProof"] == true || (n(j, "streamEpoch") > 0 && n(j, "frameSequence") > 0))
        && [
            "sliderLeftBasisPoints",
            "sliderTopBasisPoints",
            "sliderRightBasisPoints",
            "sliderBottomBasisPoints",
        ]
        .iter()
        .all(|k| n(j, k) == -1)
}
fn geometry(j: &Value) -> Value {
    if j["terminalOk"] != true
        || text(j, "terminalView") != "latest_unactivated"
        || !matches!(
            text(j, "target"),
            "open_latest_unactivated" | "return_to_latest_unactivated" | "redetect_latest"
        )
        || n(j, "streamEpoch") <= 0
        || n(j, "frameSequence") <= 0
        || [
            "sliderLeftBasisPoints",
            "sliderTopBasisPoints",
            "sliderRightBasisPoints",
            "sliderBottomBasisPoints",
        ]
        .iter()
        .any(|k| !(0..=10_000).contains(&n(j, k)))
        || n(j, "sliderLeftBasisPoints") >= n(j, "sliderRightBasisPoints")
        || n(j, "sliderTopBasisPoints") >= n(j, "sliderBottomBasisPoints")
    {
        return Value::Null;
    }
    json!({"leftBasisPoints":j["sliderLeftBasisPoints"],"topBasisPoints":j["sliderTopBasisPoints"],"rightBasisPoints":j["sliderRightBasisPoints"],"bottomBasisPoints":j["sliderBottomBasisPoints"]})
}
fn terminal(j: &Value, r: &Value, epoch: i64, sequence: i64) -> Value {
    if !retained(j)
        || j["actionId"] != r["actionId"]
        || target(text(j, "target")) != Some(text(r, "target"))
    {
        return Value::Null;
    }
    let negative = text(j, "target") == "redetect_latest"
        && text(j, "terminalReason") == "ticket_action_latest_not_detected";
    let negative_valid = !negative || bound_negative(j);
    let watermark = !(j["terminalOk"] == true || negative)
        || j["semanticProof"] == true
        || (n(j, "streamEpoch") > 0 && n(j, "frameSequence") > 0);
    let v = view(text(j, "terminalView"));
    let view_valid = j["terminalOk"] != true || terminal_view(text(r, "target"), v);
    let valid = watermark && view_valid && negative_valid;
    let status = if !valid || activates(text(r, "target")) && j["terminalOk"] != true {
        "needs_attention"
    } else {
        text(j, "terminalStatus")
    };
    let phase = if valid && !blank(text(j, "terminalPhase")) {
        text(j, "terminalPhase")
    } else if valid && activates(text(r, "target")) {
        if j["terminalOk"] == true {
            "activation_proven"
        } else {
            "outcome_unknown"
        }
    } else if valid && j["terminalOk"] == true {
        "complete"
    } else if valid {
        text(j, "terminalStatus")
    } else {
        "needs_attention"
    };
    json!({"actionId":j["actionId"],"target":j["target"],"status":status,"phase":phase,"currentView":v,
 "streamEpoch":if negative && !negative_valid {0} else if n(j,"streamEpoch")>0 {n(j,"streamEpoch")} else {epoch},
 "frameSequence":if negative && !negative_valid {0} else if n(j,"frameSequence")>0 {n(j,"frameSequence")} else {sequence},
 "reason":if !negative_valid || !watermark {"ticket_action_frame_watermark_unproved"} else if !view_valid {"ticket_action_terminal_view_unproved"} else {text(j,"terminalReason")},
 "completedAt":j["completedAt"],"interactionRevision":j["interactionRevision"],"activationRevision":j["activationRevision"],"activationAttemptId":j["activationAttemptId"],
 "semanticProof":j["semanticProof"],"terminal":true,"ok":j["terminalOk"]==true && valid})
}
fn finalize(j: &Value) -> Value {
    if !retained(j)
        || ["commandId", "commandRevision", "completedAt"]
            .iter()
            .any(|k| blank(text(j, k)))
    {
        return Value::Null;
    }
    let active = activates(text(j, "target"));
    let ok = j["terminalOk"] == true;
    let phase = if !blank(text(j, "terminalPhase")) {
        text(j, "terminalPhase")
    } else if active && ok {
        "activation_proven"
    } else if active {
        "outcome_unknown"
    } else if ok {
        "complete"
    } else {
        text(j, "terminalStatus")
    };
    json!({"commandId":j["commandId"],"commandRevision":j["commandRevision"],"flow":j["flow"],"refreshActivationAttemptId":j["refreshActivationAttemptId"],"refreshActivationRevision":j["refreshActivationRevision"],
 "action":{"actionId":j["actionId"],"target":j["target"],"status":if active && !ok {"needs_attention"} else {text(j,"terminalStatus")},"phase":phase,"currentView":view(text(j,"terminalView")),
 "streamEpoch":j["streamEpoch"],"frameSequence":j["frameSequence"],"reason":j["terminalReason"],"completedAt":j["completedAt"],"interactionRevision":j["interactionRevision"],
 "activationRevision":if ok {text(j,"activationRevision")} else {""},"activationAttemptId":j["activationAttemptId"],"semanticProof":j["semanticProof"],"terminal":true,"ok":ok},"retainedGeometry":geometry(j)})
}
fn reconciled(j: &Value, r: &Value, o: &Value) -> bool {
    if !uncertain(j)
        || j["actionId"] != r["actionId"]
        || target(text(j, "target")) != Some(text(r, "target"))
    {
        return false;
    }
    let redetect = text(r, "target") == "REDETECT_LATEST";
    let current = text(o, "state");
    let from = text(j, "navigationFromState");
    if redetect && text(j, "navigationToState") == "ticket_list_to_single_use" {
        return matches!(current, "TICKET_LIST" | "TICKETS_SINGLE_USE_EMPTY")
            && !o["timeTicketsTabBounds"].is_null()
            && o["ticketsTabBounds"].is_null();
    }
    if redetect && text(j, "navigationToState") == "ticket_list_to_time" {
        return matches!(current, "TICKET_LIST" | "TICKETS_TIME_EMPTY")
            && !o["ticketsTabBounds"].is_null()
            && o["timeTicketsTabBounds"].is_null();
    }
    let to = state(text(j, "navigationToState"));
    if to != "UNKNOWN" {
        if to == "TICKET_LIST"
            && ((from == "vivi_home" && current == "TICKETS_SINGLE_USE_EMPTY")
                || (redetect
                    && ((matches!(from, "vivi_home" | "tickets_single_use_empty")
                        && current == "TICKETS_TIME_EMPTY")
                        || (from == "tickets_time_empty"
                            && current == "TICKETS_SINGLE_USE_EMPTY"))))
        {
            return true;
        }
        if current != to {
            return false;
        }
        if to == "TICKET_LIST"
            && redetect
            && ((from == "tickets_single_use_empty" && o["ticketsTabBounds"].is_null())
                || (from == "tickets_time_empty" && o["timeTicketsTabBounds"].is_null()))
        {
            return false;
        }
        return match to {
            "TICKET_LIST" => true,
            "UNACTIVATED_DETAIL" | "ACTIVATED_DETAIL" => {
                !blank(text(j, "navigationAnchor")) && !blank(text(o, "currentAnchor"))
            }
            _ => false,
        };
    }
    if blank(text(j, "intendedAnchor")) {
        return false;
    }
    let expected = match text(r, "target") {
        "SHOW_RECENT_ACTIVATED" => "ACTIVATED_DETAIL",
        "OPEN_LATEST_UNACTIVATED" | "OPEN_LATEST_AND_REGISTER" | "RETURN_TO_LATEST_UNACTIVATED" => {
            "UNACTIVATED_DETAIL"
        }
        _ => return false,
    };
    current == expected && o["currentAnchor"] == j["intendedAnchor"]
}
fn primitive(v: &Value) -> Result<String, String> {
    if v.is_null() || v.as_str() == Some("L:null") {
        return Ok(String::new());
    }
    v.as_str()
        .and_then(|s| s.strip_prefix("S:").or_else(|| s.strip_prefix("L:")))
        .map(str::to_owned)
        .ok_or_else(|| "action field is not a primitive".into())
}
fn parse(payload: &str) -> Result<Value, String> {
    let p = crate::ticket_command::parse(payload).map_err(|_| "invalid action input")?;
    if !p["version"].is_null() && !p["version"].is_string() {
        return Err("action version is not a primitive".into());
    }
    if crate::ticket_command::integer_long(&p["version"]) != Some(3) {
        return Ok(Value::Null);
    }
    let string = |k: &str| primitive(&p[k]);
    let action = crate::trim(&string("actionId")?).to_owned();
    let Some(t) = target(&string("target")?) else {
        return Ok(Value::Null);
    };
    let attempt = crate::trim(&string("attemptId")?).to_owned();
    let revision = crate::trim(&string("expectedInteractionRevision")?).to_owned();
    let policy = crate::trim(&string("policyRevision")?).to_owned();
    let expiry = crate::trim(&string("switchExpiresAt")?).to_owned();
    let flow = crate::trim(&string("flow")?).to_owned();
    let activation_attempt = crate::trim(&string("activationAttemptId")?).to_owned();
    let activation_revision = crate::trim(&string("activationRevision")?).to_owned();
    if action.is_empty()
        || action.encode_utf16().count() > 128
        || (t == "REFRESH_CURRENT_TICKET"
            && (flow != "idle_ticket_refresh"
                || string("source")? != "ticket_remote_idle_refresh"
                || !attempt.is_empty()
                || !revision.is_empty()))
        || (activates(t) && attempt != action)
        || (t == "REGISTER_CURRENT" && revision.is_empty())
        || (matches!(t, "SHOW_RECENT_ACTIVATED" | "RETURN_TO_LATEST_UNACTIVATED")
            && (policy.is_empty() || expiry.is_empty()))
        || policy.encode_utf16().count() > 128
        || expiry.encode_utf16().count() > 64
        || (!expiry.is_empty() && crate::ticket_command::instant(&expiry).is_none())
        || flow.encode_utf16().count() > 64
        || activation_attempt.encode_utf16().count() > 128
        || activation_revision.encode_utf16().count() > 128
        || (flow == "activation_expiry_reset"
            && (t != "OPEN_LATEST_UNACTIVATED"
                || activation_attempt.is_empty()
                || activation_revision.is_empty()))
    {
        return Ok(Value::Null);
    }
    Ok(
        json!({"actionId":action,"target":t,"source":string("source")?,"reason":string("reason")?,"attemptId":attempt,"expectedInteractionRevision":revision,"scheduleId":string("scheduleId")?,
 "policyRevision":policy,"switchExpiresAt":expiry,"flow":flow,"refreshActivationAttemptId":activation_attempt,"refreshActivationRevision":activation_revision}),
    )
}
fn select_card(o: &Value, target: &str, anchors: &Value) -> Value {
    let anchor = match target {
        "SHOW_RECENT_ACTIVATED" => text(anchors, "recentActivatedAnchor"),
        "RETURN_TO_LATEST_UNACTIVATED" => text(anchors, "latestUnactivatedAnchor"),
        _ => "",
    };
    if blank(anchor) {
        return Value::Null;
    }
    unique_card(o, |c| text(c, "anchor") == anchor)
}
fn unique_card(o: &Value, predicate: impl Fn(&Value) -> bool) -> Value {
    let Some(cards) = o["cards"].as_array() else {
        return Value::Null;
    };
    let mut matches = cards.iter().filter(|c| predicate(c));
    let first = matches.next();
    if matches.next().is_some() {
        Value::Null
    } else {
        first.cloned().unwrap_or(Value::Null)
    }
}

pub(crate) fn decide(operation: &str, payload: &str) -> Result<Value, String> {
    if operation == "parse" {
        return parse(payload);
    }
    let a: Value = serde_json::from_str(payload).map_err(|_| "invalid action policy input")?;
    let o = &a["observation"];
    let j = &a["journal"];
    let r = &a["request"];
    let t = text(&a, "target");
    Ok(match operation {
        "target" => json!(target(text(&a, "value"))),
        "uncertain" => json!(uncertain(j)),
        "retained" => json!(retained(j)),
        "terminal_view" => json!(terminal_view(t, text(&a, "view"))),
        "terminal" => terminal(j, r, n(&a, "epoch"), n(&a, "sequence")),
        "negative_journal" => json!(bound_negative(j)),
        "finalize" => finalize(j),
        "reconciled" => json!(reconciled(j, r, o)),
        "recovery" => json!(
            a["consumed"] != true
                && (a["completed"] != true
                    || n(&a, "currentRestart") > n(&a, "baselineRestart")
                    || n(&a, "baselineEpoch") > 0
                        && n(&a, "currentEpoch") > 0
                        && a["baselineEpoch"] != a["currentEpoch"])
        ),
        "navigation_bounds" => {
            if matches!(
                t,
                "OPEN_LATEST_UNACTIVATED"
                    | "OPEN_LATEST_AND_REGISTER"
                    | "RETURN_TO_LATEST_UNACTIVATED"
                    | "REDETECT_LATEST"
            ) {
                a["card"]["registrationBounds"].clone()
            } else if t == "SHOW_RECENT_ACTIVATED" {
                a["card"]["activatedDetailBounds"].clone()
            } else {
                Value::Null
            }
        }
        "time_bounds" => {
            if text(o, "state") == "TICKETS_SINGLE_USE_EMPTY"
                && matches!(
                    t,
                    "OPEN_LATEST_UNACTIVATED"
                        | "OPEN_LATEST_AND_REGISTER"
                        | "SHOW_RECENT_ACTIVATED"
                        | "RETURN_TO_LATEST_UNACTIVATED"
                        | "REDETECT_LATEST"
                )
            {
                o["timeTicketsTabBounds"].clone()
            } else {
                Value::Null
            }
        }
        "single_bounds" => {
            if text(o, "state") == "TICKETS_TIME_EMPTY" && t == "REDETECT_LATEST" {
                o["ticketsTabBounds"].clone()
            } else {
                Value::Null
            }
        }
        "card" => select_card(o, t, &a["anchors"]),
        "latest_card" => unique_card(o, |c| {
            c["latest"] == true && !c["registrationBounds"].is_null()
        }),
        "activated_card" => unique_card(o, |c| !c["activatedDetailBounds"].is_null()),
        "recent_card" => {
            let card = select_card(o, "SHOW_RECENT_ACTIVATED", &a["anchors"]);
            if !card.is_null() && !card["activatedDetailBounds"].is_null() {
                card
            } else if text(&a["anchors"], "recentActivatedAnchor").starts_with("d_") {
                unique_card(o, |c| !c["activatedDetailBounds"].is_null())
            } else {
                Value::Null
            }
        }
        "redetect_tab" => {
            if text(o, "state") != "TICKET_LIST"
                || o["cards"]
                    .as_array()
                    .is_some_and(|cards| cards.iter().any(|c| !c["registrationBounds"].is_null()))
            {
                Value::Null
            } else if !o["ticketsTabBounds"].is_null()
                && o["timeTicketsTabBounds"].is_null()
                && a["returned"] != true
            {
                json!({"bounds":o["ticketsTabBounds"],"transition":"ticket_list_to_single_use"})
            } else if !o["timeTicketsTabBounds"].is_null() && o["ticketsTabBounds"].is_null() {
                json!({"bounds":o["timeTicketsTabBounds"],"transition":"ticket_list_to_time"})
            } else {
                Value::Null
            }
        }
        "agree" => json!(crate::ticket_control::observations_agree(
            &a["first"],
            &a["second"],
            n(&a, "tolerance") as i32
        )),
        "consensus" => {
            let prior = &a["prior"];
            let ignored = text(o, "state") == "UNKNOWN" && a["allowUnknown"] != true;
            let agrees = !ignored
                && !prior.is_null()
                && prior["probeId"] != o["probeId"]
                && crate::ticket_control::observations_agree(prior, o, 3);
            json!({"prior":if ignored || agrees {prior} else {o},"answer":if agrees {o} else {&Value::Null}})
        }
        "dispatch_fresh" => {
            let captured = n(o, "captureStartUs") / 1_000;
            let now = n(&a, "now");
            json!(
                n(o, "probeId") > 0
                    && n(o, "atMillis") > 0
                    && n(o, "captureStartUs") > 0
                    && captured >= n(o, "atMillis")
                    && now >= captured
                    && now.wrapping_sub(captured) <= n(&a, "maxAge").max(0)
            )
        }
        "result_view" => json!(if text(&a, "state") == "UNACTIVATED_DETAIL" {
            "LATEST_UNACTIVATED"
        } else if t == "SHOW_RECENT_ACTIVATED" && text(&a, "state") == "ACTIVATED_DETAIL" {
            "RECENT_ACTIVATED"
        } else if text(&a, "state") == "ACTIVATED_DETAIL" {
            "ACTIVATED_CURRENT"
        } else {
            "UNKNOWN"
        }),
        "negative_observation" => json!(
            t == "REDETECT_LATEST"
                && text(&a, "from") == "TICKETS_SINGLE_USE_EMPTY"
                && text(o, "state") == "TICKETS_TIME_EMPTY"
                && blank(text(o, "currentAnchor"))
                && [
                    "sliderBounds",
                    "controlCodeBounds",
                    "backBounds",
                    "timeTicketsTabBounds"
                ]
                .iter()
                .all(|k| o[k].is_null())
                && !o["ticketsTabBounds"].is_null()
                && o["cards"].as_array().is_some_and(Vec::is_empty)
        ),
        "negative_terminal" => {
            let s = &a["snapshot"];
            json!(
                s["ok"] != true
                    && s["terminal"] == true
                    && text(s, "target") == "redetect_latest"
                    && text(s, "status") == "failed"
                    && text(s, "phase") == "failed"
                    && text(s, "currentView") == "UNKNOWN"
                    && text(s, "reason") == "ticket_action_latest_not_detected"
                    && (s["semanticProof"] == true
                        || n(s, "streamEpoch") > 0 && n(s, "frameSequence") > 0)
            )
        }
        "proof_matches" => json!(
            !blank(text(&a["proof"], "ticketAnchor"))
                && !blank(text(&a["proof"], "detailAnchor"))
                && !blank(text(&a, "anchor"))
                && a["proof"]["detailAnchor"] == a["anchor"]
        ),
        "proof_revision" => {
            let revision = text(&a, "expected");
            let prior = text(&a, "proof");
            if !blank(revision)
                && (prior == revision
                    || !revision.starts_with("schedule:")
                        && prior == format!("schedule:{revision}"))
            {
                json!(revision)
            } else {
                Value::Null
            }
        }
        "proven_anchor" => {
            let anchor = text(&a["proof"], "ticketAnchor");
            json!(if blank(anchor) {
                text(o, "currentAnchor")
            } else {
                anchor
            })
        }
        "proof_gate" => {
            let p = &a["proof"];
            let expected = text(r, "expectedInteractionRevision");
            let revision = text(p, "interactionRevision");
            let generic = text(r, "target") != "REGISTER_CURRENT"
                || p.is_null()
                || blank(expected)
                || !(revision == expected
                    || !expected.starts_with("schedule:")
                        && revision == format!("schedule:{expected}"))
                || text(o, "state") != "UNACTIVATED_DETAIL"
                || o["sliderBounds"].is_null()
                || text(p, "status") != "unactivated_ready"
                || blank(text(p, "ticketAnchor"))
                || !(revision.starts_with("pc-")
                    || n(p, "streamEpoch") > 0 && n(p, "frameSequence") > 0);
            let identity = !blank(text(p, "ticketAnchor"))
                && !blank(text(p, "detailAnchor"))
                && !blank(text(o, "currentAnchor"))
                && p["detailAnchor"] == o["currentAnchor"];
            if generic {
                json!({"proof":null,"failureReason":"ticket_action_interaction_revision_unproved"})
            } else if !identity {
                json!({"proof":null,"failureReason":"ticket_action_detail_identity_conflict"})
            } else {
                let mut p = p.clone();
                p["interactionRevision"] = json!(expected);
                json!({"proof":p,"failureReason":null})
            }
        }
        "after_card" => {
            let mut o = o.clone();
            if !blank(text(&a, "anchor"))
                && matches!(text(&o, "state"), "UNACTIVATED_DETAIL" | "ACTIVATED_DETAIL")
            {
                o["currentAnchor"] = a["anchor"].clone();
            }
            o
        }
        "after_activation" => {
            let mut o = o.clone();
            if text(&o, "state") == "ACTIVATED_DETAIL" && !blank(text(&a, "anchor")) {
                o["currentAnchor"] = json!(if blank(text(&o, "detailCardAnchor")) {
                    text(&a, "anchor")
                } else {
                    text(&o, "detailCardAnchor")
                });
            }
            o
        }
        "after_recent" => {
            let mut o = o.clone();
            if !text(&a, "recent").starts_with("d_")
                && !blank(text(&a, "anchor"))
                && matches!(text(&o, "state"), "UNACTIVATED_DETAIL" | "ACTIVATED_DETAIL")
            {
                o["currentAnchor"] = a["anchor"].clone();
            }
            o
        }
        "intended_anchor" => json!(if blank(text(j, "intendedAnchor")) {
            text(o, "currentAnchor")
        } else {
            text(j, "intendedAnchor")
        }),
        "checkpoint_matches" => json!(
            text(o, "state") == "ACTIVATED_DETAIL"
                && !blank(text(&a["anchors"], "recentActivatedAnchor"))
                && (o["currentAnchor"] == a["anchors"]["recentActivatedAnchor"]
                    || o["detailCardAnchor"] == a["anchors"]["recentActivatedAnchor"])
        ),
        _ => return Err("unknown visual action policy operation".into()),
    })
}
