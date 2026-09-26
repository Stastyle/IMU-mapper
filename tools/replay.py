#!/usr/bin/env python3
"""
replay.py - offline mirror of the IMU Mapper PDR pipeline.

Reads a raw `.imul` log (pipeline/.../log/LogFormat.kt), runs the same processing steps as the
Kotlin classes in pipeline/.../pdr and pipeline/.../post, and writes the path (CSV + JSON with the
PathResult field names), plots and statistics. Function and class names follow the Kotlin ones so
the two implementations can be compared side by side; when they disagree, the Kotlin code is the
reference and this file is the one to fix.

Only numpy and matplotlib are needed (tools/requirements.txt). Usage: see tools/README.md or
`python3 replay.py --help`.
"""

import argparse
import csv
import json
import math
import os
import struct
import sys

import numpy as np

# ----------------------------------------------------------------------------------------------
# LogFormat (pipeline/.../log/LogFormat.kt)
# ----------------------------------------------------------------------------------------------

MAGIC = b"IMUL"
FORMAT_VERSION = 1
HEADER_SIZE = 8
RECORD_HEADER_SIZE = 5

T_ACCEL = 0x01
T_GYRO = 0x02
T_MAG = 0x03
T_BARO = 0x04
T_GAME_ROT = 0x05
T_ROT_VEC = 0x06
T_STEP = 0x07
T_ACCEL_UNCAL = 0x08
T_GYRO_UNCAL = 0x09
T_MAG_UNCAL = 0x0A
T_POSE = 0x10
T_POINT_CLOUD = 0x11
T_KEYFRAME = 0x12
T_ANNOTATION = 0x20
T_EVENT = 0x21
T_META = 0x30

TYPE_NAMES = {
    T_ACCEL: "ACCEL", T_GYRO: "GYRO", T_MAG: "MAG", T_BARO: "BARO", T_GAME_ROT: "GAME_ROT",
    T_ROT_VEC: "ROT_VEC", T_STEP: "STEP", T_ACCEL_UNCAL: "ACCEL_UNCAL", T_GYRO_UNCAL: "GYRO_UNCAL",
    T_MAG_UNCAL: "MAG_UNCAL", T_POSE: "POSE", T_POINT_CLOUD: "POINT_CLOUD", T_KEYFRAME: "KEYFRAME",
    T_ANNOTATION: "ANNOTATION", T_EVENT: "EVENT", T_META: "META",
}

# Enum ordinals as declared in core/Samples.kt; an ordinal past the end maps to the last entry,
# exactly like LogReader.enumOrLast.
ANNOTATION_KINDS = ["WAYPOINT", "JUNCTION", "CHAMBER", "NOTE", "LOOP_CLOSED", "REORIENT"]
EVENT_KINDS = [
    "START", "STOP", "PAUSE", "RESUME", "SCREEN_OFF", "SCREEN_ON", "TRACKING_LOST",
    "TRACKING_REGAINED", "TORCH_ON", "TORCH_OFF", "SENSOR_STALL",
]
TRACKING_STATES = ["STOPPED", "PAUSED", "TRACKING"]

_S_VEC = struct.Struct("<qfff")
_S_UNCAL = struct.Struct("<qffffff")
_S_BARO = struct.Struct("<qf")
_S_ROT = struct.Struct("<qfffff")
_S_STEP = struct.Struct("<q")
_S_POSE = struct.Struct("<qqfffffffBB")
_S_KEYFRAME = struct.Struct("<qfffffffH")
_S_ANNOTATION = struct.Struct("<qBH")
_S_EVENT = struct.Struct("<qB")


def enum_or_last(entries, ordinal):
    return entries[ordinal] if 0 <= ordinal < len(entries) else entries[-1]


class RawLog:
    """One numpy array set per record type, in file order (mirrors log/RawLog.kt)."""

    def __init__(self):
        self.meta = None
        self.meta_json = None
        self.accel_t = np.zeros(0, np.int64)
        self.accel = np.zeros((0, 3))
        self.gyro_t = np.zeros(0, np.int64)
        self.gyro = np.zeros((0, 3))
        self.mag_t = np.zeros(0, np.int64)
        self.mag = np.zeros((0, 3))
        self.accel_uncal_t = np.zeros(0, np.int64)
        self.accel_uncal = np.zeros((0, 6))
        self.gyro_uncal_t = np.zeros(0, np.int64)
        self.gyro_uncal = np.zeros((0, 6))
        self.mag_uncal_t = np.zeros(0, np.int64)
        self.mag_uncal = np.zeros((0, 6))
        self.baro_t = np.zeros(0, np.int64)
        self.baro_hpa = np.zeros(0)
        # Rotation samples keep the file layout (qx, qy, qz, qw, accuracy) plus the source.
        self.rotation_t = np.zeros(0, np.int64)
        self.rotation = np.zeros((0, 5))
        self.rotation_source = np.zeros(0, dtype="U5")
        self.steps_t = np.zeros(0, np.int64)
        self.poses_t = np.zeros(0, np.int64)
        self.poses_frame_t = np.zeros(0, np.int64)
        self.poses = np.zeros((0, 7))
        self.poses_tracking = []
        self.poses_reason = np.zeros(0, np.int64)
        self.point_clouds = []  # list of (tNs, float32 array (n, 4))
        self.keyframes = []  # list of dicts: tNs, fileName, tx..qw
        self.annotations = []  # list of dicts: tNs, kind, note
        self.events_t = np.zeros(0, np.int64)
        self.events_kind = []
        self.truncated = False
        self.unknown_records = 0
        self.records = 0
        self.counts = {}

    # RawLog.gameRotation / fusedRotation
    def game_rotation(self):
        m = self.rotation_source == "GAME"
        return self.rotation_t[m], self.rotation[m]

    def fused_rotation(self):
        m = self.rotation_source == "FUSED"
        return self.rotation_t[m], self.rotation[m]

    def _time_lists(self):
        return [self.accel_t, self.gyro_t, self.mag_t, self.baro_t, self.rotation_t, self.steps_t,
                self.poses_t, self.events_t]

    # RawLog.firstTimestampNs: first element of each list (file order), not the minimum.
    def first_timestamp_ns(self):
        firsts = [int(t[0]) for t in self._time_lists() if len(t) > 0]
        return min(firsts) if firsts else 0

    def last_timestamp_ns(self):
        lasts = [int(t[-1]) for t in self._time_lists() if len(t) > 0]
        return max(lasts) if lasts else 0

    def duration_s(self):
        return (self.last_timestamp_ns() - self.first_timestamp_ns()) / 1e9


def parse_imul(path):
    """Mirrors LogReader.forEachRecord + RawLog.Builder: unknown types are skipped, a truncated tail
    is dropped, a corrupt payload of a known type is counted as unknown."""
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < HEADER_SIZE:
        raise ValueError("file shorter than header")
    magic, version, _reserved = struct.unpack_from("<4sHH", data, 0)
    if magic != MAGIC:
        raise ValueError("bad magic %r" % magic)
    if version > FORMAT_VERSION or version < 1:
        raise ValueError("unsupported format version %d" % version)

    log = RawLog()
    lists = {t: [] for t in TYPE_NAMES}
    counts = {}
    off = HEADER_SIZE
    size = len(data)
    view = memoryview(data)
    while True:
        if size - off < RECORD_HEADER_SIZE:
            log.truncated = size - off > 0
            break
        rtype = data[off]
        (length,) = struct.unpack_from("<I", data, off + 1)
        off += RECORD_HEADER_SIZE
        if off + length > size:
            log.truncated = True
            break
        try:
            # Decode from a bounded view so a short payload raises instead of reading the next record.
            _decode(rtype, view[off:off + length], off, length, lists)
            if rtype in TYPE_NAMES:
                counts[rtype] = counts.get(rtype, 0) + 1
                log.records += 1
            else:
                log.unknown_records += 1
        except (struct.error, UnicodeDecodeError, ValueError):
            log.unknown_records += 1
        off += length

    def vec(rows, width):
        if not rows:
            return np.zeros(0, np.int64), np.zeros((0, width))
        a = np.array(rows, dtype=np.float64)
        t = np.array([r[0] for r in rows], dtype=np.int64)
        return t, a[:, 1:]

    log.accel_t, log.accel = vec(lists[T_ACCEL], 3)
    log.gyro_t, log.gyro = vec(lists[T_GYRO], 3)
    log.mag_t, log.mag = vec(lists[T_MAG], 3)
    log.accel_uncal_t, log.accel_uncal = vec(lists[T_ACCEL_UNCAL], 6)
    log.gyro_uncal_t, log.gyro_uncal = vec(lists[T_GYRO_UNCAL], 6)
    log.mag_uncal_t, log.mag_uncal = vec(lists[T_MAG_UNCAL], 6)
    log.baro_t, b = vec(lists[T_BARO], 1)
    log.baro_hpa = b[:, 0]
    rot = lists[T_GAME_ROT] + lists[T_ROT_VEC]
    if rot:
        # Both sources share one list in file order, like RawLog.rotation.
        rot.sort(key=lambda r: r[6])
        log.rotation_t = np.array([r[0] for r in rot], dtype=np.int64)
        log.rotation = np.array([r[1:6] for r in rot], dtype=np.float64)
        log.rotation_source = np.array(["GAME" if r[7] == T_GAME_ROT else "FUSED" for r in rot])
    log.steps_t = np.array([r[0] for r in lists[T_STEP]], dtype=np.int64)
    poses = lists[T_POSE]
    if poses:
        log.poses_t = np.array([r[0] for r in poses], dtype=np.int64)
        log.poses_frame_t = np.array([r[1] for r in poses], dtype=np.int64)
        log.poses = np.array([r[2:9] for r in poses], dtype=np.float64)
        log.poses_tracking = [enum_or_last(TRACKING_STATES, r[9]) for r in poses]
        log.poses_reason = np.array([r[10] for r in poses], dtype=np.int64)
    log.point_clouds = lists[T_POINT_CLOUD]
    log.keyframes = lists[T_KEYFRAME]
    log.annotations = lists[T_ANNOTATION]
    events = lists[T_EVENT]
    log.events_t = np.array([r[0] for r in events], dtype=np.int64)
    log.events_kind = [r[1] for r in events]
    if lists[T_META]:
        log.meta_json = lists[T_META][0]
        try:
            log.meta = json.loads(log.meta_json)
        except ValueError:
            log.meta = None
    log.counts = {TYPE_NAMES[t]: n for t, n in sorted(counts.items())}
    return log


