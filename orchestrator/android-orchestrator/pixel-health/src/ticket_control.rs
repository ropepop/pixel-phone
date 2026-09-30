//! Phone-local context and fresh visual evidence. No input, publication, clock or capture effects.
use serde_json::{Value, json};

fn n(v: &Value, key: &str) -> i64 {
    v[key].as_i64().unwrap_or(0)
}
fn text<'a>(v: &'a Value, key: &str) -> &'a str {
    v[key].as_str().unwrap_or("")
}
fn blank(value: &str) -> bool {
    crate::trim(value).is_empty()
}
fn detail(v: &Value) -> bool {
    matches!(text(v, "state"), "UNACTIVATED_DETAIL" | "ACTIVATED_DETAIL")
}
fn bounds_agree(a: &Value, b: &Value, tolerance: i32) -> bool {
    if a.is_null() || b.is_null() {
        return a == b;
    }
    ["left", "top", "right", "bottom"].iter().all(|key| {
        (n(a, key) as i32)
            .wrapping_sub(n(b, key) as i32)
            .wrapping_abs()
            <= tolerance
    })
}
fn string_order(a: &str, b: &str) -> std::cmp::Ordering {
    a.encode_utf16().cmp(b.encode_utf16())
}
pub(crate) fn observations_agree(a: &Value, b: &Value, tolerance: i32) -> bool {
    if a["state"] != b["state"]
        || (!detail(a) && a["bottomTab"] != b["bottomTab"])
        || a["currentAnchor"] != b["currentAnchor"]
        || a["detailCardAnchor"] != b["detailCardAnchor"]
    {
        return false;
    }
    if ["sliderBounds", "controlCodeBounds", "backBounds"]
        .iter()
        .any(|key| !bounds_agree(&a[key], &b[key], tolerance))
        || (!detail(a)
            && ["ticketsTabBounds", "timeTicketsTabBounds"]
                .iter()
                .any(|key| !bounds_agree(&a[key], &b[key], tolerance)))
    {
        return false;
    }
    let cards = |v: &Value| {
        let mut cards = v["cards"].as_array().cloned().unwrap_or_default();
        cards.sort_by(|a, b| {
            string_order(text(a, "anchor"), text(b, "anchor"))
                .then_with(|| n(&a["bounds"], "top").cmp(&n(&b["bounds"], "top")))
        });
        cards
    };
    let first = cards(a);
    let second = cards(b);
    first.len() == second.len()
        && first.iter().zip(second).all(|(a, b)| {
            a["anchor"] == b["anchor"]
                && a["latest"] == b["latest"]
                && ["bounds", "registrationBounds", "activatedDetailBounds"]
                    .iter()
                    .all(|key| bounds_agree(&a[key], &b[key], tolerance))
        })
}
fn registration_fresh(v: &Value, fence: &Value, now: i64) -> bool {
    let captured = n(v, "captureStartUs") / 1_000;
    n(v, "captureStartUs") > 0
        && v["captureGeneration"] == fence["captureGeneration"]
        && captured >= n(fence, "validAfterMillis")
        && captured <= n(v, "atMillis")
        && (0..3_000).contains(&now.wrapping_sub(captured))
        && text(v, "state") == "UNACTIVATED_DETAIL"
        && !blank(text(v, "currentAnchor"))
        && !v["sliderBounds"].is_null()
}
fn evidence_fresh(e: &Value, now: i64) -> bool {
    let f = &e["fence"];
    let a = &e["first"];
    let b = &e["second"];
    n(f, "streamEpoch") > 0
        && n(f, "captureGeneration") > 0
        && n(f, "inputGeneration") > 0
        && n(f, "windowId") >= 0
        && n(a, "captureStartUs") < n(b, "captureStartUs")
        && registration_fresh(a, f, now)
        && registration_fresh(b, f, now)
        && observations_agree(a, b, 3)
}
fn empty(session: &str) -> Value {
    json!({"counter":0,"sequence":0,"captured":0,"key":null,"first":null,"fence":null,
 "update":{"contextRevision":format!("{session}:0"),"sequence":0,"observation":null,"busy":false,"reason":"phone_session_started","inputAvailable":true}})
}

