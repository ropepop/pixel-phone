//! Byte contracts shared by the rooted encoder, Android service and browser.
pub const MAX_PAYLOAD: usize = 2 * 1024 * 1024;
pub const THF_HEADER: usize = 65;
pub const TSF_HEADER: usize = 93;
const MAX_SAFE_INTEGER: i64 = 9_007_199_254_740_991;
const START_CODE: &[u8] = &[0, 0, 0, 1];
const DELIMITER: &[u8] = &[0, 0, 0, 1, 9, 16];

pub struct AccessUnit {
    pub payload: Vec<u8>,
    pub codec_config: bool,
    pub key_frame: bool,
    pub contains_vcl: bool,
    pub idr_key_frame: bool,
}

#[derive(Clone, Copy, Default)]
enum Framing {
    #[default]
    Unknown,
    AnnexB,
    LengthPrefixed,
}

#[derive(Default)]
pub struct Assembler {
    pending: Vec<u8>,
    has_pending: bool,
    pending_config: bool,
    pending_key: bool,
    discard_until_final: bool,
    overflowed: bool,
    sps: Option<Vec<u8>>,
    pps: Option<Vec<u8>>,
    framing: Framing,
}

impl Assembler {
    pub fn accept(
        &mut self,
        data: &[u8],
        partial: bool,
        config: bool,
        key: bool,
    ) -> Option<AccessUnit> {
        if data.is_empty() && !partial {
            return None;
        }
        if self.discard_until_final {
            if !partial {
                self.clear_pending();
            }
            return None;
        }
        if self.has_pending || partial {
            if data.len() > MAX_PAYLOAD - self.pending.len() {
                self.clear_pending();
                self.discard_until_final = partial;
                self.overflowed = true;
                return None;
            }
            self.pending.extend_from_slice(data);
            self.has_pending = true;
            self.pending_config |= config;
            self.pending_key |= key;
            if partial {
                return None;
            }
            let assembled = self.pending.clone();
            let config = self.pending_config;
            let key = self.pending_key;
            self.clear_pending();
            return self.emit(&assembled, config, key);
        }
        if data.len() > MAX_PAYLOAD {
            self.overflowed = true;
            return None;
        }
        self.emit(data, config, key)
    }

    pub fn reset(&mut self) {
        self.clear_pending();
        self.overflowed = false;
        self.sps = None;
        self.pps = None;
        self.framing = Framing::Unknown;
    }

    fn clear_pending(&mut self) {
        self.pending.clear();
        self.has_pending = false;
        self.pending_config = false;
        self.pending_key = false;
        self.discard_until_final = false;
    }

    pub fn consume_overflowed(&mut self) -> bool {
        std::mem::take(&mut self.overflowed)
    }

    fn emit(&mut self, data: &[u8], config: bool, key: bool) -> Option<AccessUnit> {
        let mut units = if matches!(self.framing, Framing::Unknown) {
            if !config {
                return None;
            }
            let annex = parse(data, Framing::AnnexB);
            let lengths = parse(data, Framing::LengthPrefixed);
            let annex_config = annex.as_ref().is_some_and(Nals::has_parameter_set);
            let lengths_config = lengths.as_ref().is_some_and(Nals::has_parameter_set);
            if annex_config == lengths_config {
                return None;
            }
            self.framing = if annex_config {
                Framing::AnnexB
            } else {
                Framing::LengthPrefixed
            };
            if annex_config { annex? } else { lengths? }
        } else {
            parse(data, self.framing)?
        };
        if let Some(sps) = &units.sps {
            self.sps = Some(sps.clone());
        }
        if let Some(pps) = &units.pps {
            self.pps = Some(pps.clone());
        }
        if units.idr {
            let sps = self.sps.as_ref()?;
            let pps = self.pps.as_ref()?;
            if units.sps.is_none() {
                units.nals.insert(0, sps.clone());
            }
            if units.pps.is_none() {
                let after_sps = units
                    .nals
                    .iter()
                    .rposition(|nal| nal[start_code_at(nal, 0)] & 0x1f == 7)
                    .map_or(0, |index| index + 1);
                units.nals.insert(after_sps, pps.clone());
            }
        }
        let mut payload = Vec::with_capacity(data.len());
        for nal in units.nals {
            payload.extend_from_slice(&nal);
        }
        if !payload.ends_with(DELIMITER) {
            payload.extend_from_slice(DELIMITER);
        }
        if payload.len() > MAX_PAYLOAD {
            self.overflowed = true;
            return None;
        }
        Some(AccessUnit {
            payload,
            codec_config: config,
            key_frame: key,
            contains_vcl: units.vcl,
            idr_key_frame: units.idr,
        })
    }
}

#[derive(Default)]
struct Nals {
    nals: Vec<Vec<u8>>,
    sps: Option<Vec<u8>>,
    pps: Option<Vec<u8>>,
    vcl: bool,
    idr: bool,
}
impl Nals {
    fn has_parameter_set(&self) -> bool {
        self.sps.is_some() || self.pps.is_some()
    }
}