def _decode(rtype, payload, file_off, length, lists):
    """Decodes one record payload (a memoryview of exactly `length` bytes) into `lists`."""
    if rtype in (T_ACCEL, T_GYRO, T_MAG):
        lists[rtype].append(_S_VEC.unpack_from(payload, 0))
    elif rtype in (T_ACCEL_UNCAL, T_GYRO_UNCAL, T_MAG_UNCAL):
        lists[rtype].append(_S_UNCAL.unpack_from(payload, 0))
    elif rtype == T_BARO:
        lists[rtype].append(_S_BARO.unpack_from(payload, 0))
    elif rtype in (T_GAME_ROT, T_ROT_VEC):
        r = _S_ROT.unpack_from(payload, 0)
        # Append the file position so the merged rotation list keeps file order.
        lists[rtype].append(r + (file_off, rtype))
    elif rtype == T_STEP:
        lists[rtype].append(_S_STEP.unpack_from(payload, 0))
    elif rtype == T_POSE:
        lists[rtype].append(_S_POSE.unpack_from(payload, 0))
    elif rtype == T_POINT_CLOUD:
        t, n = struct.unpack_from("<qi", payload, 0)
        if n < 0 or n * 16 > length - 12:
            raise ValueError("bad point count %d" % n)
        arr = np.frombuffer(payload, dtype="<f4", count=n * 4, offset=12).reshape(n, 4)
        lists[rtype].append((t, arr.astype(np.float64)))
    elif rtype == T_KEYFRAME:
        r = _S_KEYFRAME.unpack_from(payload, 0)
        name = _read_string(payload, _S_KEYFRAME.size, r[8])
        lists[rtype].append({
            "tNs": r[0], "fileName": name, "tx": r[1], "ty": r[2], "tz": r[3],
            "qx": r[4], "qy": r[5], "qz": r[6], "qw": r[7],
        })
    elif rtype == T_ANNOTATION:
        t, kind, n = _S_ANNOTATION.unpack_from(payload, 0)
        note = _read_string(payload, _S_ANNOTATION.size, n)
        lists[rtype].append({"tNs": t, "kind": enum_or_last(ANNOTATION_KINDS, kind), "note": note})
    elif rtype == T_EVENT:
        t, kind = _S_EVENT.unpack_from(payload, 0)
        lists[rtype].append((t, enum_or_last(EVENT_KINDS, kind)))
    elif rtype == T_META:
        (n,) = struct.unpack_from("<i", payload, 0)
        lists[rtype].append(_read_string(payload, 4, n))


def _read_string(payload, start, n):
    if n < 0 or start + n > len(payload):
        raise ValueError("bad string length %d" % n)
    return bytes(payload[start:start + n]).decode("utf-8")


# ----------------------------------------------------------------------------------------------
# PipelineConfig (core/PipelineConfig.kt)
# ----------------------------------------------------------------------------------------------

CONFIG_DEFAULTS = [
    ("strideLengthM", 0.70),
    ("weinbergK", 0.0),
    ("headingOffsetRad", 0.0),
    ("useMagnetometer", True),
    ("magGateTolerance", 0.15),
    ("gyroBias", {"x": 0.0, "y": 0.0, "z": 0.0}),
    ("stepMinIntervalS", 0.30),
    ("stepMinSwing", 1.0),
    ("stepBandLowHz", 0.5),
    ("stepBandHighHz", 3.0),
    ("preferHardwareSteps", False),
    ("baroSmoothingS", 1.0),
    ("baroHoldWhenStill", True),
    ("loopClosure", True),
    ("smoothingWindow", 3),
    ("pdrFallbackWhenTrackingLost", True),
    ("vioResamplePeriodS", 0.1),
]


class PipelineConfig:
    def __init__(self, values=None):
        for key, default in CONFIG_DEFAULTS:
            v = default
            if values is not None and key in values:
                v = values[key]
            setattr(self, key, dict(v) if isinstance(v, dict) else v)

    @staticmethod
    def from_meta(meta):
        """Config stored in LogMeta, with defaults for anything missing (ignoreUnknownKeys)."""
        cfg = meta.get("config") if isinstance(meta, dict) else None
        return PipelineConfig(cfg if isinstance(cfg, dict) else None)

    def apply_override(self, text):
        """`key=value` from --set; the value type follows the default (gyroBias takes x,y,z)."""
        if "=" not in text:
            raise ValueError("--set expects key=value, got %r" % text)
        key, value = text.split("=", 1)
        key = key.strip()
        defaults = dict(CONFIG_DEFAULTS)
        if key not in defaults:
            raise ValueError("unknown PipelineConfig field %r (known: %s)" % (key, ", ".join(defaults)))
        default = defaults[key]
        if isinstance(default, bool):
            parsed = value.strip().lower() in ("1", "true", "yes", "on")
        elif isinstance(default, int):
            parsed = int(value)
        elif isinstance(default, float):
            parsed = float(value)
        else:
            parts = [float(p) for p in value.replace("[", "").replace("]", "").split(",")]
            if len(parts) != 3:
                raise ValueError("%s expects x,y,z" % key)
            parsed = {"x": parts[0], "y": parts[1], "z": parts[2]}
        setattr(self, key, parsed)

    def to_dict(self):
        return {key: getattr(self, key) for key, _ in CONFIG_DEFAULTS}

    def gyro_bias(self):
        b = self.gyroBias
        return np.array([b["x"], b["y"], b["z"]], dtype=np.float64)


# ----------------------------------------------------------------------------------------------
# Vec3 / Quat helpers (core/Quat.kt). Quaternions are arrays [..., 4] = (w, x, y, z).
# ----------------------------------------------------------------------------------------------

IDENTITY = np.array([1.0, 0.0, 0.0, 0.0])
UNIT_Y = np.array([0.0, 1.0, 0.0])
UNIT_Z = np.array([0.0, 0.0, 1.0])
CAMERA_AXIS = np.array([0.0, 0.0, -1.0])


def quat_normalized(q):
    q = np.asarray(q, dtype=np.float64)
    n = np.linalg.norm(q, axis=-1, keepdims=True)
    out = np.where(n > 0.0, q / np.where(n > 0.0, n, 1.0), IDENTITY)
    return out


def quat_mul(a, b):
    aw, ax, ay, az = a[..., 0], a[..., 1], a[..., 2], a[..., 3]
    bw, bx, by, bz = b[..., 0], b[..., 1], b[..., 2], b[..., 3]
    return np.stack([
        aw * bw - ax * bx - ay * by - az * bz,
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
    ], axis=-1)


def quat_conjugate(q):
    return q * np.array([1.0, -1.0, -1.0, -1.0])


def quat_rotate(q, v):
    """q * v * q^-1 in the same efficient form as Quat.rotate; broadcasts over leading axes."""
    q = np.asarray(q, dtype=np.float64)
    v = np.asarray(v, dtype=np.float64)
    qv = q[..., 1:4]
    w = q[..., 0:1]
    t = np.cross(qv, v) * 2.0
    return v + t * w + np.cross(qv, t)


def quat_from_axis_angle(axis, angle):
    a = np.asarray(axis, dtype=np.float64)
    n = np.linalg.norm(a)
    if n > 0.0:
        a = a / n
    h = angle / 2.0
    s = math.sin(h)
    return np.array([math.cos(h), a[0] * s, a[1] * s, a[2] * s])


def quat_yaw(rad):
    """Rotation about world Z; vectorised over rad."""
    rad = np.asarray(rad, dtype=np.float64)
    h = rad / 2.0
    return np.stack([np.cos(h), np.zeros_like(h), np.zeros_like(h), np.sin(h)], axis=-1)


def quat_from_android_rotation_vector(rx, ry, rz, rw):
    return quat_normalized(np.stack([rw, rx, ry, rz], axis=-1))


def quat_slerp(a, b, t):
    """Quat.slerp, vectorised: short path, linear blend above dot 0.9995."""
    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
    t = np.asarray(t, dtype=np.float64)[..., None]
    d = np.sum(a * b, axis=-1, keepdims=True)
    b = np.where(d < 0.0, -b, b)
    d = np.abs(d)
    linear = quat_normalized(a + (b - a) * t)
    dc = np.clip(d, -1.0, 1.0)
    theta0 = np.arccos(dc)
    sin0 = np.sin(theta0)
    safe = np.where(sin0 == 0.0, 1.0, sin0)
    theta = theta0 * t
    s0 = np.cos(theta) - dc * np.sin(theta) / safe
    s1 = np.sin(theta) / safe
    spherical = quat_normalized(a * s0 + b * s1)
    return np.where(d > 0.9995, linear, spherical)


def heading_of(direction):
    """Compass heading of a world direction: radians clockwise from north (+Y)."""
    d = np.asarray(direction, dtype=np.float64)
    return np.arctan2(d[..., 0], d[..., 1])


def forward_heading_rad(q):
    return heading_of(quat_rotate(q, UNIT_Y))


def camera_heading_rad(q):
    return heading_of(quat_rotate(q, CAMERA_AXIS))


def euler_zxy_yaw(q):
    """First component of Quat.eulerZXY (maths yaw about Z)."""
    w, x, y, z = q[..., 0], q[..., 1], q[..., 2], q[..., 3]
    m01 = 2.0 * (x * y - w * z)
    m11 = 1.0 - 2.0 * (x * x + z * z)
    return np.arctan2(-m01, m11)


