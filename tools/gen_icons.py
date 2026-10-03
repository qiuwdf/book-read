"""生成 Android 启动图标 PNG（纯 Python，无第三方依赖）。"""
import os
import struct
import zlib


def write_png(path, pixels, w, h):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        row_start = y * w * 4
        raw += pixels[row_start:row_start + w * 4]

    def chunk(tag, data):
        c = struct.pack('>I', len(data)) + tag + data
        c += struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
        return c

    ihdr = struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr) + chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(png)


def in_poly(x, y, poly):
    inside = False
    n = len(poly)
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y):
            xint = (xj - xi) * (y - yi) / (yj - yi) + xi
            if x < xint:
                inside = not inside
        j = i
    return inside


def render(size, ss=4):
    S = size * ss
    buf = bytearray(S * S * 4)

    # 背景：圆角矩形 + 竖向渐变
    radius = 0.235 * S
    c1 = (255, 122, 69)
    c2 = (224, 55, 22)
    for y in range(S):
        t = y / max(1, S - 1)
        r = int(c1[0] + (c2[0] - c1[0]) * t)
        g = int(c1[1] + (c2[1] - c1[1]) * t)
        b = int(c1[2] + (c2[2] - c1[2]) * t)
        for x in range(S):
            # 圆角判断
            dx = min(x, S - 1 - x)
            dy = min(y, S - 1 - y)
            if dx < radius and dy < radius:
                ox = radius - dx
                oy = radius - dy
                if ox * ox + oy * oy > radius * radius:
                    continue
            i = (y * S + x) * 4
            buf[i] = r
            buf[i + 1] = g
            buf[i + 2] = b
            buf[i + 3] = 255

    # 前景：翻开的书本（两个四边形），白色带轻微透明
    left = [(0.500, 0.275), (0.160, 0.340), (0.160, 0.740), (0.500, 0.675)]
    right = [(0.500, 0.275), (0.840, 0.340), (0.840, 0.740), (0.500, 0.675)]
    polys = [
        [(x * S, y * S) for (x, y) in left],
        [(x * S, y * S) for (x, y) in right],
    ]
    for y in range(S):
        for x in range(S):
            hit = False
            for p in polys:
                if in_poly(x + 0.5, y + 0.5, p):
                    hit = True
                    break
            if hit:
                i = (y * S + x) * 4
                buf[i] = 255
                buf[i + 1] = 255
                buf[i + 2] = 255
                buf[i + 3] = 255

    if ss == 1:
        return buf

    # 下采样抗锯齿
    out = bytearray(size * size * 4)
    for y in range(size):
        for x in range(size):
            r = g = b = a = 0
            for dy in range(ss):
                for dx in range(ss):
                    i = ((y * ss + dy) * S + (x * ss + dx)) * 4
                    r += buf[i]
                    g += buf[i + 1]
                    b += buf[i + 2]
                    a += buf[i + 3]
            n = ss * ss
            o = (y * size + x) * 4
            out[o] = r // n
            out[o + 1] = g // n
            out[o + 2] = b // n
            out[o + 3] = a // n
    return out


if __name__ == '__main__':
    base = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        os.pardir, 'app', 'src', 'main', 'res')
    base = os.path.normpath(base)
    targets = {
        'mipmap-mdpi': 48,
        'mipmap-hdpi': 72,
        'mipmap-xhdpi': 96,
        'mipmap-xxhdpi': 144,
        'mipmap-xxxhdpi': 192,
    }
    for folder, size in targets.items():
        d = os.path.join(base, folder)
        os.makedirs(d, exist_ok=True)
        px = render(size)
        write_png(os.path.join(d, 'ic_launcher.png'), px, size, size)
        print('generated', folder, size)