fn parse(data: &[u8], framing: Framing) -> Option<Nals> {
    let mut units = Nals::default();
    let mut offset = 0;
    while offset < data.len() {
        let (header_at, end, nal) = if matches!(framing, Framing::LengthPrefixed) {
            let length = i32::from_be_bytes(data.get(offset..offset + 4)?.try_into().ok()?);
            if length <= 0 || length as usize > data.len() - offset - 4 {
                return None;
            }
            let header_at = offset + 4;
            let end = header_at + length as usize;
            let mut nal = data[offset..end].to_vec();
            nal[..4].copy_from_slice(START_CODE);
            (header_at, end, nal)
        } else {
            let prefix = start_code_at(data, offset);
            if prefix == 0 || offset + prefix >= data.len() {
                return None;
            }
            let header_at = offset + prefix;
            let end = (header_at + 1..data.len())
                .find(|&at| start_code_at(data, at) > 0)
                .unwrap_or(data.len());
            (header_at, end, data[offset..end].to_vec())
        };
        let header = data[header_at];
        let kind = header & 0x1f;
        if header & 0x80 != 0 || kind == 0 {
            return None;
        }
        if kind == 7 {
            units.sps = Some(nal.clone());
        }
        if kind == 8 {
            units.pps = Some(nal.clone());
        }
        units.vcl |= (1..=5).contains(&kind);
        units.idr |= kind == 5;
        units.nals.push(nal);
        offset = end;
    }
    if units.nals.is_empty() {
        None
    } else {
        Some(units)
    }
}

fn start_code_at(data: &[u8], at: usize) -> usize {
    if data.get(at..at + 3) == Some(&[0, 0, 1]) {
        3
    } else if data.get(at..at + 4) == Some(START_CODE) {
        4
    } else {
        0
    }
}

pub fn validate_thf(metadata: &[i64; 7], payload_len: usize) -> Result<(), &'static str> {
    if payload_len == 0 || payload_len > MAX_PAYLOAD {
        return Err("invalid THF1 payload length");
    }
    if metadata[0] <= 0
        || metadata[1] <= 0
        || metadata[2] <= 0
        || metadata[2..].windows(2).any(|pair| pair[1] < pair[0])
    {
        return Err("invalid THF1 stage timestamps");
    }
    Ok(())
}

pub fn thf_header(
    key: bool,
    metadata: &[i64; 7],
    payload_len: usize,
) -> Result<Vec<u8>, &'static str> {
    validate_thf(metadata, payload_len)?;
    let mut header = Vec::with_capacity(THF_HEADER);
    header.extend_from_slice(b"THF1");
    header.push(u8::from(key));
    for value in metadata {
        header.extend_from_slice(&value.to_be_bytes());
    }
    header.extend_from_slice(&(payload_len as i32).to_be_bytes());
    Ok(header)
}

pub fn parse_thf_header(header: &[u8]) -> Result<(bool, [i64; 7], usize), &'static str> {
    if header.len() != THF_HEADER {
        return Err("truncated THF1 header");
    }
    if &header[..4] != b"THF1" {
        return Err("invalid THF1 magic");
    }
    if header[4] & !1 != 0 {
        return Err("unsupported THF1 flags");
    }
    let mut metadata = [0; 7];
    for (index, value) in metadata.iter_mut().enumerate() {
        *value = i64::from_be_bytes(header[5 + index * 8..13 + index * 8].try_into().unwrap());
    }
    let size = i32::from_be_bytes(header[61..65].try_into().unwrap());
    if size <= 0 || size as usize > MAX_PAYLOAD {
        return Err("invalid THF1 payload length");
    }
    // Timestamp validation happens only after the caller reads the full payload,
    // preserving truncated-payload precedence in the existing stream interface.
    Ok((header[4] & 1 != 0, metadata, size as usize))
}

pub fn tsf_header(
    key: bool,
    metadata: &[i64; 11],
    payload_len: usize,
) -> Result<Vec<u8>, &'static str> {
    if metadata[..2]
        .iter()
        .any(|value| *value <= 0 || *value > MAX_SAFE_INTEGER)
    {
        return Err("TSF3 epoch and sequence must be positive");
    }
    if metadata[2..5]
        .iter()
        .any(|value| *value <= 0 || *value > MAX_SAFE_INTEGER)
        || metadata[4..9]
            .windows(2)
            .any(|pair| pair[1] < pair[0] || pair[1] > MAX_SAFE_INTEGER)
        || metadata[9..]
            .iter()
            .any(|value| *value < 0 || *value > MAX_SAFE_INTEGER)
    {
        return Err("invalid TSF3 stage metadata");
    }
    if payload_len == 0 || payload_len > MAX_PAYLOAD {
        return Err("invalid TSF3 payload length");
    }
    let mut header = Vec::with_capacity(TSF_HEADER);
    header.extend_from_slice(b"TSF3");
    header.push(u8::from(key));
    for value in metadata {
        header.extend_from_slice(&value.to_be_bytes());
    }
    Ok(header)
}