# ----------------------------------------------------------------------------------------------
# Angles (pdr/Angles.kt), Diag (pdr/Diag.kt)
# ----------------------------------------------------------------------------------------------

TWO_PI = 2.0 * math.pi


class Angles:
    @staticmethod
    def wrap(rad):
        r = np.fmod(np.asarray(rad, dtype=np.float64), TWO_PI)
        r = np.where(r > math.pi, r - TWO_PI, np.where(r <= -math.pi, r + TWO_PI, r))
        return float(r) if np.ndim(r) == 0 else r

    @staticmethod
    def diff(a, b):
        return Angles.wrap(np.asarray(a) - np.asarray(b))

    @staticmethod
    def circular_mean(angles, start, end):
        if start >= end:
            return None
        c = float(np.sum(np.cos(angles[start:end])))
        s = float(np.sum(np.sin(angles[start:end])))
        if c * c + s * s < 1e-12:
            return None
        return math.atan2(s, c)


class Diag:
    @staticmethod
    def num(x, decimals=3):
        return "%.*f" % (decimals, x)


def floor_index(times, queries):
    """Index of the last sample with time <= query, -1 when before the first (OrientationTrack.floorIndex)."""
    return np.searchsorted(times, queries, side="right") - 1


def lower_bound(times, queries):
    """Index of the first sample with time >= query (WorldAccel/DetectedSteps.lowerBound)."""
    return np.searchsorted(times, queries, side="left")


def keep_monotonic(times):
    """Mask of samples whose timestamp does not go backwards relative to the last kept sample."""
    if len(times) == 0:
        return np.zeros(0, dtype=bool)
    running = np.maximum.accumulate(times)
    keep = np.ones(len(times), dtype=bool)
    keep[1:] = times[1:] >= running[:-1]
    return keep


# ----------------------------------------------------------------------------------------------
# OrientationTrack (pdr/OrientationTrack.kt)
# ----------------------------------------------------------------------------------------------

class OrientationTrack:
    def __init__(self, times, quats, dropped=0):
        self.times = np.asarray(times, dtype=np.int64)
        self.q = np.asarray(quats, dtype=np.float64).reshape(-1, 4)
        self.dropped = dropped

    @property
    def size(self):
        return len(self.times)

    @property
    def is_empty(self):
        return len(self.times) == 0

    def at(self, t_ns):
        """Slerp between the neighbouring samples, clamped to the ends; identity when empty."""
        t = np.asarray(t_ns, dtype=np.int64)
        scalar = t.ndim == 0
        t = np.atleast_1d(t)
        if self.is_empty:
            out = np.tile(IDENTITY, (len(t), 1))
            return out[0] if scalar else out
        n = self.size
        i = floor_index(self.times, t)
        i0 = np.clip(i, 0, n - 1)
        i1 = np.clip(i + 1, 0, n - 1)
        t0 = self.times[i0]
        t1 = self.times[i1]
        interior = (i >= 0) & (i < n - 1) & (t1 > t0)
        span = np.where(interior, (t1 - t0).astype(np.float64), 1.0)
        f = np.where(interior, (t - t0).astype(np.float64) / span, 0.0)
        out = quat_slerp(self.q[i0], self.q[i1], f)
        out = np.where(interior[:, None], out, self.q[i0])
        return out[0] if scalar else out

    @staticmethod
    def build(times, quats):
        """OrientationTrack.Builder: samples whose timestamp goes backwards are dropped."""
        times = np.asarray(times, dtype=np.int64)
        keep = keep_monotonic(times)
        return OrientationTrack(times[keep], np.asarray(quats)[keep], dropped=int((~keep).sum()))

    @staticmethod
    def from_rotation_samples(times, samples):
        q = quat_from_android_rotation_vector(samples[:, 0], samples[:, 1], samples[:, 2], samples[:, 3])
        return OrientationTrack.build(times, q)


EMPTY_TRACK = OrientationTrack(np.zeros(0, np.int64), np.zeros((0, 4)))


# ----------------------------------------------------------------------------------------------
# MadgwickFilter (pdr/MadgwickFilter.kt)
# ----------------------------------------------------------------------------------------------

class MadgwickFilter:
    def __init__(self, beta=0.1):
        self.beta = beta
        self.q = IDENTITY.copy()
        self.initialized = False

    def init_from_accel(self, ax, ay, az):
        a = np.array([ax, ay, az], dtype=np.float64)
        length = float(np.linalg.norm(a))
        if length < 1e-6:
            self.q = IDENTITY.copy()
        else:
            an = a / length
            d = min(1.0, max(-1.0, an[2]))
            axis = np.cross(an, UNIT_Z)
            if np.linalg.norm(axis) < 1e-9:
                self.q = IDENTITY.copy() if d > 0.0 else quat_from_axis_angle([1.0, 0.0, 0.0], math.pi)
            else:
                self.q = quat_from_axis_angle(axis, math.acos(d))
        self.initialized = True

    def update(self, gx, gy, gz, ax, ay, az, dt_s):
        if not self.initialized:
            self.init_from_accel(ax, ay, az)
        if dt_s <= 0.0:
            return
        rate = math.sqrt(gx * gx + gy * gy + gz * gz)
        qg = self.q
        if rate > 1e-12:
            qg = quat_normalized(quat_mul(self.q, quat_from_axis_angle([gx, gy, gz], rate * dt_s)))
        an = math.sqrt(ax * ax + ay * ay + az * az)
        if an < 1e-6:
            self.q = qg
            return
        nax, nay, naz = ax / an, ay / an, az / an
        w, x, y, z = qg[0], qg[1], qg[2], qg[3]
        f1 = 2.0 * (x * z - w * y) - nax
        f2 = 2.0 * (w * x + y * z) - nay
        f3 = 1.0 - 2.0 * (x * x + y * y) - naz
        gw = -2.0 * y * f1 + 2.0 * x * f2
        gxq = 2.0 * z * f1 + 2.0 * w * f2 - 4.0 * x * f3
        gyq = -2.0 * w * f1 + 2.0 * z * f2 - 4.0 * y * f3
        gzq = 2.0 * x * f1 + 2.0 * y * f2
        gn = math.sqrt(gw * gw + gxq * gxq + gyq * gyq + gzq * gzq)
        if gn < 1e-12:
            self.q = qg
            return
        step = self.beta * dt_s
        self.q = quat_normalized(np.array([
            w - gw / gn * step, x - gxq / gn * step, y - gyq / gn * step, z - gzq / gn * step,
        ]))


# ----------------------------------------------------------------------------------------------
# MagGate (pdr/MagGate.kt)
# ----------------------------------------------------------------------------------------------

class MagGate:
    def __init__(self, ref_magnitude_ut, ref_dip_rad, tolerance):
        self.ref_magnitude_ut = ref_magnitude_ut
        self.ref_dip_rad = ref_dip_rad
        self.tolerance = tolerance

    @staticmethod
    def dip_of(world_field):
        w = np.asarray(world_field, dtype=np.float64)
        return np.arctan2(-w[..., 2], np.sqrt(w[..., 0] ** 2 + w[..., 1] ** 2))

    def passes(self, world_field):
        w = np.asarray(world_field, dtype=np.float64)
        mag = np.linalg.norm(w, axis=-1)
        if self.ref_magnitude_ut <= 0.0:
            return np.zeros(mag.shape, dtype=bool)
        ok = mag > 0.0
        ok &= np.abs(mag - self.ref_magnitude_ut) / self.ref_magnitude_ut <= self.tolerance
        ok &= np.abs(self.dip_of(w) - self.ref_dip_rad) <= self.tolerance
        return ok

    @staticmethod
    def from_start(mag_t, mag, orientation, tolerance, window_s=1.0):
        if len(mag_t) == 0:
            return None
        end = int(mag_t[0]) + int(window_s * 1e9)
        # Same prefix rule as the Kotlin loop: stop at the first sample after the window.
        beyond = np.nonzero(mag_t > end)[0]
        n = int(beyond[0]) if len(beyond) > 0 else len(mag_t)
        n = max(n, 1)
        w = quat_rotate(orientation.at(mag_t[:n]), mag[:n])
        return MagGate(float(np.mean(np.linalg.norm(w, axis=-1))), float(np.mean(MagGate.dip_of(w))), tolerance)


# ----------------------------------------------------------------------------------------------
# OrientationEstimator (pdr/OrientationEstimator.kt)
# ----------------------------------------------------------------------------------------------

