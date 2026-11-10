"""Baked textures of the VR workshop (design rules.vrMode / entities3d.vrDome, QA #1 q1-1).

The room is a cylinder of radius R and height H around the user: a concrete floor disc, a wall band (block wall,
pallet racking, doors, a workbench, steel columns, cladding with a clerestory window band) and a ceiling disc with
three LED strip lights. Lighting is baked from those strips (Lambert, line sources sampled along their length) plus a
bounce term and contact occlusion, because the dome renders unlit (Mixed mode takes IBL from passthrough).

Writes app/src/main/assets/env/workshop_{floor,wall,ceiling}.jpg and a preview render from the eye point.
Run: python -I tools/workshop/gen_workshop.py <projectDir> [previewDir]
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageFilter

R = 6.5  # room radius, m (TdScene.DOME_R)
H = 4.8  # ceiling height, m (TdScene.DOME_H)
L = 2 * math.pi * R
STRIPS_X = (-2.6, 0.0, 2.6)
EYE = 1.5
rng = np.random.default_rng(20261110)


def noise(h, w, cell, amp=1.0):
    """Smooth value noise: a random grid of h/cell x w/cell upsampled bicubically, in [-amp, amp]."""
    gh, gw = max(2, int(h / cell) + 2), max(2, int(w / cell) + 2)
    g = rng.random((gh, gw)).astype(np.float32)
    im = Image.fromarray(g, mode="F").resize((w, h), Image.BICUBIC)
    a = np.asarray(im)
    return (a - 0.5) * 2 * amp


def fbm(h, w, cells, gains):
    return sum(noise(h, w, c, g) for c, g in zip(cells, gains))


def strip_points(n=48):
    pts = []
    for x in STRIPS_X:
        zmax = math.sqrt(R * R - x * x) - 0.6
        for z in np.linspace(-zmax, zmax, n):
            pts.append((x, H - 0.04, z))
    return np.array(pts, np.float32)


LIGHTS = strip_points()


def irradiance(P, N):
    """Direct light at points P (k,3) with normals N (k,3) from the downward-facing strips."""
    E = np.zeros(len(P), np.float32)
    for lp in LIGHTS:
        d = lp[None, :] - P
        r2 = np.maximum((d * d).sum(1), 0.04)
        dn = d / np.sqrt(r2)[:, None]
        cos_s = np.clip((dn * N).sum(1), 0, None)
        cos_l = np.clip(dn[:, 1], 0, None)  # light faces down: -(0,-1,0)·(-dn) = dn.y
        E += cos_s * cos_l / r2
    return E / len(LIGHTS)


def to_u8(a):
    return (np.clip(a, 0, 1) ** (1 / 1.0) * 255 + 0.5).astype(np.uint8)


# ------------------------------------------------------------------------------------------------ floor
def floor_tex(n=2048):
    xs = (np.arange(n) / n - 0.5) * 2 * R + R / n
    X, Z = np.meshgrid(xs, xs)  # row = +z (towards the user's back), col = +x
    base = np.array([0.50, 0.495, 0.47], np.float32)
    mott = fbm(n, n, [n / 3, n / 10, n / 40, n / 160], [0.05, 0.04, 0.025, 0.02])
    grain = rng.normal(0, 0.018, (n, n)).astype(np.float32)
    stains = np.clip(noise(n, n, n / 8, 1.0) - 0.45, 0, None) * 0.25
    alb = base[None, None, :] * (1 + mott + grain - stains)[..., None]
    # tyre / scuff marks along the aisle behind the user
    scuff = np.clip(noise(n, n, n / 60, 1.0), 0, None) * np.exp(-((Z - 3.8) / 0.7) ** 2) * 0.12
    alb *= (1 - scuff)[..., None]
    # saw-cut joints every 3 m
    px = 2 * R / n
    for c in (-4.5, -1.5, 1.5, 4.5):
        for A in (X, Z):
            d = np.abs(A - c)
            alb *= (1 - 0.35 * np.exp(-(d / (px * 1.2)) ** 2))[..., None]
    # yellow markings: work-zone rectangle and a walkway behind
    yellow = np.array([0.86, 0.68, 0.12], np.float32)
    wear = np.clip(0.75 + noise(n, n, n / 90, 0.35) + rng.normal(0, 0.08, (n, n)), 0, 1)

    def paint(mask):
        m = (mask * wear)[..., None]
        return alb * (1 - m) + yellow[None, None, :] * (0.9 + 0.1 * mott[..., None]) * m

    w = 0.08
    x0, x1, z0, z1 = -2.4, 2.4, -3.0, 1.7
    inside_x = (X > x0 - w) & (X < x1 + w)
    inside_z = (Z > z0 - w) & (Z < z1 + w)
    rect = ((np.abs(X - x0) < w / 2) | (np.abs(X - x1) < w / 2)) & inside_z | ((np.abs(Z - z0) < w / 2) | (np.abs(Z - z1) < w / 2)) & inside_x
    alb = paint(rect.astype(np.float32))
    for zc in (3.0, 4.6):
        alb = paint((np.abs(Z - zc) < 0.05).astype(np.float32))
    # baked light on a coarse grid
    g = 160
    gx = (np.arange(g) / g - 0.5) * 2 * R + R / g
    GX, GZ = np.meshgrid(gx, gx)
    P = np.stack([GX.ravel(), np.zeros(g * g), GZ.ravel()], 1).astype(np.float32)
    E = irradiance(P, np.tile(np.array([[0, 1, 0]], np.float32), (g * g, 1))).reshape(g, g)
    E = np.asarray(Image.fromarray(E.astype(np.float32), "F").resize((n, n), Image.BICUBIC))
    r = np.sqrt(X * X + Z * Z)
    ao = 1 - 0.55 * np.exp(-np.clip(R - r, 0, None) / 0.45)
    light = (0.3 + E / E.max() * 1.0) * ao
    # glossy reflections of the strips as seen from the eye point (polished concrete)
    t = EYE / (EYE + H)
    gloss = np.zeros((n, n), np.float32)
    for sx in STRIPS_X:
        zmax = (math.sqrt(R * R - sx * sx) - 0.6) * t
        along = np.clip(1 - np.clip(np.abs(Z) - zmax, 0, None) / 0.4, 0, 1)
        gloss += np.exp(-((X - sx * t) / 0.16) ** 2) * along
    light = light + gloss * 0.22 * (0.7 + fbm(n, n, [n / 30], [0.3]))
    out = alb * light[..., None]
    return to_u8(out)


# ------------------------------------------------------------------------------------------------ wall
class Wall:
    def __init__(self, w=4096, h=512):
        self.w, self.h = w, h
        s = (np.arange(w) + 0.5) / w * L
        y = (1 - (np.arange(h) + 0.5) / h) * H
        self.S, self.Y = np.meshgrid(s, y)
        self.alb = np.zeros((h, w, 3), np.float32)

    def mask(self, s0, s1, y0, y1):
        S = self.S
        m = ((S >= s0) & (S < s1)) | ((S + L >= s0) & (S + L < s1))
        return m & (self.Y >= y0) & (self.Y < y1)

    def fill(self, s0, s1, y0, y1, col, jitter=0.0):
        m = self.mask(s0, s1, y0, y1)
        c = np.array(col, np.float32)
        if jitter:
            c = c * (1 + rng.uniform(-jitter, jitter))
        self.alb[m] = c
        return m

    def shade(self, m, f):
        self.alb[m] *= f


def wall_tex():
    wl = Wall()
    S, Y, h, w = wl.S, wl.Y, wl.h, wl.w
    # cladding above 2.6 m: pale blue-grey corrugated steel
    clad = np.array([0.52, 0.57, 0.62], np.float32)
    rib = 0.88 + 0.12 * np.cos(S / 0.2 * 2 * np.pi)
    wl.alb[:] = clad * rib[..., None]
    streak = noise(h, w, 6, 0.04) + noise(h, w, 40, 0.03)
    wl.alb *= (1 + streak)[..., None]
    # block wall 0.15-2.6 m: painted CMU, 0.4 x 0.2 m, staggered courses
    blk = wl.mask(0, L + 1, 0.15, 2.6)
    course = np.floor((Y - 0.15) / 0.2)
    off = (course % 2) * 0.2
    mort = (np.abs(((S + off) % 0.4) - 0.0) < 0.012) | (np.abs(((Y - 0.15) % 0.2)) < 0.012)
    paint = np.array([0.66, 0.66, 0.62], np.float32) * (1 + fbm(h, w, [30, 8], [0.035, 0.02]))[..., None]
    wl.alb[blk] = paint[blk]
    wl.alb[blk & mort] *= 0.82
    # a dark grey dado band and the skirting
    wl.fill(0, L + 1, 0.0, 0.15, (0.18, 0.18, 0.19))
    band = wl.mask(0, L + 1, 1.05, 1.2)
    wl.alb[band] = np.array([0.28, 0.36, 0.42], np.float32)
    # clerestory windows 3.4-4.1 m (frosted, mullions every 1.2 m)
    win = wl.mask(0, L + 1, 3.4, 4.1)
    glass = np.array([0.80, 0.86, 0.92], np.float32) * (0.95 + 0.05 * noise(h, w, 20))[..., None]
    wl.alb[win] = glass[win]
    mull = win & ((np.abs((S % 1.2)) < 0.035) | (np.abs(Y - 3.75) < 0.02))
    wl.alb[mull] = np.array([0.22, 0.24, 0.26], np.float32)
    wl.fill(0, L + 1, 4.1, 4.16, (0.22, 0.24, 0.26))
    wl.fill(0, L + 1, 3.34, 3.4, (0.22, 0.24, 0.26))

    # pallet racking
    def rack(s0, bays, bay=2.7):
        top = 4.0
        levels = [0.12, 1.45, 2.75]
        # dark back (shadowed wall between uprights)
        back = wl.mask(s0, s0 + bays * bay, 0.0, top)
        wl.alb[back] *= 0.38
        for b in range(bays):
            bs = s0 + b * bay
            for li, ly in enumerate(levels):
                ceil_y = levels[li + 1] - 0.12 if li + 1 < len(levels) else top - 0.3
                for p in range(2):
                    ps = bs + 0.12 + p * 1.25
                    kind = rng.choice(["boxes", "boxes", "wrap", "drums", "empty"], p=[0.38, 0.17, 0.22, 0.08, 0.15])
                    if li == 0 and kind == "empty":
                        kind = "boxes"
                    # pallet (wood) with three dark gaps
                    wl.fill(ps, ps + 1.15, ly + 0.1, ly + 0.24, (0.55, 0.42, 0.27), 0.08)
                    for k in range(2):
                        gs = ps + 0.18 + k * 0.48
                        wl.fill(gs, gs + 0.3, ly + 0.11, ly + 0.2, (0.08, 0.07, 0.06))
                    g0 = ly + 0.24
                    gh = min(rng.uniform(0.7, 1.1), ceil_y - g0 - 0.06)
                    if kind == "boxes":
                        cols = rng.integers(2, 4)
                        rows = rng.integers(2, 4)
                        cw, rh = 1.12 / cols, gh / rows
                        for ci in range(cols):
                            for ri in range(rows):
                                if ri == rows - 1 and rng.random() < 0.25:
                                    continue
                                c = np.array([0.62, 0.46, 0.29]) * rng.uniform(0.85, 1.12)
                                m = wl.fill(ps + 0.015 + ci * cw, ps + (ci + 1) * cw, g0 + ri * rh, g0 + (ri + 1) * rh - 0.012, c)
                                # tape line and a white label
                                yc = g0 + (ri + 0.5) * rh
                                wl.fill(ps + 0.015 + ci * cw, ps + (ci + 1) * cw, yc - 0.02, yc + 0.02, c * 0.85)
                                if rng.random() < 0.5:
                                    ls = ps + ci * cw + cw * 0.25
                                    wl.fill(ls, ls + 0.14, yc - 0.13, yc - 0.05, (0.86, 0.86, 0.82))
                    elif kind == "wrap":
                        # cartons under stretch film: boxes first, then a milky film with vertical sheen
                        for ci in range(2):
                            for ri in range(3):
                                c = np.array([0.6, 0.45, 0.3]) * rng.uniform(0.85, 1.1)
                                wl.fill(ps + 0.03 + ci * 0.54, ps + 0.03 + (ci + 1) * 0.54 - 0.012, g0 + ri * gh / 3, g0 + (ri + 1) * gh / 3 - 0.012, c)
                        m = wl.mask(ps + 0.02, ps + 1.13, g0, g0 + gh)
                        sheen = 0.5 + 0.5 * np.cos((S - ps) / 1.11 * 2 * np.pi * 2.5) ** 8
                        film = np.array([0.82, 0.85, 0.88], np.float32)[None, None, :] * (0.85 + 0.25 * sheen + noise(h, w, 10, 0.05))[..., None]
                        wl.alb[m] = (0.45 * wl.alb + 0.55 * film)[m]
                    elif kind == "drums":
                        for k in range(2):
                            ds = ps + 0.05 + k * 0.56
                            col = (0.16, 0.32, 0.6) if rng.random() < 0.6 else (0.12, 0.42, 0.24)
                            m = wl.mask(ds, ds + 0.52, g0, g0 + min(0.88, gh))
                            # cylinder shading across the drum
                            u = np.clip((S - ds) / 0.52, 0, 1)
                            f = 0.55 + 0.6 * np.sin(u * np.pi)
                            wl.alb[m] = (np.array(col, np.float32)[None, None, :] * f[..., None])[m]
                            for ry in (0.3, 0.6):
                                wl.fill(ds, ds + 0.52, g0 + ry, g0 + ry + 0.025, np.array(col) * 0.6)
                # shadow under the next beam on the goods
                under = wl.mask(bs, bs + bay, ceil_y - 0.35, ceil_y + 0.12)
                f = np.clip((Y - (ceil_y - 0.35)) / 0.47, 0, 1)
                wl.alb[under] *= (1 - 0.45 * f)[under][..., None]
            # orange step beams
            for ly in levels[1:] + [top - 0.3]:
                m = wl.fill(bs, bs + bay, ly - 0.12, ly, (0.88, 0.42, 0.09))
                wl.fill(bs, bs + bay, ly - 0.12, ly - 0.1, (0.98, 0.6, 0.25))
            wl.fill(bs, bs + bay, levels[0], levels[0] + 0.1, (0.88, 0.42, 0.09))
        # blue uprights with perforations
        for b in range(bays + 1):
            us = s0 + b * bay - 0.045
            wl.fill(us, us + 0.09, 0.0, top + 0.05, (0.11, 0.27, 0.58))
            wl.fill(us + 0.06, us + 0.09, 0.0, top + 0.05, (0.07, 0.18, 0.4))
            hole = wl.mask(us + 0.025, us + 0.05, 0.05, top) & ((Y % 0.075) < 0.03)
            wl.alb[hole] *= 0.35
            wl.fill(us - 0.03, us + 0.12, 0.0, 0.03, (0.3, 0.3, 0.3))

    def column(s):
        wl.fill(s, s + 0.3, 0.0, H, (0.3, 0.32, 0.35))
        wl.fill(s + 0.04, s + 0.26, 0.0, H, (0.38, 0.4, 0.43))
        # yellow-black guard band at the bottom
        m = wl.mask(s - 0.02, s + 0.32, 0.0, 1.0)
        stripe = (((S + Y) / 0.18) % 2) < 1
        wl.alb[m & stripe] = np.array([0.86, 0.68, 0.12], np.float32)
        wl.alb[m & ~stripe] = np.array([0.08, 0.08, 0.08], np.float32)

    def workbench(s0, s1):
        # pegboard with tool silhouettes, a bench top, drawers
        m = wl.fill(s0, s1, 1.05, 2.1, (0.55, 0.45, 0.32))
        holes = m & ((S % 0.05) < 0.012) & ((Y % 0.05) < 0.012)
        wl.alb[holes] *= 0.5
        for k in range(int((s1 - s0) / 0.22)):
            ts = s0 + 0.12 + k * 0.22
            ln = rng.uniform(0.25, 0.5)
            wl.fill(ts, ts + 0.035, 1.85 - ln, 1.85, (0.12, 0.12, 0.13))
            wl.fill(ts - 0.03, ts + 0.065, 1.8 - ln, 1.85 - ln, (0.75, 0.15, 0.1) if k % 3 == 0 else (0.15, 0.15, 0.16))
        wl.fill(s0 - 0.05, s1 + 0.05, 0.86, 0.92, (0.45, 0.33, 0.2))
        wl.fill(s0, s1, 0.08, 0.86, (0.15, 0.32, 0.5))
        for k in range(3):
            y0 = 0.12 + k * 0.24
            wl.fill(s0 + 0.05, s1 - 0.05, y0 + 0.2, y0 + 0.215, (0.08, 0.17, 0.28))
            wl.fill((s0 + s1) / 2 - 0.15, (s0 + s1) / 2 + 0.15, y0 + 0.16, y0 + 0.18, (0.7, 0.72, 0.74))
        wl.fill(s0, s1, 0.0, 0.08, (0.05, 0.05, 0.05))
        # a hanging task lamp glow
        m = wl.mask(s0, s1, 0.92, 2.1)
        c = (s0 + s1) / 2
        glow = np.exp(-(((S - c) / 0.9) ** 2 + ((Y - 2.0) / 0.5) ** 2))
        wl.alb[m] *= (1 + 0.35 * glow[m])[..., None]

    def cabinets(s0, s1):
        n = 3
        cw = (s1 - s0) / n
        for k in range(n):
            cs = s0 + k * cw
            wl.fill(cs + 0.02, cs + cw - 0.02, 0.1, 2.0, (0.6, 0.62, 0.6))
            wl.fill(cs + cw / 2 - 0.006, cs + cw / 2 + 0.006, 0.15, 1.95, (0.35, 0.36, 0.35))
            wl.fill(cs + cw / 2 - 0.1, cs + cw / 2 - 0.04, 1.0, 1.15, (0.2, 0.2, 0.2))
            # warning triangle
            ty0, tc = 1.6, cs + cw / 2
            m = wl.mask(tc - 0.12, tc + 0.12, ty0, ty0 + 0.2)
            tri = m & (np.abs(S - tc) < (ty0 + 0.2 - Y) * 0.6)
            wl.alb[tri] = np.array([0.95, 0.75, 0.1], np.float32)
        # conduits up the wall
        for k in range(3):
            cs = s0 + 0.2 + k * 0.5
            wl.fill(cs, cs + 0.05, 2.0, H, (0.48, 0.5, 0.52))

    def exit_door(s0, s1):
        wl.fill(s0 - 0.08, s1 + 0.08, 0.0, 2.25, (0.3, 0.31, 0.33))
        wl.fill(s0, s1, 0.0, 2.17, (0.42, 0.47, 0.52))
        wl.fill(s0 + 0.1, s1 - 0.1, 1.0, 1.06, (0.72, 0.73, 0.74))
        # green exit sign box (no text)
        c = (s0 + s1) / 2
        wl.fill(c - 0.25, c + 0.25, 2.4, 2.62, (0.08, 0.62, 0.3))
        wl.fill(c - 0.17, c - 0.05, 2.45, 2.57, (0.85, 0.95, 0.88))
        # fire extinguisher
        wl.fill(s1 + 0.35, s1 + 0.5, 0.3, 0.95, (0.75, 0.1, 0.08))
        wl.fill(s1 + 0.38, s1 + 0.47, 0.95, 1.05, (0.12, 0.12, 0.12))
        wl.fill(s1 + 0.28, s1 + 0.57, 1.2, 1.5, (0.8, 0.12, 0.1))

    def rollup(s0, s1, top=3.7):
        m = wl.fill(s0, s1, 0.0, top, (0.58, 0.6, 0.6))
        slat = m & (((Y / 0.1) % 1) < 0.18)
        wl.alb[slat] *= 0.7
        wl.alb[m] *= (1 + noise(h, w, 25, 0.05))[m][..., None]
        wl.fill(s0, s1, top, top + 0.45, (0.3, 0.32, 0.34))
        for e in (s0 - 0.25, s1):
            mm = wl.mask(e, e + 0.25, 0.0, top + 0.45)
            stripe = (((S - Y) / 0.2) % 2) < 1
            wl.alb[mm & stripe] = np.array([0.86, 0.68, 0.12], np.float32)
            wl.alb[mm & ~stripe] = np.array([0.08, 0.08, 0.08], np.float32)

    rack(2.1, 3)
    workbench(11.0, 13.3)
    rack(13.8, 3)
    cabinets(22.8, 25.0)
    rack(25.4, 3)
    exit_door(34.4, 35.4)
    rollup(37.0, 40.4)
    for s in (1.6, 10.55, 22.35, 33.95):
        column(s)

    # grime near the floor and soot under the ceiling
    wl.alb *= (1 - 0.25 * np.exp(-Y / 0.25))[..., None]
    wl.alb *= (1 - 0.3 * np.exp(-(H - Y) / 0.3))[..., None]

    # baked light (coarse grid in s, y)
    gs, gy = 512, 64
    s = (np.arange(gs) + 0.5) / gs * L
    y = (1 - (np.arange(gy) + 0.5) / gy) * H
    GS, GY = np.meshgrid(s, y)
    a = GS / R
    P = np.stack([R * np.sin(a), GY, -R * np.cos(a)], -1).reshape(-1, 3).astype(np.float32) * np.array([0.995, 1, 0.995], np.float32)
    N = np.stack([-np.sin(a), np.zeros_like(a), np.cos(a)], -1).reshape(-1, 3).astype(np.float32)
    E = irradiance(P, N).reshape(gy, gs)
    E = np.asarray(Image.fromarray(E.astype(np.float32), "F").resize((w, h), Image.BICUBIC))
    light = 0.38 + E / E.max() * 0.95
    # the clerestory glass glows (daylight), the rest is lit
    win = wl.mask(0, L + 1, 3.4, 4.1) & (wl.alb.mean(-1) > 0.5)
    out = wl.alb * light[..., None]
    out[win] = wl.alb[win] * 1.08
    return to_u8(out)


# ------------------------------------------------------------------------------------------------ ceiling
def ceiling_tex(n=1536):
    xs = (np.arange(n) / n - 0.5) * 2 * R + R / n
    X, Zimg = np.meshgrid(xs, xs)
    Z = -Zimg  # image top = world +z on the ceiling disc
    alb = np.full((n, n, 3), 0.3, np.float32) * np.array([0.95, 0.98, 1.02], np.float32)
    deck = 0.85 + 0.15 * np.cos(X / 0.3 * 2 * np.pi)
    alb *= deck[..., None]
    alb *= (1 + fbm(n, n, [n / 6, n / 30], [0.05, 0.03]))[..., None]
    # trusses across the room every 2.4 m (along x), with a lighter bottom chord
    for zc in np.arange(-6.0, 6.01, 2.4):
        m = np.abs(Z - zc) < 0.14
        alb[m] = np.array([0.16, 0.17, 0.19], np.float32)
        alb[np.abs(Z - zc) < 0.04] = np.array([0.26, 0.27, 0.29], np.float32)
    # ducts
    for xc in (-4.4, 4.4):
        m = np.abs(X - xc) < 0.25
        alb[m] = np.array([0.55, 0.57, 0.6], np.float32)[None, :] * (0.75 + 0.35 * np.cos((X[m] - xc) / 0.25 * np.pi / 2))[:, None]
    out = alb * 0.75
    # strip fixtures and their glow
    glow = np.zeros((n, n), np.float32)
    for sx in STRIPS_X:
        zmax = math.sqrt(R * R - sx * sx) - 0.6
        inz = np.abs(Z) < zmax
        housing = (np.abs(X - sx) < 0.12) & inz
        out[housing] = np.array([0.55, 0.56, 0.58], np.float32)
        tube = (np.abs(X - sx) < 0.06) & inz
        out[tube] = np.array([1.0, 1.0, 0.98], np.float32)
        along = np.clip(1 - np.clip(np.abs(Z) - zmax, 0, None) / 0.3, 0, 1)
        glow += np.exp(-((X - sx) / 0.35) ** 2) * along
    out = out + glow[..., None] * np.array([0.35, 0.35, 0.33], np.float32)
    r = np.sqrt(X * X + Z * Z)
    out *= (1 - 0.4 * np.exp(-np.clip(R - r, 0, None) / 0.35))[..., None]
    return to_u8(out)


# ------------------------------------------------------------------------------------------------ preview
def preview(floor, wall, ceil, yaw_deg, out_path, w=1280, h=800, fov=95):
    f = (w / 2) / math.tan(math.radians(fov / 2))
    jj, ii = np.meshgrid(np.arange(w) - w / 2 + 0.5, np.arange(h) - h / 2 + 0.5)
    d = np.stack([jj / f, -ii / f - 0.25, -np.ones_like(jj)], -1)
    d /= np.linalg.norm(d, axis=-1, keepdims=True)
    ya = math.radians(yaw_deg)
    dy = d[..., 1]
    # yaw: positive turns right (forward -z towards +x)
    dx = d[..., 0] * math.cos(ya) + (-d[..., 2]) * math.sin(ya)
    dz = d[..., 2] * math.cos(ya) + d[..., 0] * math.sin(ya)
    img = np.zeros((h, w, 3), np.uint8)
    t_wall = R / np.maximum(np.sqrt(dx * dx + dz * dz), 1e-6)
    yw = EYE + dy * t_wall
    t_floor = np.where(dy < 0, -EYE / np.minimum(dy, -1e-6), np.inf)
    t_ceil = np.where(dy > 0, (H - EYE) / np.maximum(dy, 1e-6), np.inf)
    hit_wall = (yw >= 0) & (yw <= H)
    # wall
    a = np.arctan2(dx, -dz) % (2 * np.pi)
    u = a / (2 * np.pi)
    v = 1 - yw / H
    m = hit_wall
    img[m] = wall[np.clip((v[m] * wall.shape[0]).astype(int), 0, wall.shape[0] - 1), np.clip((u[m] * wall.shape[1]).astype(int), 0, wall.shape[1] - 1)]
    m = ~hit_wall & (dy < 0)
    x, z = dx * t_floor, dz * t_floor
    n = floor.shape[0]
    img[m] = floor[np.clip(((z[m] / (2 * R) + 0.5) * n).astype(int), 0, n - 1), np.clip(((x[m] / (2 * R) + 0.5) * n).astype(int), 0, n - 1)]
    m = ~hit_wall & (dy > 0)
    x, z = dx * t_ceil, dz * t_ceil
    n = ceil.shape[0]
    img[m] = ceil[np.clip(((-z[m] / (2 * R) + 0.5) * n).astype(int), 0, n - 1), np.clip(((x[m] / (2 * R) + 0.5) * n).astype(int), 0, n - 1)]
    Image.fromarray(img).save(out_path)


def main():
    proj = sys.argv[1]
    prev = sys.argv[2] if len(sys.argv) > 2 else None
    out = os.path.join(proj, "app", "src", "main", "assets", "env")
    os.makedirs(out, exist_ok=True)
    fl, wa, ce = floor_tex(), wall_tex(), ceiling_tex()
    Image.fromarray(fl).save(os.path.join(out, "workshop_floor.jpg"), quality=88)
    Image.fromarray(wa).save(os.path.join(out, "workshop_wall.jpg"), quality=88)
    Image.fromarray(ce).save(os.path.join(out, "workshop_ceiling.jpg"), quality=88)
    if prev:
        os.makedirs(prev, exist_ok=True)
        for yaw in (0, 90, 180, 270):
            preview(fl, wa, ce, yaw, os.path.join(prev, f"preview-{yaw}.png"))
    print("ok", out)


if __name__ == "__main__":
    main()
