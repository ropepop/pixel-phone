//! Fixed-size local action proof. Every detector retains its original fail-closed thresholds.
use crate::visual_date::{self, DateRange};

const W: i32 = 192;
const H: i32 = 288;
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Bounds {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}
impl Bounds {
    fn new(left: i32, top: i32, right: i32, bottom: i32) -> Self {
        Self {
            left,
            top,
            right,
            bottom,
        }
    }
    fn width(self) -> i32 {
        self.right - self.left
    }
    fn height(self) -> i32 {
        self.bottom - self.top
    }
    fn area(self) -> i32 {
        self.width() * self.height()
    }
}
struct Component {
    bounds: Bounds,
    points: Vec<usize>,
}
#[derive(Clone, Copy)]
struct Glyph {
    pixels: i32,
    bounds: Bounds,
}
impl Glyph {
    fn empty() -> Self {
        Self {
            pixels: 0,
            bounds: Bounds::new(W, H, -1, -1),
        }
    }
    fn add(&mut self, x: i32, y: i32) {
        self.pixels += 1;
        self.bounds.left = self.bounds.left.min(x);
        self.bounds.top = self.bounds.top.min(y);
        self.bounds.right = self.bounds.right.max(x + 1);
        self.bounds.bottom = self.bounds.bottom.max(y + 1);
    }
    fn present(self) -> bool {
        self.bounds.width() > 0 && self.bounds.height() > 0
    }
}
fn pixel(p: &[i32], x: i32, y: i32) -> i32 {
    p[(y * W + x) as usize]
}
fn luminance(p: i32) -> i32 {
    (((p >> 16) & 255) * 54 + ((p >> 8) & 255) * 183 + (p & 255) * 19) >> 8
}
fn saturation(p: i32) -> i32 {
    let (r, g, b) = ((p >> 16) & 255, (p >> 8) & 255, p & 255);
    r.max(g).max(b) - r.min(g).min(b)
}
fn registration(p: i32) -> bool {
    let (r, g, b) = ((p >> 16) & 255, (p >> 8) & 255, p & 255);
    r >= 175 && (95..=238).contains(&g) && b <= 110 && r - g >= 22
}
fn header_color(p: i32) -> bool {
    let (r, g, b) = ((p >> 16) & 255, (p >> 8) & 255, p & 255);
    registration(p)
        || (r >= 165 && (40..=150).contains(&g) && b <= 120 && r - g >= 50 && r - b >= 50)
}
fn neutral(p: i32, background: i32) -> bool {
    saturation(p) <= 64 && (luminance(p) - background).abs() >= 28
}
fn median(p: &[i32], width: i32, region: Bounds) -> i32 {
    let mut values = Vec::with_capacity(region.area() as usize);
    for y in region.top..region.bottom {
        for x in region.left..region.right {
            values.push(luminance(p[(y * width + x) as usize]));
        }
    }
    values.sort_unstable();
    values[values.len() / 2]
}
pub(crate) fn downsample(p: &[i32], sw: i32, sh: i32, w: i32, h: i32) -> Vec<i32> {
    let mut out = Vec::with_capacity((w * h) as usize);
    for y in 0..h {
        for x in 0..w {
            out.push(p[((y * sh / h).min(sh - 1) * sw + (x * sw / w).min(sw - 1)) as usize]);
        }
    }
    out
}
pub(crate) fn geometry(p: &[i32]) -> Option<Vec<i32>> {
    match p.len() {
        55296 => Some(p.to_vec()),
        221184 => Some(downsample(p, 384, 576, W, H)),
        _ => None,
    }
}
fn components(f: &[bool], search: Bounds, diagonal: bool) -> Vec<Component> {
    let mut visited = vec![false; f.len()];
    let mut result = vec![];
    for y in search.top..search.bottom {
        for x in search.left..search.right {
            let seed = (y * W + x) as usize;
            if !f[seed] || visited[seed] {
                continue;
            }
            let mut points = vec![seed];
            visited[seed] = true;
            let mut bounds = Bounds::new(x, y, x + 1, y + 1);
            let mut head = 0;
            while head < points.len() {
                let point = points[head];
                head += 1;
                let (px, py) = (point as i32 % W, point as i32 / W);
                bounds.left = bounds.left.min(px);
                bounds.top = bounds.top.min(py);
                bounds.right = bounds.right.max(px + 1);
                bounds.bottom = bounds.bottom.max(py + 1);
                for dy in -1..=1 {
                    for dx in -1..=1 {
                        if (dx == 0 && dy == 0) || (!diagonal && dx != 0 && dy != 0) {
                            continue;
                        }
                        let (nx, ny) = (px + dx, py + dy);
                        if nx < search.left
                            || nx >= search.right
                            || ny < search.top
                            || ny >= search.bottom
                        {
                            continue;
                        }
                        let next = (ny * W + nx) as usize;
                        if f[next] && !visited[next] {
                            visited[next] = true;
                            points.push(next);
                        }
                    }
                }
            }
            result.push(Component { bounds, points });
        }
    }
    result
}
fn navigation_background(p: &[i32]) -> i32 {
    median(p, W, Bounds::new(0, 262, W, 284))
}
fn navigation_glyph(p: &[i32], bg: i32, top: i32, left: i32, right: i32, selected: bool) -> Glyph {
    let mut stats = Glyph::empty();
    for y in top..283 {
        for x in left..right {
            if if selected {
                registration(pixel(p, x, y))
            } else {
                neutral(pixel(p, x, y), bg)
            } {
                stats.add(x, y);
            }
        }
    }
    stats
}
fn selected_pixels(p: &[i32], top: i32, left: i32, right: i32) -> i32 {
    let mut count = 0;
    for y in top..283 {
        for x in left..right {
            count += i32::from(registration(pixel(p, x, y)));
        }
    }
    count
}
fn valid_home(v: Glyph) -> bool {
    let b = v.bounds;
    v.present()
        && (6..=96).contains(&v.pixels)
        && (4..=24).contains(&b.width())
        && (3..=20).contains(&b.height())
        && b.area() >= 20
        && v.pixels * 100 <= b.area() * 65
}
fn valid_ticket(v: Glyph) -> bool {
    let b = v.bounds;
    v.present()
        && (12..=180).contains(&v.pixels)
        && (14..=30).contains(&b.width())
        && (5..=14).contains(&b.height())
        && b.area() >= 70
        && v.pixels * 100 >= b.area() * 8
        && v.pixels * 100 <= b.area() * 70
}
fn valid_profile(v: Glyph) -> bool {
    let b = v.bounds;
    v.present()
        && (10..=180).contains(&v.pixels)
        && (7..=24).contains(&b.width())
        && (7..=22).contains(&b.height())
}
fn longest_run(p: &[i32], y: i32, left: i32, right: i32, test: impl Fn(i32) -> bool) -> (i32, i32) {
    let (mut run, mut longest, mut start, mut longest_start) = (0, 0, -1, -1);
    for x in left..right {
        if test(pixel(p, x, y)) {
            if run == 0 {
                start = x;
            }
            run += 1;
            if run > longest {
                longest = run;
                longest_start = start;
            }
        } else {
            run = 0;
            start = -1;
        }
    }
    (longest, longest_start)
}
fn valid_menu(p: &[i32], v: Glyph, bg: i32, top: i32, selected: bool) -> bool {
    let rows = (top..283)
        .filter(|y| {
            longest_run(p, *y, 152, 186, |px| {
                if selected {
                    registration(px)
                } else {
                    neutral(px, bg)
                }
            })
            .0 >= 10
        })
        .count();
    v.present()
        && (2..=8).contains(&rows)
        && v.pixels >= 20
        && (10..=30).contains(&v.bounds.width())
}
fn neutral_profile_and_menu(p: &[i32], bg: i32, top: i32) -> bool {
    if selected_pixels(p, top, 106, 140) + selected_pixels(p, top, 152, 186) >= 4 {
        return false;
    }
    valid_profile(navigation_glyph(p, bg, top, 106, 140, false))
        && valid_menu(
            p,
            navigation_glyph(p, bg, top, 152, 186, false),
            bg,
            top,
            false,
        )
}
fn home(p: &[i32]) -> Option<Bounds> {
    let bg = navigation_background(p);
    if !valid_home(navigation_glyph(p, bg, 258, 8, 38, true)) {
        return None;
    }
    let search = Bounds::new(46, 262, 82, 283);
    let mut f = vec![false; (W * H) as usize];
    let mut selected = 0;
    for y in search.top..search.bottom {
        for x in search.left..search.right {
            let px = pixel(p, x, y);
            selected += i32::from(registration(px));
            f[(y * W + x) as usize] = neutral(px, bg);
        }
    }
    if selected >= 4 {
        return None;
    }
    let components: Vec<_> = components(&f, search, false)
        .into_iter()
        .filter(|c| c.points.len() >= 2)
        .collect();
    if components.is_empty() || components.len() > 6 {
        return None;
    }
    let mut glyph = Glyph::empty();
    let mut rows = [0; 21];
    let mut columns = [0; 36];
    for c in components {
        for point in c.points {
            let (x, y) = (point as i32 % W, point as i32 / W);
            glyph.add(x, y);
            rows[(y - 262) as usize] += 1;
            columns[(x - 46) as usize] += 1;
        }
    }
    let b = glyph.bounds;
    if !glyph.present()
        || glyph.pixels < 12
        || !(14..=30).contains(&b.width())
        || !(5..=14).contains(&b.height())
        || b.width() * 100 < b.height() * 160
        || b.width() * 100 > b.height() * 500
        || glyph.pixels * 100 < b.area() * 8
        || glyph.pixels * 100 > b.area() * 65
    {
        return None;
    }
    let active_rows = rows.iter().filter(|c| **c > 0).count();
    let (mut active_columns, mut left_ink, mut right_ink) = (0, 0, 0);
    for x in b.left..b.right {
        let count = columns[(x - 46) as usize];
        active_columns += i32::from(count > 0);
        if x < b.left + 1.max(b.width() / 3) {
            left_ink += count;
        }
        if x >= b.right - 1.max(b.width() / 3) {
            right_ink += count;
        }
    }
    if active_rows < 5
        || active_columns < 4
        || left_ink < 3
        || right_ink < 3
        || !neutral_profile_and_menu(p, bg, 262)
    {
        None
    } else {
        Some(b)
    }
}
fn profile(p: &[i32]) -> bool {
    let bg = navigation_background(p);
    let selected = navigation_glyph(p, bg, 262, 106, 140, true);
    let b = selected.bounds;
    if !selected.present()
        || !(8..=180).contains(&selected.pixels)
        || !(7..=24).contains(&b.width())
        || !(7..=22).contains(&b.height())
        || b.area() < 49
        || selected.pixels * 100 > b.area() * 70
    {
        return false;
    }
    if selected_pixels(p, 262, 8, 40)
        + selected_pixels(p, 262, 46, 84)
        + selected_pixels(p, 262, 152, 186)
        >= 4
        || !valid_home(navigation_glyph(p, bg, 262, 8, 40, false))
    {
        return false;
    }
    let t = navigation_glyph(p, bg, 262, 46, 84, false);
    let b = t.bounds;
    if !t.present()
        || !(12..=180).contains(&t.pixels)
        || !(14..=30).contains(&b.width())
        || !(5..=14).contains(&b.height())
        || b.area() < 70
        || t.pixels * 100 < b.area() * 8
        || t.pixels * 100 > b.area() * 65
    {
        return false;
    }
    valid_menu(
        p,
        navigation_glyph(p, bg, 262, 152, 186, false),
        bg,
        262,
        false,
    )
}
fn other_tab(p: &[i32], tickets: bool) -> bool {
    let bg = navigation_background(p);
    if !valid_home(navigation_glyph(p, bg, 262, 8, 40, false))
        || !valid_ticket(navigation_glyph(p, bg, 262, 46, 84, tickets))
        || !valid_profile(navigation_glyph(p, bg, 262, 106, 140, false))
        || !valid_menu(
            p,
            navigation_glyph(p, bg, 262, 152, 186, !tickets),
            bg,
            262,
            !tickets,
        )
    {
        return false;
    }
    selected_pixels(p, 262, 8, 40)
        + selected_pixels(p, 262, 106, 140)
        + if tickets {
            selected_pixels(p, 262, 152, 186)
        } else {
            selected_pixels(p, 262, 46, 84)
        }
        < 4
}
pub(crate) fn selected_navigation(p: &[i32]) -> &'static str {
    let Some(p) = geometry(p) else {
        return "";
    };
    if home(&p).is_some() {
        "home"
    } else if profile(&p) {
        "profile"
    } else if other_tab(&p, false) {
        "menu"
    } else if other_tab(&p, true) {
        "tickets"
    } else {
        ""
    }
}
fn strong_list_chrome(p: &[i32]) -> bool {
    let Some(underline) =
        (32..=40).find(|y| (10..=112).contains(&longest_run(p, *y, 5, W - 5, registration).0))
    else {
        return false;
    };
    if ((underline + 1).max(39)..=44)
        .filter(|y| longest_run(p, *y, 5, W - 5, registration).0 < 8)
        .count()
        < 2
    {
        return false;
    }
    let runs: Vec<_> = (44..=64)
        .map(|y| longest_run(p, y, 5, W - 5, header_color).0)
        .collect();
    runs.iter().filter(|run| **run >= 80).count() >= 6
        && runs.iter().copied().max().unwrap_or(0) >= 120
}
fn yellow_bands(p: &[i32]) -> Vec<Bounds> {
    let mut values = vec![];
    let (mut start, mut left, mut right) = (-1, W, -1);
    for y in 40..H - 28 {
        let (mut row_left, mut row_right, mut yellow) = (W, -1, 0);
        for x in 5..W - 5 {
            if registration(pixel(p, x, y)) {
                yellow += 1;
                row_left = row_left.min(x);
                row_right = row_right.max(x);
            }
        }
        if yellow >= W / 3 {
            if start < 0 {
                start = y;
            }
            left = left.min(row_left);
            right = right.max(row_right);
        } else if start >= 0 {
            if y - start >= 4 {
                values.push(Bounds::new(left, start, right + 1, y));
            }
            start = -1;
            left = W;
            right = -1;
        }
    }
    values
}
fn looks_like_login(p: &[i32]) -> bool {
    let (mut white, mut blue) = (0, 0);
    for y in H / 3..H * 4 / 5 {
        for x in W / 8..W * 7 / 8 {
            let px = pixel(p, x, y);
            let (r, g, b) = ((px >> 16) & 255, (px >> 8) & 255, px & 255);
            white += i32::from(r > 220 && g > 220 && b > 220);
            blue += i32::from(b > 125 && b - r > 30 && b - g > 15);
        }
    }
    if white > 2000 && blue > 300 {
        return true;
    }
    let (mut logo, mut logo_rows) = (0, 0);
    for y in 55..102 {
        let row = (38..158).filter(|x| registration(pixel(p, *x, y))).count() as i32;
        logo += row;
        logo_rows += i32::from(row >= 10);
    }
    let (mut form, mut orange) = (0, 0);
    for y in 90..175 {
        for x in 10..182 {
            let px = pixel(p, x, y);
            form += i32::from(saturation(px) <= 48 && (20..=100).contains(&luminance(px)));
            orange += i32::from(registration(px));
        }
    }
    let (mut bottom, mut wide) = (0, 0);
    for y in 240..283 {
        let row = (0..W).filter(|x| registration(pixel(p, *x, y))).count() as i32;
        bottom += row;
        wide += i32::from(row >= 180);
    }
    (400..=2000).contains(&logo)
        && logo_rows >= 10
        && form >= 11000
        && orange <= 200
        && bottom >= 3200
        && wide >= 16
}