class OrientationEstimator:
    def __init__(self, madgwick_beta=0.1, yaw_correction_time_constant_s=5.0, reference_window_s=1.0):
        self.madgwick_beta = madgwick_beta
        self.yaw_correction_time_constant_s = yaw_correction_time_constant_s
        self.reference_window_s = reference_window_s

    def estimate(self, log, config):
        """Returns (track, source, diagnostics)."""
        diag = {}
        game_t, game = log.game_rotation()
        fused_t, fused = log.fused_rotation()
        if len(game_t) > 0:
            base = OrientationTrack.from_rotation_samples(game_t, game)
            diag["orientationSource"] = "GAME"
            diag["rotationSamples"] = str(len(game_t))
            if config.useMagnetometer and len(fused_t) > 0:
                track = self.correct_yaw(base, fused_t, fused, log, config, diag)
            else:
                diag["yawCorrection"] = "disabled" if not config.useMagnetometer else "no fused rotation samples"
                track = base
            return track, "GAME", diag
        if len(fused_t) > 0:
            diag["orientationSource"] = "FUSED"
            diag["rotationSamples"] = str(len(fused_t))
            diag["yawCorrection"] = "not needed: fused rotation vector is the primary source"
            return OrientationTrack.from_rotation_samples(fused_t, fused), "FUSED", diag
        if len(log.gyro_t) > 0 and len(log.accel_t) > 0:
            diag["orientationSource"] = "MADGWICK"
            diag["yawCorrection"] = "not available without rotation vector samples"
            diag["madgwickBeta"] = str(self.madgwick_beta)
            return self.madgwick(log, config), "MADGWICK", diag
        diag["orientationSource"] = "NONE"
        diag["orientationWarning"] = "no rotation vector, gyro or accel samples; identity orientation used"
        return EMPTY_TRACK, "NONE", diag

    def madgwick(self, log, config):
        filt = MadgwickFilter(self.madgwick_beta)
        bias = config.gyro_bias()
        accel_t = log.accel_t
        accel = log.accel
        times = []
        quats = []
        ai = 0
        last_ns = None
        for k in range(len(log.gyro_t)):
            t = int(log.gyro_t[k])
            if last_ns is not None and t < last_ns:
                continue
            while ai + 1 < len(accel_t) and accel_t[ai + 1] <= t:
                ai += 1
            a = accel[ai]
            dt = 0.0 if last_ns is None else (t - last_ns) / 1e9
            if not filt.initialized:
                filt.init_from_accel(a[0], a[1], a[2])
            g = log.gyro[k]
            filt.update(g[0] - bias[0], g[1] - bias[1], g[2] - bias[2], a[0], a[1], a[2], dt)
            times.append(t)
            quats.append(filt.q.copy())
            last_ns = t
        if not times:
            return EMPTY_TRACK
        return OrientationTrack.build(np.array(times, dtype=np.int64), np.array(quats))

    def correct_yaw(self, base, fused_t, fused, log, config, diag):
        gate = MagGate.from_start(log.mag_t, log.mag, base, config.magGateTolerance, self.reference_window_s)
        if gate is None:
            diag["yawCorrection"] = "skipped: no magnetometer samples to gate the fused heading"
            return base
        diag["magRefMagnitudeUt"] = Diag.num(gate.ref_magnitude_ut, 2)
        diag["magRefDipDeg"] = Diag.num(math.degrees(gate.ref_dip_rad), 1)

        n = len(fused_t)
        qg = base.at(fused_t)
        mi = np.clip(floor_index(log.mag_t, fused_t), 0, len(log.mag_t) - 1)
        world = quat_rotate(qg, log.mag[mi])
        qf = quat_from_android_rotation_vector(fused[:, 0], fused[:, 1], fused[:, 2], fused[:, 3])
        delta = euler_zxy_yaw(quat_mul(qf, quat_conjugate(qg)))
        passes = gate.passes(world)
        passed = int(passes.sum())
        diag["magGatePassFraction"] = Diag.num(passed / n, 3)
        if passed == 0:
            diag["yawCorrection"] = "skipped: magnetic field never passed the gate"
            return base

        ref_end = int(fused_t[0]) + int(self.reference_window_s * 1e9)
        rc = rs = 0.0
        ref_count = 0
        for i in range(n):
            if fused_t[i] > ref_end and ref_count > 0:
                break
            if not passes[i]:
                continue
            rc += math.cos(delta[i])
            rs += math.sin(delta[i])
            ref_count += 1
        if ref_count == 0:
            diag["yawCorrection"] = "skipped: magnetic field disturbed during the reference second"
            return base
        ref = math.atan2(rs, rc)

        corr = np.zeros(n)
        ec = math.cos(ref)
        es = math.sin(ref)
        last_ns = int(fused_t[0])
        unwrapped = 0.0
        prev_angle = 0.0
        tau = self.yaw_correction_time_constant_s
        cos_d = np.cos(delta)
        sin_d = np.sin(delta)
        for i in range(n):
            ti = int(fused_t[i])
            dt = (ti - last_ns) / 1e9
            last_ns = ti
            if passes[i]:
                alpha = 1.0 if tau <= 0.0 else dt / (tau + dt)
                ec += (cos_d[i] - ec) * alpha
                es += (sin_d[i] - es) * alpha
            angle = Angles.wrap(math.atan2(es, ec) - ref)
            unwrapped += Angles.diff(angle, prev_angle)
            prev_angle = angle
            corr[i] = unwrapped
        diag["yawCorrectionFinalDeg"] = Diag.num(math.degrees(corr[n - 1]), 2)
        diag["yawCorrection"] = "applied"

        # Resample the correction onto the game track (linear between fused samples, held outside).
        t = base.times
        ci = np.clip(floor_index(fused_t, t), 0, n - 1)
        ci1 = np.clip(ci + 1, 0, n - 1)
        interp = (ci + 1 < n) & (fused_t[ci1] > fused_t[ci]) & (t > fused_t[ci])
        span = np.where(interp, (fused_t[ci1] - fused_t[ci]).astype(np.float64), 1.0)
        f = np.clip((t - fused_t[ci]).astype(np.float64) / span, 0.0, 1.0)
        c = np.where(interp, corr[ci] + (corr[ci1] - corr[ci]) * f, corr[ci])
        return OrientationTrack.build(t, quat_normalized(quat_mul(quat_yaw(c), base.q)))


# ----------------------------------------------------------------------------------------------
# WorldAccel (pdr/WorldAccel.kt)
# ----------------------------------------------------------------------------------------------

GRAVITY = 9.81


class WorldAccel:
    def __init__(self, t_ns, vertical, east, north, dropped):
        self.t_ns = t_ns
        self.vertical = vertical
        self.east = east
        self.north = north
        self.dropped = dropped

    @property
    def size(self):
        return len(self.t_ns)

    @property
    def is_empty(self):
        return len(self.t_ns) == 0

    def lower_bound(self, t):
        return int(lower_bound(self.t_ns, t))

    def vertical_swing(self, start, end):
        if start >= end:
            return 0.0
        v = self.vertical[start:end]
        return float(v.max() - v.min())

    @staticmethod
    def compute(accel_t, accel, orientation, gravity=GRAVITY):
        keep = keep_monotonic(accel_t)
        t = accel_t[keep]
        w = quat_rotate(orientation.at(t), accel[keep])
        return WorldAccel(t, w[:, 2] - gravity, w[:, 0], w[:, 1], int((~keep).sum()))


# ----------------------------------------------------------------------------------------------
# StepDetector (pdr/StepDetector.kt)
# ----------------------------------------------------------------------------------------------

class DetectedSteps:
    def __init__(self, t_ns, swing):
        self.t_ns = np.asarray(t_ns, dtype=np.int64)
        self.swing = np.asarray(swing, dtype=np.float64)

    @property
    def size(self):
        return len(self.t_ns)

    def lower_bound(self, t):
        return int(lower_bound(self.t_ns, t))


EMPTY_STEPS = DetectedSteps(np.zeros(0, np.int64), np.zeros(0))


class StepDetector:
    @staticmethod
    def filter(t_ns, x, low_hz, high_hz):
        """Two cascaded first-order low-passes then two high-passes, stepped with the real dt.
        Returns (band, low)."""
        n = len(x)
        band = np.zeros(n)
        low = np.zeros(n)
        if n == 0:
            return band, low
        rc_low = 1.0 / (2.0 * math.pi * high_hz) if high_hz > 0.0 else 0.0
        rc_high = 1.0 / (2.0 * math.pi * low_hz) if low_hz > 0.0 else 0.0
        lp1 = lp2 = float(x[0])
        hp1 = hp2 = 0.0
        hp_in1 = lp2
        hp_in2 = 0.0
        low[0] = lp2
        band[0] = 0.0
        tl = t_ns.tolist()
        xl = x.tolist()
        for i in range(1, n):
            dt = (tl[i] - tl[i - 1]) / 1e9
            xi = xl[i]
            if dt <= 0.0:
                low[i] = low[i - 1]
                band[i] = band[i - 1]
                continue
            a_low = dt / (rc_low + dt) if rc_low > 0.0 else 1.0
            lp1 += (xi - lp1) * a_low
            lp2 += (lp1 - lp2) * a_low
            low[i] = lp2
            a_high = rc_high / (rc_high + dt) if rc_high > 0.0 else 0.0
            h1 = a_high * (hp1 + lp2 - hp_in1)
            hp_in1 = lp2
            hp1 = h1
            h2 = a_high * (hp2 + h1 - hp_in2)
            hp_in2 = h1
            hp2 = h2
            band[i] = h2 if rc_high > 0.0 else lp2
        return band, low

    @staticmethod
    def detect(accel, config):
        n = accel.size
        if n == 0:
            return EMPTY_STEPS
        band, low = StepDetector.filter(accel.t_ns, accel.vertical, config.stepBandLowHz, config.stepBandHighHz)
        min_interval_ns = int(config.stepMinIntervalS * 1e9)
        times = []
        swings = []
        in_cycle = False
        positive = band[0] > 0.0
        peak = 0.0
        peak_ns = 0
        valley = 0.0
        low_max = low_min = 0.0
        last_step_ns = None
        t = accel.t_ns.tolist()
        bl = band.tolist()
        ll = low.tolist()
        for i in range(1, n):
            b = bl[i]
            now_positive = b > 0.0
            if in_cycle:
                if ll[i] > low_max:
                    low_max = ll[i]
                if ll[i] < low_min:
                    low_min = ll[i]
                if now_positive and not positive:
                    accepted = peak - valley >= config.stepMinSwing and (
                        last_step_ns is None or peak_ns - last_step_ns >= min_interval_ns)
                    if accepted:
                        times.append(peak_ns)
                        swings.append(low_max - low_min)
                        last_step_ns = peak_ns
                    peak = b
                    peak_ns = t[i]
                    valley = 0.0
                    low_max = low_min = ll[i]
                elif now_positive:
                    if b > peak:
                        peak = b
                        peak_ns = t[i]
                else:
                    if b < valley:
                        valley = b
            elif now_positive and not positive:
                in_cycle = True
                peak = b
                peak_ns = t[i]
                valley = 0.0
                low_max = low_min = ll[i]
            positive = now_positive
        return DetectedSteps(np.array(times, dtype=np.int64), np.array(swings))

    @staticmethod
    def from_hardware(steps_t, accel, config):
        if accel.is_empty:
            low = np.zeros(0)
        else:
            _band, low = StepDetector.filter(accel.t_ns, accel.vertical, config.stepBandLowHz, config.stepBandHighHz)
        times = []
        swings = []
        prev_ns = None
        for s in steps_t.tolist():
            if prev_ns is not None and s < prev_ns:
                continue
            start = s - 600_000_000 if prev_ns is None else prev_ns
            times.append(s)
            swings.append(StepDetector._swing(low, accel, start, s))
            prev_ns = s
        return DetectedSteps(np.array(times, dtype=np.int64), np.array(swings))

    @staticmethod
    def band_pass(t_ns, x, low_hz, high_hz):
        return StepDetector.filter(t_ns, x, low_hz, high_hz)[0]

    @staticmethod
    def _swing(low, accel, from_ns, to_ns):
        start = accel.lower_bound(from_ns)
        end = accel.lower_bound(to_ns + 1)
        if start >= end:
            return 0.0
        seg = low[start:end]
        return float(seg.max() - seg.min())


