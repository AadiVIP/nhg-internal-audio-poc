#!/usr/bin/env python3
"""Independent WAV analysis for NHG capture evidence. No app code is imported."""
import array
import json
import math
import sys
import wave

FULL_SCALE = 32768.0


def db(x):
    return -120.0 if x <= 0 else 20.0 * math.log10(x)


def dbfs(v):
    return db(abs(v) / FULL_SCALE) if v else -120.0


def tone_amplitude(samples, sr, freq):
    """Least-squares sin/cos projection over the window, Hann-windowed."""
    n = len(samples)
    if n == 0:
        return 0.0
    wsum = 0.0
    sc = 0.0
    cc = 0.0
    ss = 0.0
    for i, v in enumerate(samples):
        w = 0.5 - 0.5 * math.cos(2.0 * math.pi * i / (n - 1)) if n > 1 else 1.0
        wsum += w
        sc += w * v * math.sin(2.0 * math.pi * freq * i / sr)
        cc += w * v * math.cos(2.0 * math.pi * freq * i / sr)
        ss += w
    # amplitude of the fitted sinusoid (2/N for the sum of sin*sin and cos*cos)
    return 2.0 * math.hypot(sc, cc) / ss if ss else 0.0


def analyze(path, start_s=1.0, window_s=2.0):
    with wave.open(path, "rb") as w:
        nch = w.getnchannels()
        sw = w.getsampwidth()
        sr = w.getframerate()
        nframes = w.getnframes()
        raw = w.readframes(nframes)
    if sw != 2:
        raise SystemExit(f"expected 16-bit PCM, got sample width {sw}")
    a = array.array("h")
    a.frombytes(raw)
    if sys.byteorder == "big":
        a.byteswap()
    if nch > 1:
        chans = [a[c::nch] for c in range(nch)]
    else:
        chans = [a]
    total = len(a)
    peak = max((abs(x) for x in a), default=0)
    ssum = sum(float(x) * x for x in a)
    rms = math.sqrt(ssum / total) if total else 0.0
    nonzero = sum(1 for x in a if x != 0)
    clipped = sum(1 for x in a if abs(x) >= 32760)
    start = int(start_s * sr)
    end = min(len(chans[0]), start + int(window_s * sr))
    tones = {}
    labels = ["L", "R", "C", "S"] if nch > 1 else ["M"]
    for ci, ch in enumerate(chans[:4]):
        seg = ch[start:end]
        seg_rms = math.sqrt(sum(float(x) * x for x in seg) / len(seg)) if seg else 0.0
        label = labels[ci] if ci < len(labels) else f"ch{ci}"
        tones[label] = {
            "rmsDbfs": round(dbfs(seg_rms), 2),
            "peakDbfs": round(dbfs(max((abs(x) for x in seg), default=0)), 2),
            "amp440Dbfs": round(dbfs(tone_amplitude(seg, sr, 440.0)), 2),
            "amp1000Dbfs": round(dbfs(tone_amplitude(seg, sr, 1000.0)), 2),
        }
    result = {
        "path": path,
        "channels": nch,
        "sampleWidth": sw,
        "sampleRate": sr,
        "frames": nframes,
        "durationS": round(nframes / sr, 6) if sr else 0,
        "pcmBytes": nframes * nch * sw,
        "peakSample": peak,
        "peakDbfs": round(dbfs(peak), 2),
        "rmsDbfs": round(dbfs(rms), 2),
        "nonzeroSamples": nonzero,
        "zeroSamples": total - nonzero,
        "zeroFraction": round((total - nonzero) / total, 6) if total else 1.0,
        "clippedSamples": clipped,
        "analysisWindow": {"startS": start_s, "lengthS": window_s},
        "tones": tones,
    }
    return result


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("usage: analyze_wav.py FILE [startS windowS]")
    start = float(sys.argv[2]) if len(sys.argv) > 2 else 1.0
    win = float(sys.argv[3]) if len(sys.argv) > 3 else 2.0
    print(json.dumps(analyze(sys.argv[1], start, win), indent=2))