struct Tabs {
    single: Option<Bounds>,
    time: Option<Bounds>,
    time_glyph: Bounds,
    time_selected: bool,
}
fn tickets_tabs(p: &[i32], require_empty: bool) -> Option<Tabs> {
    let bg = if require_empty {
        median(p, W, Bounds::new(0, 218, W, 242))
    } else {
        navigation_background(p)
    };
    let (mut single_rows, mut time_rows) = (0, 0);
    for y in 33..=40 {
        let (run, start) = longest_run(p, y, 5, 100, registration);
        if (60..=95).contains(&run)
            && (8..=18).contains(&start)
            && (90..=102).contains(&(start + run))
        {
            single_rows += 1;
        }
        let (run, start) = longest_run(p, y, 90, W - 5, registration);
        if (60..=95).contains(&run)
            && (90..=110).contains(&start)
            && (175..=187).contains(&(start + run))
        {
            time_rows += 1;
        }
    }
    let single = (1..=4).contains(&single_rows);
    let time = (1..=4).contains(&time_rows);
    if single == time {
        return None;
    }
    let (mut l, mut t) = (Glyph::empty(), Glyph::empty());
    for y in 18..34 {
        for x in 10..184 {
            let px = pixel(p, x, y);
            if saturation(px) <= 64 && (luminance(px) - bg).abs() >= 24 {
                if x < 100 {
                    l.add(x, y);
                } else {
                    t.add(x, y);
                }
            }
        }
    }
    if !l.present()
        || !t.present()
        || !(70..=420).contains(&l.pixels)
        || !(50..=82).contains(&l.bounds.width())
        || !(3..=9).contains(&l.bounds.height())
        || !(40..=300).contains(&t.pixels)
        || !(30..=72).contains(&t.bounds.width())
        || !(3..=9).contains(&t.bounds.height())
    {
        return None;
    }
    if require_empty {
        let mut empty = Glyph::empty();
        let mut rows = [false; 45];
        let mut columns = [false; 146];
        for y in 125..170 {
            for x in 24..170 {
                let px = pixel(p, x, y);
                if saturation(px) <= 64 && (luminance(px) - bg).abs() >= 24 {
                    empty.add(x, y);
                    rows[(y - 125) as usize] = true;
                    columns[(x - 24) as usize] = true;
                }
            }
        }
        let b = empty.bounds;
        if !empty.present()
            || !(220..=900).contains(&empty.pixels)
            || !(100..=145).contains(&b.width())
            || !(8..=20).contains(&b.height())
            || rows.iter().filter(|v| **v).count() < 8
            || columns.iter().filter(|v| **v).count() < 80
            || empty.pixels * 100 < b.area() * 12
            || empty.pixels * 100 > b.area() * 65
        {
            return None;
        }
        let mut unexpected = 0;
        for y in 45..255 {
            for x in 5..W - 5 {
                unexpected += i32::from(registration(pixel(p, x, y)));
            }
        }
        if unexpected > 8 {
            return None;
        }
    }
    let (mut separator_rows, mut separator) = (0, -1);
    for y in 255..=262 {
        let count = (2..W - 2)
            .filter(|x| {
                let px = pixel(p, *x, y);
                saturation(px) <= 64 && (luminance(px) - bg).abs() >= 24
            })
            .count() as i32;
        if count >= W * 3 / 4 {
            separator_rows += 1;
            separator = y;
        }
    }
    if separator_rows > 4 {
        return None;
    }
    let proved = separator_rows >= 1 && separator >= 0;
    let top = if proved { 262.max(separator + 2) } else { 262 };
    let (mut tickets, mut home, mut home_neutral, mut other) = (0, 0, 0, 0);
    for y in top..280 {
        for x in 8..40 {
            let px = pixel(p, x, y);
            home += i32::from(registration(px));
            home_neutral += i32::from(neutral(px, bg));
        }
        for x in 46..84 {
            tickets += i32::from(registration(pixel(p, x, y)));
        }
        for x in 108..184 {
            other += i32::from(registration(pixel(p, x, y)));
        }
    }
    if !(8..=120).contains(&tickets)
        || home > 4
        || home_neutral < 12
        || other > 4
        || (!proved && !neutral_profile_and_menu(p, bg, top))
    {
        return None;
    }
    Some(Tabs {
        single: time.then_some(Bounds::new(
            10.max(l.bounds.left - 4),
            18.max(l.bounds.top - 4),
            100.min(l.bounds.right + 4),
            34.min(l.bounds.bottom + 4),
        )),
        time: (!time).then_some(Bounds::new(
            100.max(t.bounds.left - 4),
            18.max(t.bounds.top - 4),
            184.min(t.bounds.right + 4),
            34.min(t.bounds.bottom + 4),
        )),
        time_glyph: t.bounds,
        time_selected: time,
    })
}
fn legacy_control_button(p: &[i32]) -> Option<Bounds> {
    let (mut start, mut left, mut right) = (-1, W, -1);
    for y in 8..60 {
        let (mut row_left, mut row_right, mut colored) = (W, -1, 0);
        for x in 4..W / 2 {
            if registration(pixel(p, x, y)) {
                colored += 1;
                row_left = row_left.min(x);
                row_right = row_right.max(x);
            }
        }
        if colored >= 12 {
            if start < 0 {
                start = y;
            }
            left = left.min(row_left);
            right = right.max(row_right);
        } else if start >= 0 {
            if y - start >= 3 && right - left >= 12 {
                return Some(Bounds::new(left, start, right + 1, y));
            }
            start = -1;
            left = W;
            right = -1;
        }
    }
    None
}
fn logo_button(p: &[i32]) -> Option<Bounds> {
    let search = Bounds::new(4, 2, W / 2, 22);
    let bg = median(p, W, search);
    let mut foreground = vec![false; (W * H) as usize];
    for y in search.top..search.bottom {
        for x in search.left..search.right {
            let px = pixel(p, x, y);
            foreground[(y * W + x) as usize] =
                saturation(px) <= 92 && (luminance(px) - bg).abs() >= 28;
        }
    }
    let components: Vec<_> = components(&foreground, search, true)
        .into_iter()
        .filter(|c| c.bounds.width() >= 2 && c.bounds.height() >= 4 && c.points.len() >= 6)
        .collect();
    if components.is_empty() || components.len() > 8 {
        return None;
    }
    let mut glyph = Glyph::empty();
    let mut rows = [0; 20];
    let mut columns = [0; 92];
    for c in components {
        for point in c.points {
            let (x, y) = (point as i32 % W, point as i32 / W);
            glyph.add(x, y);
            rows[(y - 2) as usize] += 1;
            columns[(x - 4) as usize] += 1;
        }
    }
    let b = glyph.bounds;
    if !glyph.present()
        || !(14..=72).contains(&b.width())
        || !(4..=18).contains(&b.height())
        || b.left > 30
        || b.top > 14
        || b.right > 90
        || b.bottom > 22
        || b.width() * 100 < b.height() * 170
        || b.width() * 100 > b.height() * 800
        || glyph.pixels < 14
        || glyph.pixels * 100 < b.area() * 7
        || glyph.pixels * 100 > b.area() * 75
    {
        return None;
    }
    if (rows.iter().filter(|v| **v > 0).count() as i32) * 3 < b.height() * 2
        || (columns.iter().filter(|v| **v > 0).count() as i32) * 2 < b.width()
    {
        return None;
    }
    let mut quartiles = [0; 4];
    for x in b.left..b.right {
        quartiles[((x - b.left) * 4 / 1.max(b.width())).min(3) as usize] +=
            columns[(x - 4) as usize];
    }
    if quartiles.iter().any(|v| *v < 3.max(glyph.pixels / 24)) {
        return None;
    }
    Some(Bounds::new(
        search.left.max(b.left - 4),
        search.top.max(b.top - 4),
        search.right.min(b.right + 4),
        search.bottom.min(b.bottom + 4),
    ))
}
fn control_button(p: &[i32]) -> Option<Bounds> {
    legacy_control_button(p).or_else(|| logo_button(p))
}
fn close_x(c: &Component) -> bool {
    let b = c.bounds;
    let (w, h, count) = (b.width(), b.height(), c.points.len() as i32);
    if !(5..=19).contains(&w)
        || !(5..=19).contains(&h)
        || (w - h).abs() > 4
        || count < 7
        || count * 100 > w * h * 58
    {
        return false;
    }
    let (cx, cy) = ((b.left + b.right - 1) / 2, (b.top + b.bottom - 1) / 2);
    if !(158..=W - 5).contains(&cx) || !(7..=26).contains(&cy) {
        return false;
    }
    let (mut main, mut anti, mut tl, mut br, mut tr, mut bl, mut center) =
        (0, 0, false, false, false, false, false);
    let tolerance = w.max(h) * 2;
    let scale = (w - 1) * (h - 1);
    for point in &c.points {
        let (x, y) = (*point as i32 % W, *point as i32 / W);
        let (rx, ry) = (x - b.left, y - b.top);
        if (rx * (h - 1) - ry * (w - 1)).abs() <= tolerance {
            main += 1;
            tl |= rx * 3 <= w && ry * 3 <= h;
            br |= rx * 3 >= w * 2 && ry * 3 >= h * 2;
        }
        if (rx * (h - 1) + ry * (w - 1) - scale).abs() <= tolerance {
            anti += 1;
            tr |= rx * 3 >= w * 2 && ry * 3 <= h;
            bl |= rx * 3 <= w && ry * 3 >= h * 2;
        }
        center |= (x - cx).abs() <= 1 && (y - cy).abs() <= 1;
    }
    let minimum = 4.max(w.min(h) * 2 / 3);
    main >= minimum && anti >= minimum && tl && br && tr && bl && center
}
fn directional_contrast(p: &[i32], x: i32, y: i32, bg: i32, polarity: i32) -> i32 {
    let mut best = i32::MIN;
    for dy in -1..=1 {
        for dx in -1..=1 {
            best = best.max(
                polarity
                    * (luminance(pixel(p, (x + dx).clamp(0, W - 1), (y + dy).clamp(0, H - 1)))
                        - bg),
            );
        }
    }
    best
}
fn close_template_score(p: &[i32], bg: i32, cx: i32, cy: i32, radius: i32, polarity: i32) -> i32 {
    if radius < 1 || cx - radius < 0 || cx + radius >= W || cy - radius < 0 || cy + radius >= H {
        return 0;
    }
    let mut arms = [0; 4];
    let mut score = 0;
    for step in 1..=radius {
        for (i, (x, y)) in [
            (cx - step, cy - step),
            (cx + step, cy - step),
            (cx - step, cy + step),
            (cx + step, cy + step),
        ]
        .into_iter()
        .enumerate()
        {
            let c = directional_contrast(p, x, y, bg, polarity);
            if c >= 12 {
                arms[i] += 1;
                score += c.min(48);
            }
        }
    }
    if arms.iter().any(|v| *v < 2.max(radius * 2 / 3))
        || directional_contrast(p, cx, cy, bg, polarity) < 10
    {
        return 0;
    }
    let mut clear = 0;
    for distance in [3.max(radius / 2), radius] {
        for (x, y) in [
            (cx - distance, cy),
            (cx + distance, cy),
            (cx, cy - distance),
            (cx, cy + distance),
        ] {
            if polarity * (luminance(pixel(p, x, y)) - bg) < 12 {
                clear += 1;
            }
        }
    }
    if clear < 6 {
        return 0;
    }
    score - (arms.iter().max().unwrap() - arms.iter().min().unwrap()) * 12
}
fn close_template(p: &[i32], bg: i32, search: Bounds) -> Option<Bounds> {
    let (mut best, mut bx, mut by, mut competing) = (0, -1, -1, 0);
    for cy in search.top + 5..search.bottom - 5 {
        for cx in search.left + 5..search.right - 5 {
            for polarity in [1, -1] {
                for radius in 2..=9 {
                    let score = close_template_score(p, bg, cx, cy, radius, polarity);
                    if score <= 0 {
                        continue;
                    }
                    if score > best {
                        if bx >= 0 && ((cx - bx).abs() > 5 || (cy - by).abs() > 5) {
                            competing = competing.max(best);
                        }
                        best = score;
                        bx = cx;
                        by = cy;
                    } else if bx >= 0 && ((cx - bx).abs() > 5 || (cy - by).abs() > 5) {
                        competing = competing.max(score);
                    }
                }
            }
        }
    }
    if best < 120 || competing * 100 >= best * 88 {
        None
    } else {
        Some(Bounds::new(bx - 1, by - 1, bx + 2, by + 2))
    }
}
fn detail_close(p: &[i32]) -> Option<Bounds> {
    let search = Bounds::new(W * 3 / 4, 2, W - 2, 34);
    let bg = median(p, W, search);
    let mut glyphs = vec![];
    for bright in [true, false] {
        let mut foreground = vec![false; (W * H) as usize];
        for y in search.top..search.bottom {
            for x in search.left..search.right {
                let px = pixel(p, x, y);
                let delta = luminance(px) - bg;
                foreground[(y * W + x) as usize] =
                    saturation(px) <= 112 && if bright { delta >= 24 } else { delta <= -24 };
            }
        }
        glyphs.extend(
            components(&foreground, search, true)
                .into_iter()
                .filter(close_x)
                .map(|c| c.bounds),
        );
    }
    if glyphs.len() > 1 {
        return None;
    }
    let glyph = if let Some(glyph) = glyphs.first() {
        *glyph
    } else {
        close_template(p, bg, search)?
    };
    let (cx, cy) = (
        (glyph.left + glyph.right - 1) / 2,
        (glyph.top + glyph.bottom - 1) / 2,
    );
    let (rx, ry) = (14.min(cx.min(W - cx)), 14.min(cy.min(36 - cy)));
    if rx < 8 || ry < 8 {
        None
    } else {
        Some(Bounds::new(cx - rx, cy - ry, cx + rx, cy + ry))
    }
}
fn native_probe_has_close(p: &[i32]) -> bool {
    if p.len() != 384 * 576 {
        return false;
    }
    for oy in 0..2 {
        for ox in 0..2 {
            let mut phase = Vec::with_capacity((W * H) as usize);
            for y in 0..H {
                for x in 0..W {
                    phase.push(p[((y * 2 + oy).min(575) * 384 + (x * 2 + ox).min(383)) as usize]);
                }
            }
            if detail_close(&phase).is_some() {
                return true;
            }
        }
    }
    false
}
fn time_label_close_alias(original: &[i32], close: Option<Bounds>, tabs: Option<&Tabs>) -> bool {
    let (Some(c), Some(t)) = (close, tabs) else {
        return false;
    };
    if original.len() != 384 * 576 {
        return false;
    }
    let (x, y) = ((c.left + c.right - 1) / 2, (c.top + c.bottom - 1) / 2);
    let b = t.time_glyph;
    x >= b.left && x < b.right && y >= b.top && y < b.bottom && !native_probe_has_close(original)
}
fn preserve_header(probe: &[i32], geometry: &[i32]) -> Vec<i32> {
    let mut output = geometry.to_vec();
    for b in [
        Bounds::new(4, 2, W / 2, 34),
        Bounds::new(W * 3 / 4, 2, W - 2, 34),
    ] {
        let bg = median(
            probe,
            384,
            Bounds::new(b.left * 2, b.top * 2, b.right * 2, b.bottom * 2),
        );
        for y in b.top..b.bottom {
            for x in b.left..b.right {
                let mut strongest = output[(y * W + x) as usize];
                let mut contrast = -1;
                for oy in 0..2 {
                    for ox in 0..2 {
                        let px = probe[((y * 2 + oy) * 384 + x * 2 + ox) as usize];
                        if saturation(px) > 112 {
                            continue;
                        }
                        let candidate = (luminance(px) - bg).abs();
                        if candidate > contrast {
                            contrast = candidate;
                            strongest = px;
                        }
                    }
                }
                output[(y * W + x) as usize] = strongest;
            }
        }
    }
    output
}