# ----------------------------------------------------------------------------------------------
# StrideModel (pdr/StrideModel.kt)
# ----------------------------------------------------------------------------------------------

class StrideModel:
    @staticmethod
    def stride_m(config, swing):
        if config.weinbergK > 0.0 and swing > 0.0:
            return config.weinbergK * swing ** 0.25
        return config.strideLengthM

    @staticmethod
    def uses_weinberg(config):
        return config.weinbergK > 0.0


# ----------------------------------------------------------------------------------------------
# HeadingEstimator (pdr/HeadingEstimator.kt)
# ----------------------------------------------------------------------------------------------

FORWARD = "FORWARD"
CAMERA = "CAMERA"


class DeviceHeading:
    UPRIGHT_COS = 0.8

    @staticmethod
    def heading_rad(q, axis):
        return forward_heading_rad(q) if axis == FORWARD else camera_heading_rad(q)

    @staticmethod
    def choose_axis(track, times, start, end):
        if start >= end:
            return FORWARD
        up = np.abs(quat_rotate(track.at(times[start:end]), UNIT_Y)[:, 2])
        return CAMERA if float(np.sum(up)) / (end - start) > DeviceHeading.UPRIGHT_COS else FORWARD


class HeadingOffsetEstimator:
    MIN_SAMPLES = 50
    MIN_AXIS_RATIO = 1.3

    @staticmethod
    def estimate(accel, from_ns, to_ns, device_heading_rad, previous_walking_heading_rad, fallback_offset_rad):
        start = accel.lower_bound(from_ns)
        end = accel.lower_bound(to_ns)
        n = end - start
        if n < HeadingOffsetEstimator.MIN_SAMPLES:
            return None
        e = accel.east[start:end]
        no = accel.north[start:end]
        me = float(np.sum(e)) / n
        mn = float(np.sum(no)) / n
        de = e - me
        dn = no - mn
        see = float(np.sum(de * de))
        sen = float(np.sum(de * dn))
        snn = float(np.sum(dn * dn))
        theta = 0.5 * math.atan2(2.0 * sen, see - snn)
        c = math.cos(theta)
        s = math.sin(theta)
        major = see * c * c + 2.0 * sen * c * s + snn * s * s
        minor = see * s * s - 2.0 * sen * c * s + snn * c * c
        if major <= 0.0 or major < HeadingOffsetEstimator.MIN_AXIS_RATIO * minor:
            return None
        h1 = Angles.wrap(math.atan2(c, s))
        h2 = Angles.wrap(h1 + math.pi)
        if previous_walking_heading_rad is not None:
            d1 = abs(Angles.diff(h1, previous_walking_heading_rad))
            d2 = abs(Angles.diff(h2, previous_walking_heading_rad))
            chosen = h1 if d1 <= d2 else h2
        else:
            o1 = Angles.diff(h1, device_heading_rad)
            o2 = Angles.diff(h2, device_heading_rad)
            d1 = abs(Angles.diff(o1, fallback_offset_rad))
            d2 = abs(Angles.diff(o2, fallback_offset_rad))
            chosen = h1 if d1 <= d2 else h2
        return Angles.diff(chosen, device_heading_rad)


# ----------------------------------------------------------------------------------------------
# AltitudeTrack (pdr/AltitudeTrack.kt)
# ----------------------------------------------------------------------------------------------

class AltitudeTrack:
    def __init__(self, times, heights, p0_hpa):
        self.times = times
        self.heights = heights
        self.p0_hpa = p0_hpa

    @property
    def size(self):
        return len(self.times)

    def at(self, t_ns):
        t = np.asarray(t_ns, dtype=np.int64)
        scalar = t.ndim == 0
        t = np.atleast_1d(t)
        if len(self.times) == 0:
            out = np.zeros(len(t))
            return float(out[0]) if scalar else out
        n = len(self.times)
        i = floor_index(self.times, t)
        i0 = np.clip(i, 0, n - 1)
        i1 = np.clip(i + 1, 0, n - 1)
        t0 = self.times[i0]
        t1 = self.times[i1]
        interior = (i >= 0) & (i < n - 1) & (t1 > t0)
        span = np.where(interior, (t1 - t0).astype(np.float64), 1.0)
        f = np.where(interior, (t - t0).astype(np.float64) / span, 0.0)
        out = self.heights[i0] + (self.heights[i1] - self.heights[i0]) * f
        return float(out[0]) if scalar else out

    @staticmethod
    def height_above_reference(p_hpa, p0_hpa):
        return 44330.0 * (1.0 - (p_hpa / p0_hpa) ** 0.1903)

    @staticmethod
    def from_baro(baro_t, hpa, smoothing_s, reference_window_s=1.0):
        if len(baro_t) == 0:
            return None
        ref_end = int(baro_t[0]) + int(reference_window_s * 1e9)
        beyond = np.nonzero(baro_t > ref_end)[0]
        count = max(int(beyond[0]) if len(beyond) > 0 else len(baro_t), 1)
        p0 = float(np.sum(hpa[:count])) / count
        times = []
        heights = []
        filtered = 0.0
        last_ns = None
        for t, p in zip(baro_t.tolist(), hpa.tolist()):
            if (last_ns is not None and t < last_ns) or p <= 0.0:
                continue
            raw = AltitudeTrack.height_above_reference(p, p0)
            dt = 0.0 if last_ns is None else (t - last_ns) / 1e9
            if smoothing_s <= 0.0:
                alpha = 1.0
            elif dt > 0.0:
                alpha = dt / (smoothing_s + dt)
            else:
                alpha = 0.0
            filtered += (raw - filtered) * alpha
            times.append(t)
            heights.append(filtered)
            last_ns = t
        if not times:
            return None
        return AltitudeTrack(np.array(times, dtype=np.int64), np.array(heights), p0)


# ----------------------------------------------------------------------------------------------
# PathPoint (core/PathResult.kt) and PdrSolver (pdr/PdrSolver.kt)
# ----------------------------------------------------------------------------------------------

class PathPoint:
    __slots__ = ("t_ns", "p", "source", "heading_rad", "step_index")

    def __init__(self, t_ns, p, source, heading_rad, step_index=-1):
        self.t_ns = int(t_ns)
        self.p = np.asarray(p, dtype=np.float64)
        self.source = source
        self.heading_rad = float(heading_rad)
        self.step_index = int(step_index)

    def with_p(self, p):
        return PathPoint(self.t_ns, p, self.source, self.heading_rad, self.step_index)

    def to_dict(self):
        return {
            "tNs": self.t_ns,
            "p": {"x": float(self.p[0]), "y": float(self.p[1]), "z": float(self.p[2])},
            "source": self.source,
            "headingRad": self.heading_rad,
            "stepIndex": self.step_index,
        }


class HeadingSegment:
    def __init__(self, from_ns, axis, offset_rad, estimated):
        self.from_ns = from_ns
        self.axis = axis
        self.offset_rad = offset_rad
        self.estimated = estimated


class PdrContext:
    def __init__(self, log, config, orientation, orientation_source, world_accel, steps, software_step_count,
                 hardware_step_count, heading_segments, step_heading_rad, step_stride_m, altitude, diagnostics):
        self.log = log
        self.config = config
        self.orientation = orientation
        self.orientation_source = orientation_source
        self.world_accel = world_accel
        self.steps = steps
        self.software_step_count = software_step_count
        self.hardware_step_count = hardware_step_count
        self.heading_segments = heading_segments
        self.step_heading_rad = step_heading_rad
        self.step_stride_m = step_stride_m
        self.altitude = altitude
        self.diagnostics = diagnostics

    def segment_at(self, t_ns):
        seg = self.heading_segments[0]
        for s in self.heading_segments:
            if s.from_ns <= t_ns:
                seg = s
            else:
                break
        return seg

    def walking_heading_at(self, t_ns):
        seg = self.segment_at(t_ns)
        return Angles.wrap(float(DeviceHeading.heading_rad(self.orientation.at(t_ns), seg.axis)) + seg.offset_rad)


LONG_MAX = 2 ** 63 - 1


