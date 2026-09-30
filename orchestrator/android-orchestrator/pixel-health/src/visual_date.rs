//! The existing conservative date recognizer, with the same masks and confidence gates.
//! Captured pixels and dates never leave the process. Java retains the private anchor salt.
use chrono::{Datelike, Months, NaiveDate};
use std::{collections::HashSet, sync::LazyLock};

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct DateRange {
    pub from: i64,
    pub until: i64,
    pub center_y: usize,
}
#[derive(Clone)]
struct Component {
    left: usize,
    top: usize,
    right: usize,
    bottom: usize,
    value: char,
}
impl Component {
    fn center(&self) -> usize {
        (self.top + self.bottom) / 2
    }
}

pub(crate) fn valid_size(length: usize, width: i32, height: i32) -> Option<(usize, usize)> {
    if width <= 0 || height <= 0 {
        return None;
    }
    let (w, h) = (width as usize, height as usize);
    (w.checked_mul(h) == Some(length)).then_some((w, h))
}
pub(crate) fn recognize(pixels: &[i32], width: i32, height: i32) -> Vec<DateRange> {
    let Some((w, h)) = valid_size(pixels.len(), width, height) else {
        return vec![];
    };
    let luminance: Vec<_> = pixels
        .iter()
        .map(|p| (((p >> 16) & 255) * 54 + ((p >> 8) & 255) * 183 + (p & 255) * 19) >> 8)
        .collect();
    let mut candidates = vec![];
    for (dark, bright) in [(115, 185), (150, 150), (185, 115), (205, 115)] {
        candidates.extend(recognize_polarity(
            &luminance.iter().map(|v| *v <= dark).collect::<Vec<_>>(),
            w,
            h,
        ));
        candidates.extend(recognize_polarity(
            &luminance.iter().map(|v| *v >= bright).collect::<Vec<_>>(),
            w,
            h,
        ));
    }
    let mut accepted: Vec<DateRange> = vec![];
    let mut conflicts: Vec<usize> = vec![];
    for value in candidates {
        if conflicts.iter().any(|y| y.abs_diff(value.center_y) <= 5) {
            continue;
        }
        match accepted
            .iter()
            .position(|v| v.center_y.abs_diff(value.center_y) <= 5)
        {
            None => accepted.push(value),
            Some(i) if (accepted[i].from, accepted[i].until) != (value.from, value.until) => {
                conflicts.push((accepted[i].center_y + value.center_y) / 2);
                accepted.remove(i);
            }
            _ => {}
        }
    }
    accepted
}
fn recognize_polarity(foreground: &[bool], w: usize, h: usize) -> Vec<DateRange> {
    let mut components = components(foreground, w, h);
    components.sort_by_key(|v| (v.center(), v.left));
    let mut texts = vec![];
    let mut centers = vec![];
    let mut keys = HashSet::new();
    let mut ranges = vec![];
    for seed in &components {
        let mut row: Vec<_> = components
            .iter()
            .filter(|v| v.center().abs_diff(seed.center()) <= 3.max(seed.bottom - seed.top))
            .collect();
        row.sort_by_key(|v| v.left);
        let mut text = String::new();
        let mut right = None;
        for c in row {
            if right.is_some_and(|r| c.left as isize - r as isize > 8.max(w / 20) as isize) {
                text.push(' ');
            }
            text.push(c.value);
            right = Some(c.right);
        }
        if keys.insert((text.clone(), seed.center() / 3)) {
            if let Some(value) = parse_date_range(&text, seed.center()) {
                ranges.push(value);
            }
            texts.push(text);
            centers.push(seed.center());
        }
    }
    for first in 0..texts.len() {
        let first_digits = digits_only(&texts[first]);
        if first_digits.len() != 8 {
            continue;
        }
        for second in first + 1..texts.len() {
            if centers[first].abs_diff(centers[second]) > 24.max(h / 7) {
                continue;
            }
            let second_digits = digits_only(&texts[second]);
            if second_digits.len() != 8 {
                continue;
            }
            if let Some(value) = parse_date_range(
                &(first_digits.clone() + &second_digits),
                (centers[first] + centers[second]) / 2,
            ) {
                ranges.push(value);
            }
        }
    }
    ranges
}
fn digits_only(value: &str) -> String {
    value.chars().filter(char::is_ascii_digit).collect()
}
fn parse_date_range(value: &str, center_y: usize) -> Option<DateRange> {
    let digits = digits_only(value);
    for start in 0..=digits.len().saturating_sub(16) {
        if digits.len() < 16 {
            break;
        }
        let parse = |at: usize| -> Option<NaiveDate> {
            NaiveDate::from_ymd_opt(
                digits[at + 4..at + 8].parse().ok()?,
                digits[at + 2..at + 4].parse().ok()?,
                digits[at..at + 2].parse().ok()?,
            )
        };
        let (Some(from), Some(until)) = (parse(start), parse(start + 8)) else {
            continue;
        };
        if until < from || until > from.checked_add_months(Months::new(24))? {
            continue;
        }
        return Some(DateRange {
            from: from.num_days_from_ce() as i64 - 719163,
            until: until.num_days_from_ce() as i64 - 719163,
            center_y,
        });
    }
    None
}
fn components(foreground: &[bool], w: usize, h: usize) -> Vec<Component> {
    let mut seen = vec![false; foreground.len()];
    let mut queue = Vec::with_capacity(foreground.len());
    let mut values = vec![];
    for index in 0..foreground.len() {
        if !foreground[index] || seen[index] {
            continue;
        }
        queue.clear();
        queue.push(index);
        seen[index] = true;
        let (mut left, mut top, mut right, mut bottom) = (w, h, 0, 0);
        let mut head = 0;
        while head < queue.len() {
            let current = queue[head];
            head += 1;
            let (x, y) = (current % w, current / w);
            left = left.min(x);
            right = right.max(x);
            top = top.min(y);
            bottom = bottom.max(y);
            for ny in y.saturating_sub(1)..=(y + 1).min(h - 1) {
                for nx in x.saturating_sub(1)..=(x + 1).min(w - 1) {
                    let next = ny * w + nx;
                    if foreground[next] && !seen[next] {
                        seen[next] = true;
                        queue.push(next);
                    }
                }
            }
        }
        let (cw, ch) = (right - left + 1, bottom - top + 1);
        let value = if queue.len() <= 3 && cw <= 3 && ch <= 3 {
            '.'
        } else {
            if ch < 4 || cw < 2 || cw > ch * 2 {
                continue;
            }
            let mut crop = Vec::with_capacity(cw * ch);
            for y in top..=bottom {
                crop.extend_from_slice(&foreground[y * w + left..=y * w + right]);
            }
            recognize_glyph(&crop, cw, ch, true)
        };
        if value != '?' {
            values.push(Component {
                left,
                top,
                right: right + 1,
                bottom: bottom + 1,
                value,
            });
        }
    }
    values
}
fn normalized_words(pixels: &[bool], w: usize, h: usize) -> [u64; 4] {
    let mut words = [0; 4];
    for ny in 0..18 {
        for nx in 0..12 {
            let (left, top) = (nx * w / 12, ny * h / 18);
            let (right, bottom) = (
                (left + 1).max((nx + 1) * w / 12).min(w),
                (top + 1).max((ny + 1) * h / 18).min(h),
            );
            let mut count = 0;
            for y in top..bottom {
                for x in left..right {
                    count += usize::from(pixels[y * w + x]);
                }
            }
            if count * 2 >= 1.max((right - left) * (bottom - top)) {
                let bit = ny * 12 + nx;
                words[bit / 64] |= 1 << (bit % 64);
            }
        }
    }
    words
}
fn rendered_glyph(source: &[bool], w: usize, h: usize) -> char {
    let words = normalized_words(source, w, h);
    let aspect = (w as f64 / h as f64).ln();
    let mut errors = [f64::MAX; 10];
    for (digit, template) in &*RENDERED {
        let mut mismatch = 0;
        let mut union = 0;
        for i in 0..4 {
            mismatch += (words[i] ^ template[i]).count_ones();
            union += (words[i] | template[i]).count_ones();
        }
        let error = mismatch as f64 / union.max(1) as f64 * 0.90
            + (aspect - (12.0_f64 / 18.0).ln()).abs().min(1.0) * 0.10;
        errors[*digit] = errors[*digit].min(error);
    }
    let (mut best, mut runner, mut digit) = (f64::MAX, f64::MAX, 0);
    for (i, error) in errors.into_iter().enumerate() {
        if error < best {
            runner = best;
            best = error;
            digit = i;
        } else if error < runner {
            runner = error;
        }
    }
    if (best <= 0.34 && runner - best >= 0.02) || (best <= 0.42 && runner - best >= 0.10) {
        (b'0' + digit as u8) as char
    } else {
        '?'
    }
}
fn recognize_glyph(source: &[bool], w: usize, h: usize, allow_topology: bool) -> char {
    let runtime = rendered_glyph(source, w, h);
    if runtime != '?' {
        return runtime;
    }
    let (mut best, mut runner, mut zero, mut digit) = (f64::MAX, f64::MAX, f64::MAX, 0);
    for (i, templates) in FIXED.iter().enumerate() {
        let mut error = f64::MAX;
        for rows in *templates {
            let mut mismatch = 0;
            for (y, row) in rows.iter().enumerate() {
                for x in 0..5 {
                    let sx = ((x * w + w / 2) / 5).min(w - 1);
                    let sy = ((y * h + h / 2) / 7).min(h - 1);
                    mismatch += usize::from(source[sy * w + sx] != (row.as_bytes()[x] == b'#'));
                }
            }
            error = error.min(mismatch as f64 / 35.0);
        }
        if i == 0 {
            zero = error;
        }
        if error < best {
            runner = best;
            best = error;
            digit = i;
        } else if error < runner {
            runner = error;
        }
    }
    if best > 0.34 {
        return '?';
    }
    if runner - best >= 0.06 {
        return (b'0' + digit as u8) as char;
    }
    if !allow_topology {
        return '?';
    }
    if zero <= 0.34 && zero - best <= 0.06 && topology(source, w, h, 0) {
        return '0';
    }
    if (digit == 8 || digit == 9) && topology(source, w, h, digit) {
        (b'0' + digit as u8) as char
    } else {
        '?'
    }
}
struct Counter {
    count: usize,
    y_total: usize,
    left: usize,
    right: usize,
    top: usize,
    bottom: usize,
}
impl Counter {
    fn center(&self) -> usize {
        self.y_total / self.count
    }
    fn large(&self, w: usize, h: usize) -> bool {
        self.count >= 2.max(w * h / 40)
            && self.right - self.left + 1 >= 1.max(w / 4)
            && self.bottom - self.top + 1 >= 1.max(h / 7)
    }
}
fn enclosed_counters(foreground: &[bool], w: usize, h: usize) -> Vec<Counter> {
    let mut seen = vec![false; foreground.len()];
    let mut queue = Vec::with_capacity(foreground.len());
    let mut counters = vec![];
    for start in 0..foreground.len() {
        if foreground[start] || seen[start] {
            continue;
        }
        queue.clear();
        queue.push(start);
        seen[start] = true;
        let mut c = Counter {
            count: 0,
            y_total: 0,
            left: w,
            right: 0,
            top: h,
            bottom: 0,
        };
        let mut head = 0;
        let mut exterior = false;
        while head < queue.len() {
            let current = queue[head];
            head += 1;
            let (x, y) = (current % w, current / w);
            c.count += 1;
            c.y_total += y;
            c.left = c.left.min(x);
            c.right = c.right.max(x);
            c.top = c.top.min(y);
            c.bottom = c.bottom.max(y);
            exterior |= x == 0 || x == w - 1 || y == 0 || y == h - 1;
            let neighbors = [
                x.checked_sub(1).map(|_| current - 1),
                (x + 1 < w).then_some(current + 1),
                y.checked_sub(1).map(|_| current - w),
                (y + 1 < h).then_some(current + w),
            ];
            for next in neighbors.into_iter().flatten() {
                if !foreground[next] && !seen[next] {
                    seen[next] = true;
                    queue.push(next);
                }
            }
        }
        if !exterior {
            counters.push(c);
        }
    }
    counters
}
fn topology(f: &[bool], w: usize, h: usize, digit: usize) -> bool {
    if w < 5 || h < 7 || w * 100 < h * 45 || w * 100 > h * 140 {
        return false;
    }
    let (
        mut total,
        mut top,
        mut middle,
        mut bottom,
        mut mid_l,
        mut mid_r,
        mut upper_l,
        mut upper_r,
        mut lower_l,
        mut lower_r,
    ) = (0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    for y in 0..h {
        let mut row = 0;
        for x in 0..w {
            if !f[y * w + x] {
                continue;
            }
            total += 1;
            row += 1;
            if y >= h / 3 && y <= h * 2 / 3 {
                if x < w / 2 {
                    mid_l += 1;
                } else {
                    mid_r += 1;
                }
            }
            if y < h * 2 / 3 {
                if x < w / 2 {
                    upper_l += 1;
                } else {
                    upper_r += 1;
                }
            } else if x < w / 2 {
                lower_l += 1;
            } else {
                lower_r += 1;
            }
        }
        if y < 1.max(h / 3) {
            top = top.max(row);
        }
        if y >= h / 3 && y <= h * 2 / 3 {
            middle = middle.max(row);
        }
        if y >= h * 2 / 3 {
            bottom = bottom.max(row);
        }
    }
    if total * 100 < w * h * 25 || total * 100 > w * h * if digit == 0 { 65 } else { 75 } {
        return false;
    }
    let counters = enclosed_counters(f, w, h);
    match digit {
        0 => {
            counters.len() == 1
                && {
                    let c = &counters[0];
                    let center = c.center();
                    c.count >= 4.max(w * h / 40)
                        && c.right - c.left + 1 >= 2.max(w.div_ceil(4))
                        && c.bottom - c.top + 1 >= 3.max(h.div_ceil(4))
                        && center * 100 >= h * 25
                        && center * 100 <= h * 75
                }
                && top * 100 >= w * 35
                && bottom * 100 >= w * 35
                && middle * 100 <= w * 60
                && mid_l >= 2
                && mid_r >= 2
                && lower_l >= 2
                && lower_r >= 2
                && lower_l <= lower_r * 2
                && lower_r <= lower_l * 2
        }
        8 => {
            middle * 100 >= w * 55
                && top * 100 >= w * 45
                && bottom * 100 >= w * 45
                && counters.len() == 2
                && counters.iter().all(|c| c.large(w, h))
                && {
                    let a = counters[0].center().min(counters[1].center());
                    let b = counters[0].center().max(counters[1].center());
                    a < h / 2 && b >= h / 2 && b - a >= 2.max(h / 4)
                }
        }
        9 => {
            top * 100 >= w * 45
                && middle * 100 >= w * 55
                && upper_l >= 3
                && upper_r >= 3
                && lower_r >= 3
                && lower_r >= lower_l * 2
                && counters.len() == 1
                && counters[0].large(w, h)
                && counters[0].center() * 100 < h * 55
        }
        _ => false,
    }
}

const FIXED: [&[[&str; 7]]; 10] = [
    &[
        [
            ".###.", "##.##", "##.##", "##.##", "##.##", "##.##", ".###.",
        ],
        [
            ".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###.",
        ],
    ],
    &[
        [
            "..##.", ".###.", "..##.", "..##.", "..##.", "..##.", ".####",
        ],
        [
            "..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###.",
        ],
    ],
    &[
        [
            ".###.", "##.##", "...##", "..##.", ".##..", "##...", "#####",
        ],
        [
            ".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####",
        ],
    ],
    &[
        [
            "####.", "...##", "...##", ".###.", "...##", "...##", "####.",
        ],
        [
            "####.", "....#", "....#", ".###.", "....#", "....#", "####.",
        ],
        [
            "..##.", ".#.##", "....#", "..##.", "....#", "#...#", ".###.",
        ],
    ],
    &[
        [
            "...##", "..###", ".#.##", "##.##", "#####", "...##", "...##",
        ],
        [
            "...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#.",
        ],
    ],
    &[
        [
            "#####", "##...", "##...", "####.", "...##", "...##", "####.",
        ],
        [
            "#####", "#....", "#....", "####.", "....#", "....#", "####.",
        ],
    ],
    &[
        [
            ".###.", "##...", "##...", "####.", "##.##", "##.##", ".###.",
        ],
        [
            ".###.", "#....", "#....", "####.", "#...#", "#...#", ".###.",
        ],
    ],
    &[
        [
            "#####", "...##", "..##.", "..##.", ".##..", ".##..", ".##..",
        ],
        [
            "#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#...",
        ],
    ],
    &[
        [
            ".###.", "##.##", "##.##", ".###.", "##.##", "##.##", ".###.",
        ],
        [
            ".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###.",
        ],
        [
            "..##.", ".####", ".#..#", ".###.", ".#..#", ".#..#", ".####",
        ],
    ],
    &[
        [
            ".###.", "##.##", "##.##", ".####", "...##", "...##", ".###.",
        ],
        [
            ".###.", "#...#", "#...#", ".####", "....#", "....#", ".###.",
        ],
        [
            ".##..", ".###.", "#...#", "#...#", ".####", "...#.", ".###.",
        ],
    ],
];
const ENCODED: &[(usize, &str)] = &[
    (
        0,
        "f070c030cf909fc0bfc03fc03fc03fc03fc03fc03fc03fc03fc0bfc09fc0cf90c030f070",
    ),
    (
        1,
        "00000000fc00fc00fc00fc00fc00fc00fc00fc00fc00fc00fc00fc00fc00fc00fc00fc00",
    ),
    (
        2,
        "f8f0e030cf90cf90dfd0ffd0ff90ff90ff30fc30fc70fcf0f9f0e3f0c3f0c7f080000000",
    ),
    (
        3,
        "80108030fe70fe70fcf0f1f0f0f0e030fe30ff90ffd0ffc0ffc0ffd07f903f308070e0f0",
    ),
    (
        4,
        "fc70fcf0fcf0f9f0f9f0f3f0f3f0e3f0e7f0cfc0cfc0cfc09fc00000ffc0ffc0ffc0ffc0",
    ),
    (
        5,
        "c010c010cff0cff0cff0c0f0c030c030ff90ffc0ffc0ffc0ffc0ffc03fd09f90c030e0f0",
    ),
    (
        6,
        "fcf0fcf0f1f0f3f0f3f0e0f0c03086308f10bfd03fc03fc03fc0bfd09f908f30e070f0f0",
    ),
    (
        7,
        "00000010ffb0ff30ff30fe30fe70fe70fcf0f9f0f9f0f9f0f1f0f3f0f7f0e7f0cff0cff0",
    ),
    (
        8,
        "f0f0e030cf30cfb0dfb0cf30c630e030c0309f90bfc03fc03fc03fc09fc08f90c030f0f0",
    ),
    (
        9,
        "f070c0308f909fc0bfc03fc03fc03fc09fc0c710c030f030fe30fc70fcf0f9f0f3f0e3f0",
    ),
    (
        0,
        "e070c0309f901f801f801f801f801f801f801f801f801f801f801f801f809f90c030e070",
    ),
    (
        1,
        "ffc0fc0000000e00fe00fe00fe00fe00fe00fe00fe00fe00fe00fe00fe00fe00fe00fe00",
    ),
    (
        2,
        "e0f0c0301f303f903f903f90ffb0ff30fe30fc70fcf0f3f0e3f0c7f0cff08ff000000000",
    ),
    (
        3,
        "e070c0301f901f903f80ff80ff90e030e070e030ff10ff80ff803f801f901f10c070e070",
    ),
    (
        4,
        "fe30fe30fc30f830f030f230f230e630ce308e308e301e3000000000fe30fe30fe30fe30",
    ),
    (
        5,
        "801080109ff09ff09ff09ff0807000309f10ff90ff80ff80ff803f801f901f10c070e070",
    ),
    (
        6,
        "fc70f070e7f0cff09ff09ff0907007101f801f801f801fc01fc01f809f808f80e030f070",
    ),
    (
        7,
        "00000000ff90ff30ff30ff30fe30fe70fef0fcf0fcf0f9f0f1f0f1f0f3f0f3f0e7f0cff0",
    ),
    (
        8,
        "e070c0309f901f801f801f809f90c030e070c0309f901f801f801f801f801f80c030e070",
    ),
    (
        9,
        "e0f0c0701f101f901f903f803f801f801f808f008000ff90ff90ff90ff30fe30e0f0e3f0",
    ),
    (
        6,
        "f030e070e0708ff08ff08ff0003002001f801f801f801f801f801f808f8080308030e070",
    ),
    (
        6,
        "f830e030c1f0cff08ff09030801087001f801f801fc01fc01fc09fc08fc08f80c010e030",
    ),
    (
        6,
        "f830f030c3f08ff00ff038f000100f103fc03fc03fc03fc03fc03fc03fc08f108010f0f0",
    ),
    (
        6,
        "f8f0f1f0f1f0f7f0e7f0e7f080f00e100e101f107f807f807f801f101f108010e0f0e0f0",
    ),
    (
        6,
        "fc30f030c7f08ff0bff038f000100f103fc03fc03fc03fc03fc03fc03fc08f10c010f0f0",
    ),
    (
        6,
        "fc70e1f0e1f08ff09ff09ff000300e301f801f801fc01fc01fc01fc09f8082308230e070",
    ),
    (
        6,
        "fc70e1f0e1f08ff09ff09ff090700e301f801f801fc01fc01fc01fc09f808e308e30f070",
    ),
    (
        6,
        "fc70f070c3f0cff09ff09ff0907000300f101f903f903f803f801f909f908f10c030e070",
    ),
    (
        6,
        "fc70f1f0f1f0f3f0e3f0e3f080708e008e001fc07fc07fc07fc01fc01fc08e30e070e070",
    ),
    (
        6,
        "fc70fdf0fdf0f3f0eff0eff080708fb08fb07fc07fc07fc07fc01fc01fc08e30e070e070",
    ),
    (
        6,
        "fcf0f8f0f1f0f3f0e7f0c0f0c0308f109f909f903f803fc03fc03f801f908f10c030e0f0",
    ),
    (
        6,
        "fcf0f9f0f9f0f3f0e7f0e0f0c0308f109f909f903fd03fc03fc03fd01f908f30c030e0f0",
    ),
    (
        6,
        "fcf0fcf0f1f0f1f0f3f0e0f0c03080308f10bfc03fc03fc03fc09fc09f908f10c030f0f0",
    ),
    (
        6,
        "fcf0fdf0f9f0f3f0f3f0e1f0c070ce308f30bfd0bfc03fc03fc0bfd09f908f30e070f1f0",
    ),
    (
        6,
        "fe70f070e7f0cff0dff09ff0987007101f901f801f801fc01fc09f809f808f90e030f070",
    ),
    (
        6,
        "fe70f8f0f9f0f1f0e7f0e070c0309f90bfc0bfc03fe03fe03fe03fe09fc09f90c030f070",
    ),
];
static RENDERED: LazyLock<Vec<(usize, [u64; 4])>> = LazyLock::new(|| {
    ENCODED
        .iter()
        .map(|(digit, rows)| {
            let mut pixels = vec![];
            for y in 0..18 {
                let row =
                    u16::from_str_radix(&rows[y * 4..y * 4 + 4], 16).expect("fixed glyph mask");
                for x in 0..12 {
                    pixels.push((row >> (15 - x)) & 1 == 0);
                }
            }
            (*digit, normalized_words(&pixels, 12, 18))
        })
        .collect()
});
