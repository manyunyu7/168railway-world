"""Generate original short interface sounds for this fork (no samples)."""
from pathlib import Path
from math import sin, exp, pi
import wave, struct

output = Path(__file__).resolve().parents[2] / 'app/public/sounds'
output.mkdir(exist_ok=True)
for name, pitch, duration, sweep in [
    ('snap', 820, .06, -290), ('pop', 395, .09, -245),
    ('knock', 165, .095, -60), ('construct', 260, .15, 215),
]:
    rate = 16000
    samples = []
    for i in range(round(rate * duration)):
        t = i / rate
        envelope = min(1, t / .004) * exp(-7 * t / duration)
        angle = 2 * pi * (pitch * t + .5 * sweep * t * t / duration)
        samples.append(round(12000 * envelope * (sin(angle) + .12 * sin(2 * angle))))
    with wave.open(str(output / (name + '.wav')), 'wb') as wav:
        wav.setparams((1, 2, rate, 0, 'NONE', 'not compressed'))
        wav.writeframes(struct.pack('<' + 'h' * len(samples), *samples))