class PdrSolver:
    def __init__(self, orientation_estimator=None, still_gap_s=2.0, reorient_steps=10, reorient_reference_steps=5):
        self.orientation_estimator = orientation_estimator or OrientationEstimator()
        self.still_gap_s = still_gap_s
        self.reorient_steps = reorient_steps
        self.reorient_reference_steps = reorient_reference_steps

    def prepare(self, log, config):
        diag = {}
        track, source, odiag = self.orientation_estimator.estimate(log, config)
        diag.update(odiag)
        world = WorldAccel.compute(log.accel_t, log.accel, track)
        if world.dropped > 0:
            diag["accelSamplesDropped"] = str(world.dropped)

        software = StepDetector.detect(world, config)
        hardware = StepDetector.from_hardware(log.steps_t, world, config)
        use_hardware = config.preferHardwareSteps and hardware.size > 0
        steps = hardware if use_hardware else software
        diag["softwareSteps"] = str(software.size)
        diag["hardwareSteps"] = str(hardware.size)
        diag["stepsUsed"] = "hardware" if use_hardware else "software"
        if config.preferHardwareSteps and hardware.size == 0:
            diag["stepsNote"] = "hardware steps preferred but none logged"

        strides = np.array([StrideModel.stride_m(config, s) for s in steps.swing.tolist()], dtype=np.float64)
        diag["strideModel"] = "weinberg" if StrideModel.uses_weinberg(config) else "fixed"
        if steps.size > 0:
            diag["meanStrideM"] = Diag.num(float(strides.sum()) / steps.size, 3)

        segments = []
        headings = np.zeros(steps.size)
        self.build_headings(log, config, track, world, steps, segments, headings, diag)

        altitude = AltitudeTrack.from_baro(log.baro_t, log.baro_hpa, config.baroSmoothingS)
        diag["baro"] = "absent" if altitude is None else "present"
        if altitude is not None:
            diag["baroP0hPa"] = Diag.num(altitude.p0_hpa, 2)

        return PdrContext(log, config, track, source, world, steps, software.size, hardware.size, segments,
                          headings, strides, altitude, diag)

    def solve_segment(self, ctx, from_ns, to_ns, start, start_heading_rad):
        steps = ctx.steps
        first = steps.lower_bound(from_ns)
        last = steps.lower_bound(to_ns)
        correction = 0.0
        if start_heading_rad is not None:
            correction = Angles.diff(start_heading_rad, ctx.walking_heading_at(from_ns))
        out = []
        x, y, z = float(start[0]), float(start[1]), float(start[2])
        prev_ns = from_ns
        altitude = ctx.altitude
        hold = ctx.config.baroHoldWhenStill
        for i in range(first, last):
            t = int(steps.t_ns[i])
            h = Angles.wrap(ctx.step_heading_rad[i] + correction)
            d = ctx.step_stride_m[i]
            x += d * math.sin(h)
            y += d * math.cos(h)
            if altitude is not None:
                gap_s = (t - prev_ns) / 1e9
                if not hold or gap_s <= self.still_gap_s:
                    z += altitude.at(t) - altitude.at(prev_ns)
            out.append(PathPoint(t, [x, y, z], "PDR", h, i))
            prev_ns = t
        return out

    def solve(self, log, config):
        """Whole trip: (points, ctx) with the start point at the origin followed by one point per step."""
        ctx = self.prepare(log, config)
        start_ns = log.first_timestamp_ns()
        steps = self.solve_segment(ctx, start_ns, LONG_MAX, np.zeros(3), None)
        start_heading = steps[0].heading_rad if steps else ctx.walking_heading_at(start_ns)
        return [PathPoint(start_ns, np.zeros(3), "PDR", start_heading, -1)] + steps, ctx

    def build_headings(self, log, config, track, world, steps, segments, headings, diag):
        boundaries = [log.first_timestamp_ns()]
        for a in log.annotations:
            if a["kind"] == "REORIENT" and a["tNs"] > boundaries[-1]:
                boundaries.append(a["tNs"])
        diag["reorientCount"] = str(len(boundaries) - 1)

        offset = config.headingOffsetRad
        estimated = []
        for b in range(len(boundaries)):
            from_ns = boundaries[b]
            to_ns = boundaries[b + 1] if b + 1 < len(boundaries) else LONG_MAX
            first = steps.lower_bound(from_ns)
            last = steps.lower_bound(to_ns)
            if first < last:
                axis = DeviceHeading.choose_axis(track, steps.t_ns, first, last)
            else:
                axis = DeviceHeading.choose_axis(track, np.array([from_ns], dtype=np.int64), 0, 1)
            was_estimated = False
            if b > 0 and first < last:
                window_end = min(last - 1, first + self.reorient_steps - 1)
                device_headings = DeviceHeading.heading_rad(track.at(steps.t_ns[first:window_end + 1]), axis)
                mean_device = Angles.circular_mean(device_headings, 0, len(device_headings))
                previous = Angles.circular_mean(headings, max(0, first - self.reorient_reference_steps), first)
                if mean_device is not None:
                    est = HeadingOffsetEstimator.estimate(
                        world, int(steps.t_ns[first]), int(steps.t_ns[window_end]) + 1, mean_device, previous, offset)
                    if est is not None:
                        offset = est
                        was_estimated = True
                estimated.append(Diag.num(math.degrees(offset), 1) if was_estimated else "kept")
            segments.append(HeadingSegment(from_ns, axis, offset, was_estimated))
            if first < last:
                headings[first:last] = Angles.wrap(
                    DeviceHeading.heading_rad(track.at(steps.t_ns[first:last]), axis) + offset)
        diag["headingAxis"] = ";".join(s.axis for s in segments)
        diag["headingOffsetDeg"] = Diag.num(math.degrees(config.headingOffsetRad), 1)
        if estimated:
            diag["reorientOffsetsDeg"] = ";".join(estimated)


# ----------------------------------------------------------------------------------------------
# post: LoopClosure, Smoothing, PathBuilder (pipeline/.../post)
# ----------------------------------------------------------------------------------------------

class PathBuilder:
    # 2: results carry rawPoints, the path before loop closure and smoothing.
    PIPELINE_VERSION = 2

    @staticmethod
    def nearest_index(points, t_ns):
        if not points:
            return -1
        times = np.array([p.t_ns for p in points], dtype=np.int64)
        lo = min(int(np.searchsorted(times, t_ns, side="left")), len(points) - 1)
        if lo > 0 and abs(int(times[lo - 1]) - t_ns) <= abs(int(times[lo]) - t_ns):
            return lo - 1
        return lo

    @staticmethod
    def place_annotations(points, annotations):
        out = []
        for a in annotations:
            p = np.zeros(3) if not points else points[PathBuilder.nearest_index(points, a["tNs"])].p
            out.append({"tNs": a["tNs"], "kind": a["kind"], "note": a["note"], "p": _vec_dict(p)})
        return out

    @staticmethod
    def place_keyframes(points, keyframes):
        out = []
        for k in keyframes:
            near = None if not points else points[PathBuilder.nearest_index(points, k["tNs"])]
            out.append({
                "tNs": k["tNs"], "fileName": k["fileName"],
                "p": _vec_dict(near.p if near is not None else np.zeros(3)),
                "headingRad": near.heading_rad if near is not None else 0.0,
            })
        return out

    @staticmethod
    def stats(points, duration_s, step_count, closure_error_m):
        distance = 0.0
        min_z = max_z = 0.0
        vio = 0
        for i, pt in enumerate(points):
            if i == 0:
                min_z = max_z = float(pt.p[2])
            else:
                distance += float(np.linalg.norm(pt.p - points[i - 1].p))
                min_z = min(min_z, float(pt.p[2]))
                max_z = max(max_z, float(pt.p[2]))
            if pt.source == "VIO":
                vio += 1
        return {
            "distanceM": distance,
            "durationS": duration_s,
            "stepCount": step_count,
            "minZ": min_z,
            "maxZ": max_z,
            "closureErrorM": closure_error_m,
            "vioFraction": 0.0 if not points else vio / len(points),
        }

    @staticmethod
    def build(config, points, log, step_count, closure_error_m, diagnostics, point_cloud=None,
              pipeline_version=PIPELINE_VERSION, raw_points=None):
        # rawPoints is stored only when post-processing moved something (same rule as the Kotlin builder).
        raw = [] if raw_points is None or raw_points is points else raw_points
        return {
            "pipelineVersion": pipeline_version,
            "config": config.to_dict(),
            "points": [p.to_dict() for p in points],
            "annotations": PathBuilder.place_annotations(points, log.annotations),
            "keyframes": PathBuilder.place_keyframes(points, log.keyframes),
            "pointCloud": point_cloud or [],
            "stats": PathBuilder.stats(points, log.duration_s(), step_count, closure_error_m),
            "diagnostics": dict(diagnostics),
            "rawPoints": [p.to_dict() for p in raw],
        }


def _vec_dict(p):
    return {"x": float(p[0]), "y": float(p[1]), "z": float(p[2])}


class LoopClosure:
    @staticmethod
    def apply(points, closure_times_ns):
        """Returns (points, closure_error_m) or None when nothing can be closed."""
        n = len(points)
        if n < 2 or not closure_times_ns:
            return None
        indices = []
        for t in sorted(closure_times_ns):
            k = PathBuilder.nearest_index(points, t)
            if k > 0 and k not in indices:
                indices.append(k)
        if not indices:
            return None
        pos = np.array([p.p for p in points])
        seg = np.linalg.norm(pos[1:] - pos[:-1], axis=1)
        cum = np.concatenate([[0.0], np.cumsum(seg)])
        origin = pos[0].copy()
        closure_error_m = float(np.linalg.norm(pos[indices[-1]] - origin))
        prev = 0
        for k in indices:
            e = pos[k] - origin
            span = cum[k] - cum[prev]
            for i in range(prev + 1, k + 1):
                f = (cum[i] - cum[prev]) / span if abs(span) > 1e-12 else (i - prev) / (k - prev)
                pos[i] -= e * f
            pos[k + 1:] -= e
            prev = k
        return [points[i].with_p(pos[i]) for i in range(n)], closure_error_m


class Smoothing:
    @staticmethod
    def moving_average(points, window):
        n = len(points)
        if window <= 1 or n < 3:
            return points
        half = window // 2
        pos = np.array([p.p for p in points])
        out = [points[0]]
        for i in range(1, n - 1):
            start = max(0, i - half)
            end = min(n - 1, i + half)
            out.append(points[i].with_p(pos[start:end + 1].sum(axis=0) / (end - start + 1)))
        out.append(points[n - 1])
        return out


