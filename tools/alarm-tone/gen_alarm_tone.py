# Generates the self-generated alarm beep loop (CP-00A R-80): 1.0 s loop, two 200 ms beeps at 1000 Hz with 5 ms
# fades, 100 ms gap, 500 ms silence. 22050 Hz mono 16-bit PCM WAV.
import math, struct, sys, wave
rate = 22050
def beep(ms, f=1000.0, amp=0.8):
    n = int(rate * ms / 1000); fade = int(rate * 0.005); out = []
    for i in range(n):
        g = min(1.0, i / fade, (n - 1 - i) / fade)
        out.append(amp * g * math.sin(2 * math.pi * f * i / rate))
    return out
def silence(ms): return [0.0] * int(rate * ms / 1000)
samples = beep(200) + silence(100) + beep(200) + silence(500)
with wave.open(sys.argv[1], 'wb') as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
    w.writeframes(b''.join(struct.pack('<h', int(s * 32767)) for s in samples))