pub(crate) fn decide(operation: &str, payload: &str) -> Result<Value, String> {
    let input: Value = serde_json::from_str(payload).map_err(|_| "invalid phone control input")?;
    let args = &input["args"];
    let now = n(args, "now");
    match operation {
        "agree" => {
            return Ok(json!(observations_agree(
                &args["first"],
                &args["second"],
                n(args, "tolerance") as i32
            )));
        }
        "registration_fresh" => {
            return Ok(json!(registration_fresh(
                &args["observation"],
                &args["fence"],
                now
            )));
        }
        "evidence_fresh" => return Ok(json!(evidence_fresh(&args["evidence"], now))),
        "clock" => {
            let captured = n(args, "captureStartUs") / 1_000;
            let received = n(args, "received");
            return Ok(
                if !(0..30_000).contains(&now.wrapping_sub(received))
                    || captured > now
                    || n(args, "captureStartUs") <= 0
                {
                    Value::Null
                } else {
                    json!(
                        n(args, "server")
                            .wrapping_add(captured)
                            .wrapping_sub(received)
                            .wrapping_sub(1)
                    )
                },
            );
        }
        "identity" => {
            let v = &args["observation"];
            return Ok(json!(
                !v.is_null()
                    && text(args, "revision").starts_with("pc-")
                    && text(v, "state") == "UNACTIVATED_DETAIL"
                    && !blank(text(v, "currentAnchor"))
                    && !v["sliderBounds"].is_null()
            ));
        }
        "publish_ready" => {
            let u = &args["update"];
            let v = &u["observation"];
            return Ok(json!(
                u["inputAvailable"] == true
                    && u["busy"] != true
                    && !v.is_null()
                    && !blank(text(v, "currentAnchor"))
                    && detail(v)
                    && (0..3_000).contains(&now.wrapping_sub(n(v, "captureStartUs") / 1_000))
                    && !text(args, "observedAt").is_empty()
                    && (text(v, "state") != "UNACTIVATED_DETAIL"
                        || args["boundsAvailable"] == true)
            ));
        }
        _ => {}
    }
    let mut state = if input["state"].is_null() {
        empty(text(&input, "session"))
    } else {
        input["state"].clone()
    };
    let mut answer = Value::Null;
    match operation {
        "init" => {}
        "observe" => {
            let v = &args["observation"];
            if n(v, "captureStartUs") > n(&state, "captured") {
                state["captured"] = v["captureStartUs"].clone();
                let key = json!([
                    v["state"],
                    v["currentAnchor"],
                    v["sliderBounds"],
                    args["inputAvailable"]
                ]);
                let prior = &state["update"]["observation"];
                let first = if !prior.is_null()
                    && key == state["key"]
                    && !args["fence"].is_null()
                    && args["fence"] == state["fence"]
                    && observations_agree(prior, v, 3)
                {
                    prior.clone()
                } else {
                    Value::Null
                };
                state["fence"] = if args["inputAvailable"] == true
                    && text(v, "state") == "UNACTIVATED_DETAIL"
                    && !blank(text(v, "currentAnchor"))
                    && !v["sliderBounds"].is_null()
                {
                    args["fence"].clone()
                } else {
                    Value::Null
                };
                state["first"] = if state["fence"].is_null() {
                    Value::Null
                } else {
                    first
                };
                if key != state["key"] {
                    state["counter"] = json!(n(&state, "counter").wrapping_add(1));
                    state["key"] = key;
                }
                state["sequence"] = json!(n(&state, "sequence").wrapping_add(1));
                state["update"] = json!({"contextRevision":format!("{}:{}",text(&input,"session"),n(&state,"counter")),"sequence":state["sequence"],
 "observation":v,"busy":args["busy"],"inputAvailable":args["inputAvailable"],"reason":if args["inputAvailable"]!=true {"ticket_action_accessibility_unavailable"}
 else if args["busy"]==true {"phone_busy"} else if blank(text(v,"currentAnchor")) {"ticket_not_identified"} else {""}});
            }
        }
        "invalidate" => {
            state["counter"] = json!(n(&state, "counter").wrapping_add(1));
            state["sequence"] = json!(n(&state, "sequence").wrapping_add(1));
            state["captured"] = json!(n(&state, "captured").max(n(args, "captured")));
            state["key"] = Value::Null;
            state["first"] = Value::Null;
            state["fence"] = Value::Null;
            state["update"] = json!({"contextRevision":format!("{}:{}",text(&input,"session"),n(&state,"counter")),"sequence":state["sequence"],"observation":null,"busy":args["busy"],"reason":args["reason"],"inputAvailable":true});
        }
        "clear" => {
            state["first"] = Value::Null;
            state["fence"] = Value::Null;
        }
        "exact" => {
            let u = &state["update"];
            let v = &u["observation"];
            if !v.is_null()
                && u["inputAvailable"] == true
                && u["busy"] != true
                && u["contextRevision"] == args["revision"]
                && (0..3_000).contains(&now.wrapping_sub(n(v, "captureStartUs") / 1_000))
                && !blank(text(v, "currentAnchor"))
                && detail(v)
            {
                answer = v.clone();
            }
        }
        "candidate" => {
            answer = json!(
                !args["fence"].is_null()
                    && args["fence"] == state["fence"]
                    && state["update"]["contextRevision"] == args["revision"]
                    && !state["update"]["observation"].is_null()
                    && registration_fresh(&state["update"]["observation"], &args["fence"], now)
            );
        }
        "evidence" => {
            if !args["fence"].is_null()
                && args["fence"] == state["fence"]
                && state["update"]["contextRevision"] == args["revision"]
                && !state["first"].is_null()
                && !state["update"]["observation"].is_null()
            {
                let e = json!({"contextRevision":args["revision"],"first":state["first"],"second":state["update"]["observation"],"fence":args["fence"]});
                if evidence_fresh(&e, now) {
                    answer = e;
                }
            }
        }
        "evidence_current" => {
            let e = &args["evidence"];
            answer = json!(
                e["contextRevision"] == state["update"]["contextRevision"]
                    && e["fence"] == state["fence"]
                    && e["fence"] == args["fence"]
                    && evidence_fresh(e, now)
            );
        }
        _ => return Err("unknown phone control operation".into()),
    }
    Ok(json!({"state":state,"answer":answer}))
}