# ----------------------------------------------------------------------------------------------
# PdrProcessor (pdr/PdrProcessor.kt)
# ----------------------------------------------------------------------------------------------

class PdrProcessor:
    def __init__(self, solver=None):
        self.solver = solver or PdrSolver()
        # Intermediate products kept for plots and for comparing with the Kotlin implementation.
        self.context = None
        self.raw_points = None
        self.closed_points = None

    def process(self, log, config):
        points, ctx = self.solver.solve(log, config)
        self.context = ctx
        self.raw_points = points
        diagnostics = dict(ctx.diagnostics)
        closure_error_m = None
        closures = [a["tNs"] for a in log.annotations if a["kind"] == "LOOP_CLOSED"]
        if config.loopClosure and closures:
            closed = LoopClosure.apply(points, closures)
            if closed is not None:
                points, closure_error_m = closed
                diagnostics["loopClosure"] = "applied at %d annotation(s)" % len(closures)
            else:
                diagnostics["loopClosure"] = "skipped: no path before the annotation"
        elif closures:
            diagnostics["loopClosure"] = "disabled"
        self.closed_points = points
        points = Smoothing.moving_average(points, config.smoothingWindow)
        return PathBuilder.build(config, points, log, ctx.steps.size, closure_error_m, diagnostics,
                                 raw_points=self.raw_points)


# ----------------------------------------------------------------------------------------------
# Reporting, export, plots
# ----------------------------------------------------------------------------------------------

def rate_hz(times):
    if len(times) < 2:
        return 0.0
    span = (int(times[-1]) - int(times[0])) / 1e9
    return (len(times) - 1) / span if span > 0 else 0.0


def print_summary(path, log):
    print("== %s ==" % path)
    print("records: %d  unknown: %d  truncated: %s" % (log.records, log.unknown_records, log.truncated))
    t0 = log.first_timestamp_ns()
    print("duration: %.3f s  (t0 = %d ns)" % (log.duration_s(), t0))
    rates = {
        "ACCEL": log.accel_t, "GYRO": log.gyro_t, "MAG": log.mag_t, "BARO": log.baro_t,
        "GAME_ROT": log.game_rotation()[0], "ROT_VEC": log.fused_rotation()[0], "STEP": log.steps_t,
        "ACCEL_UNCAL": log.accel_uncal_t, "GYRO_UNCAL": log.gyro_uncal_t, "MAG_UNCAL": log.mag_uncal_t,
        "POSE": log.poses_t,
    }
    for name, count in log.counts.items():
        line = "  %-12s %8d" % (name, count)
        if name in rates and len(rates[name]) > 1:
            line += "  %7.1f Hz" % rate_hz(rates[name])
        print(line)
    if log.poses_t.size:
        tracking = sum(1 for s in log.poses_tracking if s == "TRACKING")
        print("  poses tracking: %d / %d" % (tracking, len(log.poses_tracking)))
    for a in log.annotations:
        print("  annotation %+9.3f s  %-11s %s" % ((a["tNs"] - t0) / 1e9, a["kind"], a["note"]))
    for t, kind in zip(log.events_t.tolist(), log.events_kind):
        print("  event      %+9.3f s  %s" % ((t - t0) / 1e9, kind))
    if log.meta is None:
        print("meta: missing or unparsable")
    else:
        m = log.meta
        print("meta: app=%s device=%s sdk=%s mode=%s carry=%s" % (
            m.get("appVersion"), m.get("deviceModel"), m.get("androidSdk"), m.get("mode"), m.get("carryPosition")))
        if m.get("notes"):
            print("  notes: %s" % m["notes"])
        if m.get("sensorPeriodsUs"):
            print("  sensorPeriodsUs: %s" % json.dumps(m["sensorPeriodsUs"]))


def print_config(config):
    print("config:")
    for key, value in config.to_dict().items():
        print("  %-28s %s" % (key, json.dumps(value)))


def print_stats(result, label="stats"):
    s = result["stats"]
    closure = "none" if s.get("closureErrorM") is None else "%.3f m" % s["closureErrorM"]
    fmt = "%s: distance %.3f m  steps %d  duration %.1f s  z %.2f..%.2f m (range %.2f m)  closure error %s  points %d"
    print(fmt % (label, s["distanceM"], s["stepCount"], s["durationS"], s["minZ"], s["maxZ"], s["maxZ"] - s["minZ"],
                 closure, len(result["points"])))
    if result["points"]:
        last = result["points"][-1]["p"]
        print("  end point (%.3f, %.3f, %.3f) m, %.3f m from the origin" % (
            last["x"], last["y"], last["z"], math.sqrt(last["x"] ** 2 + last["y"] ** 2 + last["z"] ** 2)))


def print_diagnostics(result):
    print("diagnostics:")
    for key, value in result["diagnostics"].items():
        print("  %-24s %s" % (key, value))


def write_outputs(result, out_dir, stem):
    json_path = os.path.join(out_dir, stem + ".json")
    with open(json_path, "w") as f:
        json.dump(result, f, indent=1)
    csv_path = os.path.join(out_dir, stem + ".csv")
    with open(csv_path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["tNs", "x", "y", "z", "source", "headingRad", "stepIndex"])
        for p in result["points"]:
            w.writerow([p["tNs"], "%.6f" % p["p"]["x"], "%.6f" % p["p"]["y"], "%.6f" % p["p"]["z"],
                        p["source"], "%.6f" % p["headingRad"], p["stepIndex"]])
    return json_path, csv_path


def points_xyz(point_dicts):
    if not point_dicts:
        return np.zeros((0, 3)), np.zeros(0, np.int64)
    xyz = np.array([[p["p"]["x"], p["p"]["y"], p["p"]["z"]] for p in point_dicts])
    t = np.array([p["tNs"] for p in point_dicts], dtype=np.int64)
    return xyz, t


def cumulative_distance(xyz):
    if len(xyz) == 0:
        return np.zeros(0)
    return np.concatenate([[0.0], np.cumsum(np.linalg.norm(xyz[1:] - xyz[:-1], axis=1))])


def compare_results(result, other):
    """Prints how a PathResult from the app differs from this replay; returns the parsed points."""
    xyz_a, t_a = points_xyz(result["points"])
    xyz_b, t_b = points_xyz(other.get("points", []))
    sb = other.get("stats", {})
    sa = result["stats"]
    print("compare: app result has %d points, pipelineVersion %s" % (len(xyz_b), other.get("pipelineVersion")))
    for key in ("distanceM", "stepCount", "durationS", "minZ", "maxZ", "closureErrorM"):
        va = sa.get(key)
        vb = sb.get(key)
        if isinstance(va, (int, float)) and isinstance(vb, (int, float)):
            print("  %-14s replay %10.4f  app %10.4f  diff %+.4f" % (key, va, vb, va - vb))
        else:
            print("  %-14s replay %s  app %s" % (key, va, vb))
    if len(xyz_a) and len(xyz_b):
        end = float(np.linalg.norm(xyz_a[-1] - xyz_b[-1]))
        print("  end points differ by %.4f m" % end)
        common, ia, ib = np.intersect1d(t_a, t_b, return_indices=True)
        if len(common):
            d = np.linalg.norm(xyz_a[ia] - xyz_b[ib], axis=1)
            print("  %d points share a timestamp: position error mean %.4f m, max %.4f m" % (
                len(common), float(d.mean()), float(d.max())))
        else:
            print("  no shared timestamps; only stats and end points compared")
    dif = set(result["diagnostics"].items()) ^ set(other.get("diagnostics", {}).items())
    if dif:
        keys = sorted({k for k, _ in dif})
        print("  diagnostics differ in: %s" % ", ".join(keys))
    return xyz_b


# Reference categorical palette from the dataviz skill (light surface), used in a fixed order.
C_BLUE = "#2a78d6"
C_ORANGE = "#eb6834"
C_AQUA = "#1baf7a"
C_YELLOW = "#eda100"
C_MAGENTA = "#e87ba4"
C_GREEN = "#008300"
C_VIOLET = "#4a3aa7"
C_RED = "#e34948"
C_GRID = "#e6e6e3"
C_TEXT = "#4a4a48"