pub(crate) struct Card {
    pub bounds: Bounds,
    pub registration: Option<Bounds>,
    pub activated: Option<Bounds>,
    pub date: DateRange,
    pub latest: bool,
}
pub(crate) struct Result {
    pub state: &'static str,
    pub detail_identity: bool,
    pub slider: Option<Bounds>,
    pub control: Option<Bounds>,
    pub back: Option<Bounds>,
    pub tickets: Option<Bounds>,
    pub time: Option<Bounds>,
    pub cards: Vec<Card>,
}
impl Result {
    fn state(state: &'static str) -> Self {
        Self {
            state,
            detail_identity: false,
            slider: None,
            control: None,
            back: None,
            tickets: None,
            time: None,
            cards: vec![],
        }
    }
}
fn activated_status(
    p: &[i32],
    date_y: i32,
    card: Bounds,
    registration: Option<Bounds>,
) -> Option<Bounds> {
    let left = (card.left + card.width() * 3 / 4).max(120);
    let right = left.max(card.right - 2);
    let top = (card.top + 2).max(date_y + 1);
    let bottom = (card.bottom - 2).min(registration.map_or(date_y + 26, |b| b.top));
    if right - left < 6 || bottom - top < 4 {
        return None;
    }
    let search = Bounds::new(left, top, right, bottom);
    let mut f = vec![false; (W * H) as usize];
    for y in top..bottom {
        for x in left..right {
            f[(y * W + x) as usize] = self::registration(pixel(p, x, y));
        }
    }
    let mut candidate = None;
    for c in components(&f, search, true) {
        let b = c.bounds;
        let count = c.points.len() as i32;
        if !(5..=28).contains(&b.width())
            || !(4..=22).contains(&b.height())
            || count < 12
            || count * 100 < b.area() * 20
            || (b.left + b.right) / 2 < card.right - 44
        {
            continue;
        }
        if candidate.is_some() {
            return None;
        }
        candidate = Some(Bounds::new(
            (card.left + 2).max(b.left - 4),
            top.max(b.top - 3),
            (card.right - 2).min(b.right + 4),
            bottom.min(b.bottom + 3),
        ));
    }
    candidate
}
fn cards(p: &[i32], dates: Vec<DateRange>, bands: &[Bounds], today: i64) -> Vec<Card> {
    let mut cards: Vec<_> = dates
        .into_iter()
        .map(|date| {
            let cy = date.center_y as i32;
            let bounds = Bounds::new(8, 38.max(cy - 34), W - 8, (H - 24).min(cy + 58));
            let registration = bands
                .iter()
                .filter(|b| b.top >= bounds.top && b.bottom <= bounds.bottom + 12)
                .min_by_key(|b| (b.top - (cy + 20)).abs())
                .copied();
            let activated = activated_status(p, cy, bounds, registration);
            Card {
                bounds,
                registration,
                activated,
                date,
                latest: false,
            }
        })
        .collect();
    let (mut latest, mut selected, mut ambiguous) = (None, None, false);
    for (i, card) in cards.iter().enumerate() {
        if card.registration.is_none() || card.date.until < today {
            continue;
        }
        if latest.is_none_or(|date| card.date.from > date) {
            latest = Some(card.date.from);
            selected = Some(i);
            ambiguous = false;
        } else if latest == Some(card.date.from) {
            ambiguous = true;
        }
    }
    if !ambiguous && let Some(index) = selected {
        cards[index].latest = true;
    }
    cards
}
fn scaled_bounds(wire: &str) -> Option<Bounds> {
    let values: Vec<i32> = wire
        .split(',')
        .map(str::parse)
        .collect::<std::result::Result<_, _>>()
        .ok()?;
    if values.len() != 4 {
        return None;
    }
    Some(Bounds::new(
        values[0] * 4,
        values[1] * 4,
        values[2] * 4,
        values[3] * 4,
    ))
}
fn refined_slider(p: &[i32], coarse: Option<Bounds>, bands: &[Bounds]) -> Option<Bounds> {
    let coarse = coarse?;
    let mut track = None;
    for band in bands {
        let center = (band.top + band.bottom) / 2;
        if center < coarse.top
            || center >= coarse.bottom
            || band.right <= coarse.left
            || band.left >= coarse.right
        {
            continue;
        }
        if track.is_some() {
            return None;
        }
        track = Some(*band);
    }
    let track = track?;
    let height = track.height();
    let search = Bounds::new(
        0.max(coarse.left - 4),
        0.max(coarse.top - 4),
        W.min((track.left + height * 2).max(coarse.left + coarse.width() / 3)),
        H.min(coarse.bottom + 4),
    );
    let mut f = vec![false; (W * H) as usize];
    for y in search.top..search.bottom {
        for x in search.left..search.right {
            let px = pixel(p, x, y);
            f[(y * W + x) as usize] = luminance(px) <= 90 && !registration(px);
        }
    }
    let mut thumb = None;
    let mut best = 0;
    for c in components(&f, search, false) {
        let b = c.bounds;
        if b.left == search.left
            || b.right == search.right
            || b.top == search.top
            || b.bottom == search.bottom
            || b.height() < height * 2 / 3
            || b.width() > height * 3
            || b.bottom <= track.top
            || b.top >= track.bottom
            || c.points.len() <= best
        {
            continue;
        }
        best = c.points.len();
        thumb = Some(b);
    }
    let thumb = thumb?;
    Some(Bounds::new(
        track.left.min(thumb.left),
        track.top.min(thumb.top),
        track.right,
        track.bottom.max(thumb.bottom),
    ))
}
pub(crate) fn classify(original: &[i32], resolve_list: bool, today: i64) -> Result {
    use crate::visual_control as control;
    let Some(p) = geometry(original) else {
        return Result::state("unknown");
    };
    let header = if original.len() == 384 * 576 {
        preserve_header(original, &p)
    } else {
        p.clone()
    };
    let compact = downsample(&p, W, H, 48, 72);
    let ordinary = control::classify(&compact);
    let activated = control::classify_for_activated_ticket(&compact);
    let generated_close = control::generated_result_close_bounds(&compact);
    let slider = control::registration_slider_bounds(&compact);
    let back = detail_close(&header);
    let bands = yellow_bands(&p);
    let strong_list = strong_list_chrome(&p);
    let detail_base = activated == "raw_ticket";
    if let Some(home) = home(&p)
        && ordinary != "control_popup"
        && !looks_like_login(&p)
    {
        return Result {
            tickets: Some(home),
            ..Result::state("vivi_home")
        };
    }
    if strong_list && !resolve_list {
        return Result {
            back,
            ..Result::state("ticket_list")
        };
    }
    if strong_list {
        let dates = if bands.is_empty() {
            vec![]
        } else if original.len() == 384 * 576 {
            visual_date::recognize(original, 384, 576)
                .into_iter()
                .map(|mut range| {
                    range.center_y = (range.center_y * H as usize / 576).min(H as usize - 1);
                    range
                })
                .collect()
        } else {
            visual_date::recognize(original, W, H)
        };
        let cards = cards(&p, dates, &bands, today);
        let has_registration = cards.iter().any(|c| c.registration.is_some());
        let tabs = tickets_tabs(&p, false);
        let safe = tabs.is_some()
            && !detail_base
            && (back.is_none() || time_label_close_alias(original, back, tabs.as_ref()))
            && !looks_like_login(&p)
            && ordinary != "control_popup"
            && ordinary != "generated";
        if !cards.is_empty() && has_registration {
            let (tickets, time) = if safe {
                let t = tabs.as_ref().unwrap();
                (
                    if t.time_selected { t.single } else { None },
                    if !t.time_selected { t.time } else { None },
                )
            } else {
                (None, None)
            };
            return Result {
                back,
                tickets,
                time,
                cards,
                ..Result::state("ticket_list")
            };
        }
        if safe {
            let t = tabs.unwrap();
            return Result {
                tickets: if t.time_selected { t.single } else { None },
                time: if t.time_selected { None } else { t.time },
                ..Result::state("ticket_list")
            };
        }
        return Result::state("unknown");
    }
    let tabs = tickets_tabs(&p, true);
    if let Some(t) = tabs.as_ref()
        && slider.is_empty()
        && !detail_base
        && (back.is_none() || time_label_close_alias(original, back, tabs.as_ref()))
        && !looks_like_login(&p)
        && ordinary != "control_popup"
        && ordinary != "generated"
    {
        return Result {
            tickets: if t.time_selected { t.single } else { None },
            time: if t.time_selected { None } else { t.time },
            ..Result::state(if t.time_selected {
                "tickets_time_empty"
            } else {
                "tickets_single_use_empty"
            })
        };
    }
    if !slider.is_empty() && (detail_base || back.is_some()) {
        return Result {
            detail_identity: true,
            slider: refined_slider(&p, scaled_bounds(&slider), &bands),
            control: control_button(&header),
            back,
            ..Result::state("unactivated_detail")
        };
    }
    if !slider.is_empty() {
        return Result::state("unknown");
    }
    if ordinary == "generated" && !generated_close.is_empty() {
        return Result::state("blocked");
    }
    if activated == "raw_ticket" {
        return Result {
            detail_identity: true,
            control: control_button(&header),
            back,
            ..Result::state("activated_detail")
        };
    }
    if looks_like_login(&p) {
        return Result::state("login_required");
    }
    if ordinary == "control_popup" || ordinary == "generated" {
        return Result::state("blocked");
    }
    if profile(&p) {
        return Result::state("vivi_profile");
    }
    if other_tab(&p, false) {
        return Result::state("vivi_other_tab");
    }
    Result::state("unknown")
}
