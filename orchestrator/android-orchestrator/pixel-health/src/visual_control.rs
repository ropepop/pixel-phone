//! Pixel-local classification of bounded sanitized probes. No images or recognized text leave this core.
const W: i32 = 48;
const H: i32 = 72;
const SW: i32 = 96;
const SH: i32 = 144;
const CHIP_START: i32 = 20;
const CHIP_END: i32 = 42;
const CHIP_HEIGHT: i32 = 5;
const CHIP_DARK: i32 = 100;
const CHIP_LIGHT: i32 = 145;
const CHIP_DARK_ROW: i32 = 22;
const CHIP_LONG_RUN: i32 = 10;
const CHIP_LONG_ROWS: i32 = 2;
const UNKNOWN: &str = "unknown";
const POPUP: &str = "control_popup";
const STATIC_READY: &str = "control_popup_static_ready";
const VALUE_READY: &str = "control_popup_value_ready";
const KEYBOARD_READY: &str = "control_popup_keyboard_ready";
const GENERATED: &str = "generated";
const RAW: &str = "raw_ticket";
const LIST: &str = "ticket_list_with_registration_button";
fn valid(p: &[i32], w: i32, h: i32) -> bool {
    w > 0 && h > 0 && i64::from(w) * i64::from(h) == p.len() as i64
}
fn rgb(p: i32) -> (i32, i32, i32) {
    ((p >> 16) & 255, (p >> 8) & 255, p & 255)
}
fn luminance(p: i32) -> i32 {
    // Keep this legacy weighting distinct from the action/date classifier.
    let (r, g, b) = rgb(p);
    (r * 299 + g * 587 + b * 114) / 1000
}
fn pixel(p: &[i32], x: i32, y: i32) -> i32 {
    p[(y * W + x) as usize]
}
fn submit_pixel(p: &[i32], x: i32, y: i32) -> i32 {
    p[(y * SW + x) as usize]
}
#[derive(Default)]
struct Stats {
    mean: f64,
    contrast: f64,
    dark: f64,
    light: f64,
}
fn stats(p: &[i32], w: i32, h: i32, rect: [i32; 4], dark_limit: i32, light_limit: i32) -> Stats {
    let [left, top, right, bottom] = rect;
    let (mut n, mut dark, mut light) = (0, 0, 0);
    let (mut sum, mut squares) = (0i64, 0i64);
    for y in top.max(0)..h.min(bottom) {
        for x in left.max(0)..w.min(right) {
            let v = luminance(p[(y * w + x) as usize]);
            sum += i64::from(v);
            squares += i64::from(v) * i64::from(v);
            if v <= dark_limit {
                dark += 1;
            }
            if v >= light_limit {
                light += 1;
            }
            n += 1;
        }
    }
    if n == 0 {
        return Stats::default();
    }
    let mean = sum as f64 / f64::from(n);
    Stats {
        mean,
        contrast: (squares as f64 / f64::from(n) - mean * mean)
            .max(0.0)
            .sqrt(),
        dark: f64::from(dark) / f64::from(n),
        light: f64::from(light) / f64::from(n),
    }
}
fn visual(p: &[i32], rect: [i32; 4]) -> Stats {
    stats(p, W, H, rect, 80, 175)
}
fn submit_visual(p: &[i32], rect: [i32; 4]) -> Stats {
    stats(p, SW, SH, rect, 80, 175)
}
fn compact(p: &[i32]) -> Option<Vec<i32>> {
    if !valid(p, SW, SH) {
        return None;
    }
    // Original rooted point sample: odd x/y, never an averaged reduction.
    let mut result = vec![0; (W * H) as usize];
    for y in 0..H {
        for x in 0..W {
            result[(y * W + x) as usize] = submit_pixel(p, x * 2 + 1, y * 2 + 1);
        }
    }
    Some(result)
}
fn orange(p: i32) -> bool {
    let (r, g, b) = rgb(p);
    r >= 175 && (95..=240).contains(&g) && b <= 120 && r - g >= 22
}
fn activated_color(p: i32) -> bool {
    if orange(p) {
        return true;
    }
    let (r, g, b) = rgb(p);
    b >= 175 && (95..=240).contains(&g) && r <= 120 && b - g >= 22
}
fn pale_status(p: i32) -> bool {
    let (r, g, b) = rgb(p);
    luminance(p) >= 165
        && r >= 145
        && g >= 155
        && b >= 155
        && r.max(g.max(b)) - r.min(g.min(b)) <= 70
}
fn header(p: &[i32]) -> bool {
    let label = visual(p, [2, 2, 19, 7]);
    let top = visual(p, [0, 0, W, 10]);
    let (mut samples, mut red_pixels) = (0, 0);
    for y in 8..15 {
        for x in 1..W - 1 {
            let (r, g, b) = rgb(pixel(p, x, y));
            if r >= 135 && r - g >= 25 && r - b >= 35 && g <= 110 && b <= 115 {
                red_pixels += 1;
            }
            samples += 1;
        }
    }
    let ratio = if samples == 0 {
        0.0
    } else {
        f64::from(red_pixels) / f64::from(samples)
    };
    let label_pill =
        label.mean >= 150.0 && label.light >= 0.48 && label.dark <= 0.34 && label.contrast <= 115.0;
    let legacy = top.light >= 0.10 && top.dark >= 0.10 && ratio >= 0.24;
    let (mut rows, mut widest) = (0, 0);
    for y in 0..16 {
        let mut colored = 0;
        for x in 1..W - 1 {
            let (r, g, b) = rgb(pixel(p, x, y));
            let max = r.max(g.max(b));
            let min = r.min(g.min(b));
            if max >= 130 && max - min >= 40 && (r - g >= 22 || b - g >= 22) {
                colored += 1;
            }
        }
        widest = widest.max(colored);
        if colored >= 24 {
            rows += 1;
        }
    }
    (label_pill && legacy) || (rows >= 3 && widest >= 32)
}
fn detail(p: &[i32]) -> bool {
    let code = visual(p, [8, 14, 40, 34]);
    header(p) && code.dark >= 0.10 && code.light >= 0.18 && code.contrast >= 45.0
}
fn raw_ticket(p: &[i32]) -> bool {
    let separator = visual(p, [7, 36, 41, 40]);
    detail(p)
        && separator.light >= 0.50
        && separator.dark >= 0.04
        && separator.dark <= 0.45
        && separator.contrast >= 35.0
}
fn yellow_band(p: &[i32], top: i32, bottom: i32) -> bool {
    let (mut rows, mut widest) = (0, 0);
    for y in top..=bottom {
        let mut yellow = 0;
        for x in 2..W - 2 {
            let (r, g, b) = rgb(pixel(p, x, y));
            if r >= 180 && (110..=235).contains(&g) && b <= 90 && r - g >= 25 {
                yellow += 1;
            }
        }
        widest = widest.max(yellow);
        if yellow >= 24 {
            rows += 1;
        }
    }
    rows >= 3 && widest >= 30
}
fn current_registered_status(p: &[i32]) -> bool {
    let (mut left, mut top, mut right, mut bottom) = (W, H, -1, -1);
    let (mut pixels, mut rows, mut widest) = (0, 0, 0);
    for y in 38..=55 {
        let mut count = 0;
        for x in 33..W - 1 {
            if !activated_color(pixel(p, x, y)) {
                continue;
            }
            left = left.min(x);
            top = top.min(y);
            right = right.max(x);
            bottom = bottom.max(y);
            pixels += 1;
            count += 1;
        }
        if count >= 2 {
            rows += 1;
        }
        widest = widest.max(count);
    }
    if right < left || bottom < top {
        return false;
    }
    let width = right - left + 1;
    let height = bottom - top + 1;
    if left < 36
        || right < 39
        || !(2..=8).contains(&width)
        || !(2..=8).contains(&height)
        || pixels < 6
        || rows < 2
        || widest > 8
    {
        return false;
    }
    let (mut pale_rows, mut pale_widest) = (0, 0);
    for y in 38.max(top - 2)..56.min(bottom + 3) {
        let mut count = 0;
        for x in 3..35 {
            if pale_status(pixel(p, x, y)) {
                count += 1;
            }
        }
        if count >= 18 {
            pale_rows += 1;
        }
        pale_widest = pale_widest.max(count);
    }
    pale_rows >= 2 && pale_widest >= 22
}
fn registered(p: &[i32]) -> bool {
    detail(p) && (yellow_band(p, 41, 49) || current_registered_status(p))
}
fn orange_button(p: &[i32], top: i32, bottom: i32) -> bool {
    let (mut n, mut count, mut rows) = (0, 0, 0);
    for y in top..bottom {
        let mut row = 0;
        for x in 31..42 {
            let (r, g, b) = rgb(pixel(p, x, y));
            if r >= 155 && (80..=190).contains(&g) && b <= 95 && r - g >= 20 && g - b >= 25 {
                count += 1;
                row += 1;
            }
            n += 1;
        }
        if row >= 6 {
            rows += 1;
        }
    }
    n > 0 && rows >= 3 && f64::from(count) / f64::from(n) >= 0.08
}
fn blue_button(p: &[i32], top: i32, bottom: i32) -> bool {
    let (mut n, mut count) = (0, 0);
    for y in top..bottom {
        for x in 4..44 {
            let (r, g, b) = rgb(pixel(p, x, y));
            if b >= 80 && b - r >= 35 && b - g >= 20 {
                count += 1;
            }
            n += 1;
        }
    }
    n > 0 && f64::from(count) / f64::from(n) >= 0.18
}
fn light_dialog(s: &Stats) -> bool {
    s.mean >= 125.0 && s.light >= 0.46 && s.dark <= 0.28 && s.contrast <= 95.0
}
fn dark_dialog(s: &Stats, ratio: f64) -> bool {
    s.mean <= 85.0 && s.dark >= ratio && s.light <= 0.12 && s.contrast <= 90.0
}
fn strong_dark_popup(p: &[i32]) -> bool {
    (dark_dialog(&visual(p, [4, 26, 44, 43]), 0.65) && blue_button(p, 34, 40))
        || (dark_dialog(&visual(p, [4, 12, 44, 31]), 0.65) && blue_button(p, 20, 29))
}
fn popup(p: &[i32]) -> bool {
    (light_dialog(&visual(p, [8, 30, 40, 45])) && orange_button(p, 39, 44))
        || (light_dialog(&visual(p, [8, 16, 40, 30])) && orange_button(p, 24, 29))
        || strong_dark_popup(p)
}
fn dark_rows(p: &[i32], top: i32) -> i32 {
    let mut count = 0;
    for y in top..(top + CHIP_HEIGHT).min(H) {
        let mut dark = 0;
        for x in 7..W - 7 {
            if luminance(pixel(p, x, y)) <= CHIP_DARK {
                dark += 1;
            }
        }
        if dark >= CHIP_DARK_ROW {
            count += 1;
        }
    }
    count
}
fn long_rows(p: &[i32], top: i32) -> i32 {
    let mut count = 0;
    for y in top..(top + CHIP_HEIGHT).min(H) {
        let (mut run, mut longest) = (0, 0);
        for x in 7..W - 7 {
            if luminance(pixel(p, x, y)) <= CHIP_DARK {
                run += 1;
                longest = longest.max(run);
            } else {
                run = 0;
            }
        }
        if longest >= CHIP_LONG_RUN {
            count += 1;
        }
    }
    count
}
fn chip_stats(p: &[i32], top: i32) -> Stats {
    stats(
        p,
        W,
        H,
        [7, top, W - 7, top + CHIP_HEIGHT],
        CHIP_DARK,
        CHIP_LIGHT,
    )
}
fn chip_top(p: &[i32]) -> i32 {
    let (mut best, mut score) = (-1, i32::MIN);
    for top in CHIP_START..=CHIP_END {
        let dark = dark_rows(p, top);
        if dark < 3 {
            continue;
        }
        let s = chip_stats(p, top);
        if s.dark < 0.50 || s.light < 0.01 || s.light > 0.48 || s.contrast < 20.0 {
            continue;
        }
        let candidate = dark * 100 + (s.dark * 50.0).round() as i32 + s.contrast.round() as i32;
        if candidate > score {
            score = candidate;
            best = top;
        }
    }
    best
}
fn generated(p: &[i32]) -> bool {
    if !detail(p) {
        return false;
    }
    let top = chip_top(p);
    if top < 0 {
        return false;
    }
    let s = chip_stats(p, top);
    s.dark >= 0.50
        && s.light >= 0.01
        && s.light <= 0.48
        && s.contrast >= 20.0
        && dark_rows(p, top) >= 3
        && long_rows(p, top) >= CHIP_LONG_ROWS
}
pub(crate) fn classify(p: &[i32]) -> &'static str {
    if !valid(p, W, H) {
        return UNKNOWN;
    }
    if popup(p) {
        POPUP
    } else if generated(p) {
        GENERATED
    } else if raw_ticket(p) {
        RAW
    } else {
        UNKNOWN
    }
}
pub(crate) fn classify_for_activated_ticket(p: &[i32]) -> &'static str {
    if !valid(p, W, H) {
        UNKNOWN
    } else if popup(p) {
        POPUP
    } else if detail(p) {
        RAW
    } else {
        UNKNOWN
    }
}
pub(crate) fn classify_for_cleanup(p: &[i32]) -> &'static str {
    // A malformed probe cannot grant cleanup authority (the old Java method
    // could throw here). Valid probes retain the dedicated cleanup precedence.
    if !valid(p, W, H) {
        return UNKNOWN;
    }
    if yellow_band(p, 28, 39) {
        return LIST;
    }
    // Broad light-popup geometry overlaps the registered detail; only the
    // strong dark-sheet proof may reject the independently detected result.
    if generated(p) && !strong_dark_popup(p) {
        return GENERATED;
    }
    if strong_dark_popup(p) {
        return UNKNOWN;
    }
    if registered(p) { RAW } else { UNKNOWN }
}
pub(crate) fn classify_for_cleanup_high_resolution(p: &[i32]) -> &'static str {
    compact(p).map_or(UNKNOWN, |p| classify_for_cleanup(&p))
}
fn wire(left: i32, top: i32, right: i32, bottom: i32) -> String {
    format!("{left},{top},{right},{bottom}")
}
pub(crate) fn generated_result_close_bounds(p: &[i32]) -> String {
    if !valid(p, W, H) || classify_for_cleanup(p) != GENERATED {
        return String::new();
    }
    let chip = chip_top(p);
    if chip < 0 {
        return String::new();
    }
    let (mut left, mut top, mut right, mut bottom, mut count) = (W, H, -1, -1, 0);
    // Only the right edge of the exact strip owns the close target. A bright
    // area elsewhere on the ticket must never enlarge a permitted tap.
    for y in chip..chip + CHIP_HEIGHT {
        for x in W - 14..W - 7 {
            if luminance(pixel(p, x, y)) >= CHIP_LIGHT {
                left = left.min(x);
                top = top.min(y);
                right = right.max(x + 1);
                bottom = bottom.max(y + 1);
                count += 1;
            }
        }
    }
    if !(2..=20).contains(&count)
        || right <= left
        || bottom <= top
        || right - left > 6
        || bottom - top > CHIP_HEIGHT
    {
        return String::new();
    }
    wire(
        (W - 14).max(left - 2),
        chip.max(top - 2),
        (W - 7).min(right + 2),
        (chip + CHIP_HEIGHT).min(bottom + 2),
    )
}
pub(crate) fn generated_result_close_bounds_high_resolution(p: &[i32]) -> String {
    let Some(compacted) = compact(p) else {
        return String::new();
    };
    if classify_for_cleanup(&compacted) != GENERATED {
        return String::new();
    }
    let (mut high_top, mut high_bottom, mut candidate) = (-1, -1, -1);
    for y in CHIP_START * 2..CHIP_END * 2 {
        let (mut dark, mut longest, mut run) = (0, 0, 0);
        for x in 8..SW - 8 {
            if luminance(submit_pixel(p, x, y)) <= CHIP_DARK {
                dark += 1;
                run += 1;
                longest = longest.max(run);
            } else {
                run = 0;
            }
        }
        if dark >= CHIP_DARK_ROW * 2 && longest >= CHIP_LONG_RUN * 2 {
            if candidate < 0 {
                candidate = y;
            }
            if y - candidate + 1 >= CHIP_LONG_ROWS * 2 + 1 {
                high_top = candidate;
                high_bottom = y + 1;
            }
        } else {
            if high_top >= 0 {
                break;
            }
            candidate = -1;
        }
    }
    if high_top < 0 || high_bottom <= high_top {
        return String::new();
    }
    let zone_left = (W - 14) * 2;
    let zone_right = (W - 7) * 2;
    let (mut left, mut top, mut right, mut bottom, mut count, mut widest) = (SW, SH, -1, -1, 0, 0);
    for y in high_top..high_bottom {
        let mut row = 0;
        for x in zone_left..zone_right {
            if luminance(submit_pixel(p, x, y)) >= CHIP_LIGHT {
                left = left.min(x);
                top = top.min(y);
                right = right.max(x + 1);
                bottom = bottom.max(y + 1);
                count += 1;
                row += 1;
            }
        }
        widest = widest.max(row);
    }
    if !(3..=32).contains(&count)
        || widest < 2
        || right - left < 3
        || right - left > 10
        || bottom - top < 2
        || bottom - top > 8
    {
        return String::new();
    }
    wire(
        zone_left.max(left - 3) / 2,
        high_top.max(top - 3) / 2,
        (zone_right.min(right + 3) + 1) / 2,
        (high_bottom.min(bottom + 3) + 1) / 2,
    )
}
pub(crate) fn registration_slider_bounds(p: &[i32]) -> String {
    // Header, wide orange band and attached charcoal thumb are all mandatory.
    if !valid(p, W, H) || !header(p) {
        return String::new();
    }
    let (mut left, mut top, mut right, mut bottom, mut score) = (-1, -1, -1, -1, i32::MIN);
    let (mut band_start, mut band_left, mut band_right) = (-1, W, -1);
    for y in 38..=55 {
        let (mut row_left, mut row_right, mut count) = (W, -1, 0);
        for x in 2..W - 2 {
            if orange(pixel(p, x, y)) {
                count += 1;
                row_left = row_left.min(x);
                row_right = row_right.max(x);
            }
        }
        if count >= 20 && row_right > row_left {
            if band_start < 0 {
                band_start = y;
                band_left = row_left;
                band_right = row_right;
            } else {
                band_left = band_left.min(row_left);
                band_right = band_right.max(row_right);
            }
            let band_bottom = y + 1;
            let height = band_bottom - band_start;
            let width = band_right - band_left + 1;
            let candidate = height * 100 + width * 3 + count;
            if height >= 3 && width >= 24 && candidate > score {
                score = candidate;
                left = band_left;
                top = band_start;
                right = band_right + 1;
                bottom = band_bottom;
            }
        } else if band_start >= 0 {
            band_start = -1;
            band_left = W;
            band_right = -1;
        }
    }
    if left < 0 {
        return String::new();
    }
    let width = right - left;
    let start = 0.max(left - 8.max(width / 3));
    let end = right.min(left + 5.max(width / 5));
    let (mut dark, mut tl, mut tt, mut tr, mut tb) = (0, W, H, -1, -1);
    for y in top..bottom {
        for x in start..end {
            if luminance(pixel(p, x, y)) <= 90 && !orange(pixel(p, x, y)) {
                dark += 1;
                tl = tl.min(x);
                tt = tt.min(y);
                tr = tr.max(x + 1);
                tb = tb.max(y + 1);
            }
        }
    }
    if dark < 3 || tr - tl < 2 || tb - tt < 2 {
        return String::new();
    }
    wire(
        0.max(left.min(tl) - 1),
        0.max(top - 1),
        W.min(right + 1),
        H.min(bottom + 1),
    )
}
#[derive(Default)]
struct Intensity {
    pixels: i32,
    columns: i32,
    span: i32,
    maximum_row: i32,
}
fn intensity(p: &[i32], rect: [i32; 4], threshold: i32, dark: bool) -> Intensity {
    let [left, top, right, bottom] = rect;
    let mut result = Intensity::default();
    let mut columns = [false; SW as usize];
    let (mut first, mut last) = (SW, -1);
    for y in top.max(0)..SH.min(bottom) {
        let mut row = 0;
        for x in left.max(0)..SW.min(right) {
            let value = luminance(submit_pixel(p, x, y));
            if if dark {
                value <= threshold
            } else {
                value >= threshold
            } {
                result.pixels += 1;
                row += 1;
                if !columns[x as usize] {
                    columns[x as usize] = true;
                    result.columns += 1;
                }
                first = first.min(x);
                last = last.max(x);
            }
        }
        result.maximum_row = result.maximum_row.max(row);
    }
    result.span = if last < 0 { 0 } else { last - first };
    result
}
fn enabled_submit(p: &[i32], top: i32, bottom: i32) -> bool {
    let (mut n, mut chromatic) = (0, 0);
    for y in top..bottom {
        for x in 62..84 {
            let (r, g, b) = rgb(submit_pixel(p, x, y));
            let max = r.max(g.max(b));
            let min = r.min(g.min(b));
            if max >= 155 && min <= 95 && max - min >= 100 {
                chromatic += 1;
            }
            n += 1;
        }
    }
    n > 0 && f64::from(chromatic) / f64::from(n) >= 0.18
}
fn blue_submit(p: &[i32], top: i32, bottom: i32) -> bool {
    let (mut n, mut count) = (0, 0);
    for y in top..bottom {
        for x in 8..88 {
            let (r, g, b) = rgb(submit_pixel(p, x, y));
            if b >= 80 && b - r >= 35 && b - g >= 20 {
                count += 1;
            }
            n += 1;
        }
    }
    n > 0 && f64::from(count) / f64::from(n) >= 0.18
}
fn dark_ready(p: &[i32]) -> bool {
    dark_dialog(&submit_visual(p, [8, 52, 88, 86]), 0.75) && blue_submit(p, 68, 78)
}
fn static_ready(p: &[i32]) -> bool {
    light_dialog(&submit_visual(p, [16, 60, 80, 90])) && enabled_submit(p, 73, 82)
}
fn dark_value(p: &[i32]) -> bool {
    if !dark_ready(p) {
        return false;
    }
    let v = intensity(p, [30, 60, 66, 68], 190, false);
    v.pixels >= 3 && v.columns >= 2
}
fn static_value(p: &[i32]) -> bool {
    if !static_ready(p) {
        return false;
    }
    let v = intensity(p, [28, 64, 68, 72], 90, true);
    v.pixels >= 2
        && v.columns >= 2
        && (v.span <= 20 || v.columns > v.maximum_row + 1)
        && (v.span >= 2 || (v.span >= 1 && v.maximum_row >= 2))
}
pub(crate) fn classify_submit_layout(p: &[i32]) -> &'static str {
    if !valid(p, SW, SH) {
        return UNKNOWN;
    }
    if dark_value(p) || static_value(p) {
        VALUE_READY
    } else if dark_ready(p) || static_ready(p) {
        STATIC_READY
    } else if (dark_dialog(&submit_visual(p, [8, 24, 88, 62]), 0.75) && blue_submit(p, 40, 56))
        || (light_dialog(&submit_visual(p, [16, 32, 80, 60])) && enabled_submit(p, 48, 58))
    {
        KEYBOARD_READY
    } else {
        UNKNOWN
    }
}
fn submit_button(p: &[i32]) -> Option<[i32; 4]> {
    let (mut left, mut top, mut right, mut bottom, mut count) = (SW, SH, -1, -1, 0);
    for y in 20..105 {
        for x in 4..SW - 4 {
            let (r, g, b) = rgb(submit_pixel(p, x, y));
            let orange = r >= 175 && (85..=220).contains(&g) && b <= 110 && r - g >= 25;
            let blue = b >= 80 && b - r >= 35 && b - g >= 15;
            if !orange && !blue {
                continue;
            }
            left = left.min(x);
            top = top.min(y);
            right = right.max(x + 1);
            bottom = bottom.max(y + 1);
            count += 1;
        }
    }
    if count < 20 || right - left < 10 || bottom - top < 3 {
        None
    } else {
        Some([left, top, right, bottom])
    }
}
pub(crate) fn submit_input_bounds(p: &[i32]) -> String {
    if !matches!(classify_submit_layout(p), STATIC_READY | VALUE_READY) {
        return String::new();
    }
    let Some([_, button_top, _, _]) = submit_button(p) else {
        return String::new();
    };
    let left = SW * 7 / 24;
    let right = SW * 17 / 24;
    let bottom = button_top - 2;
    let top = bottom - 8;
    if right - left < 8 || bottom - top < 3 {
        String::new()
    } else {
        wire(left, top, right, bottom)
    }
}
pub(crate) fn submit_button_bounds(p: &[i32]) -> String {
    if !matches!(classify_submit_layout(p), STATIC_READY | VALUE_READY) {
        return String::new();
    }
    submit_button(p).map_or_else(String::new, |[l, t, r, b]| wire(l, t, r, b))
}
pub(crate) fn code_signature_pixels(p: &[i32]) -> Option<Vec<u8>> {
    if !valid(p, W, H) || !detail(p) || popup(p) {
        return None;
    }
    let mut global = 0;
    for y in 14..34 {
        for x in 8..40 {
            global += luminance(pixel(p, x, y));
        }
    }
    let average = global / (32 * 20);
    let mut result = Vec::with_capacity(20);
    for top in (14..34).step_by(4) {
        for left in (8..40).step_by(8) {
            let mut sum = 0;
            for y in top..top + 4 {
                for x in left..left + 8 {
                    sum += luminance(pixel(p, x, y));
                }
            }
            let delta = sum / 32 - average;
            result.push(if delta < -48 {
                0
            } else if delta < -16 {
                1
            } else if delta <= 16 {
                2
            } else if delta <= 48 {
                3
            } else {
                4
            });
        }
    }
    Some(result)
}
pub(crate) fn high_res_code_signature_pixels(p: &[i32]) -> Option<Vec<u8>> {
    code_signature_pixels(&compact(p)?)
}
pub(crate) fn static_signature_pixels(p: &[i32], width: i32, height: i32) -> Option<Vec<u8>> {
    if width < 48 || height < 72 || !valid(p, width, height) {
        return None;
    }
    let left = width / 12;
    let top = height * 35 / 72;
    let right = width - width / 12;
    let bottom = height * 42 / 72;
    let block_w = 2.max(width / 48);
    let block_h = 2.max(height / 72);
    let right = width.min(left.max(right));
    let bottom = height.min(top.max(bottom));
    // Preserve the TD domain and lower metadata band, excluding the rotating
    // code, slider and result. Java adds the original process salt and digest.
    let mut result = vec![0x54, 0x44];
    for y in (top.max(0)..bottom).step_by(block_h as usize) {
        for x in (left.max(0)..right).step_by(block_w as usize) {
            let (mut sum, mut samples) = (0, 0);
            for sy in y..bottom.min(y + block_h) {
                for sx in x..right.min(x + block_w) {
                    sum += luminance(p[(sy * width + sx) as usize]);
                    samples += 1;
                }
            }
            let average = if samples == 0 { 0 } else { sum / samples };
            result.push((average / 16) as u8);
        }
    }
    Some(result)
}