def _style_axes(ax):
    ax.grid(True, color=C_GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for spine in ("top", "right"):
        ax.spines[spine].set_visible(False)
    for spine in ("left", "bottom"):
        ax.spines[spine].set_color(C_GRID)
    ax.tick_params(colors=C_TEXT, labelsize=9)
    ax.xaxis.label.set_color(C_TEXT)
    ax.yaxis.label.set_color(C_TEXT)


def make_plots(log, config, result, processor, out_dir, compare_xyz=None, title=""):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    ctx = processor.context
    xyz, t_pts = points_xyz(result["points"])
    raw_xyz = np.array([p.p for p in processor.raw_points]) if processor.raw_points else np.zeros((0, 3))
    t0 = log.first_timestamp_ns()
    written = []

    # --- top-down path -------------------------------------------------------------------------
    fig, ax = plt.subplots(figsize=(8, 8))
    if len(raw_xyz) and result["stats"].get("closureErrorM") is not None:
        ax.plot(raw_xyz[:, 0], raw_xyz[:, 1], color=C_ORANGE, linewidth=1.5, linestyle="--", label="PDR before closure")
    if len(xyz):
        ax.plot(xyz[:, 0], xyz[:, 1], color=C_BLUE, linewidth=2, label="replay path")
        ax.plot(xyz[:, 0], xyz[:, 1], ".", color=C_BLUE, markersize=4)
        ax.plot(xyz[0, 0], xyz[0, 1], "o", color=C_GREEN, markersize=9, label="start")
        ax.plot(xyz[-1, 0], xyz[-1, 1], "s", color=C_RED, markersize=8, label="end")
    if compare_xyz is not None and len(compare_xyz):
        # Wide and translucent, drawn under the replay line, so an identical path is still visible.
        ax.plot(compare_xyz[:, 0], compare_xyz[:, 1], color=C_AQUA, linewidth=5, alpha=0.45,
                label="app result (--compare)", zorder=1)
    for a in result["annotations"]:
        ax.plot(a["p"]["x"], a["p"]["y"], "^", color=C_VIOLET, markersize=8)
        ax.annotate("%s %s" % (a["kind"], a["note"]), (a["p"]["x"], a["p"]["y"]), textcoords="offset points",
                    xytext=(6, 6), fontsize=8, color=C_TEXT)
    for k in result["keyframes"]:
        ax.plot(k["p"]["x"], k["p"]["y"], "D", color=C_YELLOW, markersize=6)
    ax.set_aspect("equal", adjustable="datalim")
    ax.set_xlabel("east (m)")
    ax.set_ylabel("north (m)")
    ax.set_title("%s top-down path" % title, color=C_TEXT)
    ax.legend(loc="best", fontsize=8, frameon=False)
    _style_axes(ax)
    p = os.path.join(out_dir, "path_top.png")
    fig.savefig(p, dpi=130, bbox_inches="tight")
    plt.close(fig)
    written.append(p)

    # --- side view -----------------------------------------------------------------------------
    fig, ax = plt.subplots(figsize=(9, 3.5))
    if len(xyz):
        ax.plot(cumulative_distance(xyz), xyz[:, 2], color=C_BLUE, linewidth=2, label="replay path")
    if compare_xyz is not None and len(compare_xyz):
        ax.plot(cumulative_distance(compare_xyz), compare_xyz[:, 2], color=C_AQUA, linewidth=5, alpha=0.45,
                label="app result (--compare)", zorder=1)
    ax.set_xlabel("distance along path (m)")
    ax.set_ylabel("z (m)")
    ax.set_title("%s side view" % title, color=C_TEXT)
    if compare_xyz is not None and len(compare_xyz):
        ax.legend(loc="best", fontsize=8, frameon=False)
    _style_axes(ax)
    p = os.path.join(out_dir, "path_side.png")
    fig.savefig(p, dpi=130, bbox_inches="tight")
    plt.close(fig)
    written.append(p)

    # --- signal panels -------------------------------------------------------------------------
    fig, axes = plt.subplots(4, 1, figsize=(13, 12), sharex=True)
    legend_kw = {"loc": "upper left", "bbox_to_anchor": (1.01, 1.0), "fontsize": 8, "frameon": False}
    wa = ctx.world_accel
    if wa.size:
        ts = (wa.t_ns - t0) / 1e9
        band = StepDetector.band_pass(wa.t_ns, wa.vertical, config.stepBandLowHz, config.stepBandHighHz)
        axes[0].plot(ts, wa.vertical, color=C_GRID, linewidth=0.8, label="vertical accel (gravity removed)")
        axes[0].plot(ts, band, color=C_BLUE, linewidth=1.2, label="band-pass %.1f-%.1f Hz" % (
            config.stepBandLowHz, config.stepBandHighHz))
        st = ctx.steps.t_ns
        if st.size:
            idx = np.clip(lower_bound(wa.t_ns, st), 0, wa.size - 1)
            axes[0].plot((st - t0) / 1e9, band[idx], "v", color=C_ORANGE, markersize=7,
                         label="steps used (%s)" % ctx.diagnostics.get("stepsUsed", ""))
        if log.steps_t.size:
            axes[0].plot((log.steps_t - t0) / 1e9, np.full(log.steps_t.size, float(np.nanmin(band)) - 0.3), "|",
                         color=C_VIOLET, markersize=10, label="hardware steps")
    axes[0].set_ylabel("m/s^2")
    axes[0].set_title("%s vertical acceleration and detected steps" % title, color=C_TEXT)
    axes[0].legend(**legend_kw)

    track = ctx.orientation
    if track.size:
        axis = ctx.heading_segments[0].axis if ctx.heading_segments else FORWARD
        stride = max(1, track.size // 5000)
        tt = (track.times[::stride] - t0) / 1e9
        dev = np.degrees(DeviceHeading.heading_rad(track.q[::stride], axis))
        # Break the line where the heading wraps around +-180 degrees instead of drawing a vertical jump.
        dev = np.where(np.concatenate([[False], np.abs(np.diff(dev)) > 180.0]), np.nan, dev)
        axes[1].plot(tt, dev, color=C_GRID, linewidth=1, label="device heading (%s axis)" % axis)
    if ctx.steps.size:
        axes[1].plot((ctx.steps.t_ns - t0) / 1e9, np.degrees(ctx.step_heading_rad), ".", color=C_BLUE, markersize=5,
                     label="walking heading per step")
    for seg in ctx.heading_segments[1:]:
        axes[1].axvline((seg.from_ns - t0) / 1e9, color=C_MAGENTA, linewidth=1, linestyle="--")
    axes[1].set_ylabel("deg clockwise from north")
    axes[1].set_ylim(-185, 185)
    axes[1].legend(**legend_kw)

    if log.baro_t.size:
        tb = (log.baro_t - t0) / 1e9
        if ctx.altitude is not None:
            raw_h = AltitudeTrack.height_above_reference(log.baro_hpa, ctx.altitude.p0_hpa)
            axes[2].plot(tb, raw_h, color=C_GRID, linewidth=0.8, label="raw height from pressure")
            alt_label = "altitude track (p0 = %.2f hPa, tau %.1f s)" % (ctx.altitude.p0_hpa, config.baroSmoothingS)
            axes[2].plot((ctx.altitude.times - t0) / 1e9, ctx.altitude.heights, color=C_BLUE, linewidth=1.5,
                         label=alt_label)
        if len(xyz):
            axes[2].plot((t_pts - t0) / 1e9, xyz[:, 2], ".", color=C_ORANGE, markersize=4, label="path z")
        axes[2].legend(**legend_kw)
    else:
        axes[2].text(0.5, 0.5, "no barometer samples", transform=axes[2].transAxes, ha="center", color=C_TEXT)
    axes[2].set_ylabel("height (m)")

    if ctx.steps.size > 1:
        st = ctx.steps.t_ns
        axes[3].plot((st[1:] - t0) / 1e9, np.diff(st) / 1e9, ".-", color=C_BLUE, markersize=4, linewidth=1,
                     label="interval between used steps")
    if log.steps_t.size > 1:
        axes[3].plot((log.steps_t[1:] - t0) / 1e9, np.diff(log.steps_t) / 1e9, ".", color=C_VIOLET, markersize=3,
                     label="interval between hardware steps")
    axes[3].axhline(config.stepMinIntervalS, color=C_RED, linewidth=1, linestyle=":", label="stepMinIntervalS")
    axes[3].set_ylabel("s")
    axes[3].set_xlabel("time since log start (s)")
    axes[3].set_ylim(bottom=0)
    axes[3].legend(**legend_kw)
    for a in log.annotations:
        for ax in axes:
            ax.axvline((a["tNs"] - t0) / 1e9, color=C_VIOLET, linewidth=0.8, alpha=0.6)
    for ax in axes:
        _style_axes(ax)
    fig.tight_layout()
    p = os.path.join(out_dir, "signals.png")
    fig.savefig(p, dpi=120, bbox_inches="tight")
    plt.close(fig)
    written.append(p)
    return written


# ----------------------------------------------------------------------------------------------
# main
# ----------------------------------------------------------------------------------------------

def build_config(log, args):
    if args.defaults or log.meta is None:
        config = PipelineConfig()
    else:
        config = PipelineConfig.from_meta(log.meta)
    for item in args.set or []:
        config.apply_override(item)
    return config


def main(argv=None):
    parser = argparse.ArgumentParser(description="Replay an IMU Mapper .imul log through the PDR pipeline.")
    parser.add_argument("log", help="raw log (.imul)")
    parser.add_argument("--out", default=None, help="output directory (default: <log name>_replay next to the log)")
    parser.add_argument("--set", action="append", metavar="KEY=VALUE",
                        help="override a PipelineConfig field, e.g. --set strideLengthM=0.68 (repeatable)")
    parser.add_argument("--defaults", action="store_true", help="ignore the config stored in the log meta")
    parser.add_argument("--compare", metavar="RESULT_JSON", help="PathResult JSON exported from the app to overlay")
    parser.add_argument("--summary-only", action="store_true", help="parse and print the summary, do not process")
    parser.add_argument("--no-plots", action="store_true", help="skip the PNG plots")
    parser.add_argument("--quiet", action="store_true", help="print only the stats line")
    args = parser.parse_args(argv)

    try:
        log = parse_imul(args.log)
    except (OSError, ValueError) as e:
        print("error: cannot read %s: %s" % (args.log, e), file=sys.stderr)
        return 2
    if not args.quiet:
        print_summary(args.log, log)
    if args.summary_only:
        return 0
    try:
        config = build_config(log, args)
    except ValueError as e:
        print("error: %s" % e, file=sys.stderr)
        return 2
    if not args.quiet:
        print_config(config)

    processor = PdrProcessor()
    result = processor.process(log, config)

    out_dir = args.out or os.path.splitext(os.path.abspath(args.log))[0] + "_replay"
    os.makedirs(out_dir, exist_ok=True)
    json_path, csv_path = write_outputs(result, out_dir, "path")
    if not args.quiet:
        print_diagnostics(result)
    print_stats(result)
    compare_xyz = None
    if args.compare:
        with open(args.compare) as f:
            other = json.load(f)
        compare_xyz = compare_results(result, other)
    if not args.no_plots:
        title = os.path.basename(args.log)
        written = make_plots(log, config, result, processor, out_dir, compare_xyz, title)
    else:
        written = []
    print("wrote: %s" % ", ".join([json_path, csv_path] + written))
    return 0


if __name__ == "__main__":
    sys.exit(main())
