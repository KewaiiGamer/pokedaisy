// The hero handhelds' controls as pixel sprites, one sprite pixel to one handheld unit (--u): the AYN
// Thor's (black) and the Retroid Pocket 5's (its light grey, purple-buttoned "16-bit" shell). Each is
// painted per pixel from a few shapes - a cross, round buttons, a stick in its well - lit from the top
// left: a highlight rim, a shaded rim, an outline, and a drop shadow onto the shell (see-through, so it
// suits either shell). Outside the shapes is transparent, so the shell shows through.

const SHADOW = 'rgba(0, 0, 0, 0.34)';
const SHADOW_SOFT = 'rgba(0, 0, 0, 0.14)';

/** Rows of palette keys for a [w] x [h] sprite from [paint](x, y) (null = transparent). */
function paint(w, h, fn) {
  const rows = [];
  for (let y = 0; y < h; y++) {
    let row = '';
    for (let x = 0; x < w; x++) row += fn(x, y) ?? '.';
    rows.push(row);
  }
  return rows;
}

/** A 3x5 letter for a face button, as [x, y] pixels from its top left. */
const LETTERS = {
  A: ['.#.', '#.#', '###', '#.#', '#.#'],
  B: ['##.', '#.#', '##.', '#.#', '##.'],
  X: ['#.#', '#.#', '.#.', '#.#', '#.#'],
  Y: ['#.#', '#.#', '.#.', '.#.', '.#.'],
};
const letterAt = (ch, x, y) => LETTERS[ch]?.[y]?.[x] === '#';

/**
 * A d-pad: a cross [size] pixels across with arms [arm] wide, rounded ends, a bevel, an arrow pressed
 * into each arm and a dish in the middle; [c] = { K outline, H light rim, G face, D dark rim, A arrow, a arrow's lit edge }.
 */
export function dpad(c, size = 24, arm = 8) {
  const lo = (size - arm) / 2;
  const hi = lo + arm - 1;
  const inside = (x, y) => {
    if (x < 0 || y < 0 || x >= size || y >= size) return false;
    const v = x >= lo && x <= hi;
    const h = y >= lo && y <= hi;
    if (!v && !h) return false;
    // The arms' outer corners, cut a pixel: rounded ends.
    const end = (a, b) => (a === 0 || a === size - 1) && (b === lo || b === hi);
    return !(end(y, x) || end(x, y));
  };
  // An arrow pointing out along each arm, pressed in: three rows, 2 / 4 / 6 wide.
  const mid = (size - 1) / 2;
  const arrow = new Map();
  for (let r = 0; r < 3; r++) {
    for (let i = -(r + 1); i < r + 1; i++) {
      const along = 3 + r;
      const across = Math.round(mid + i + 0.5);
      for (const [x, y] of [[across, along], [across, size - 1 - along], [along, across], [size - 1 - along, across]]) arrow.set(`${x},${y}`, true);
    }
  }
  return {
    pal: { K: c.K, H: c.H, G: c.G, D: c.D, A: c.A, a: c.a, S: SHADOW, s: SHADOW_SOFT },
    rows: paint(size + 2, size + 2, (x, y) => {
      if (!inside(x, y)) {
        if (inside(x - 1, y - 1) || inside(x, y - 1) && inside(x - 1, y)) return 'S';
        if (inside(x - 2, y - 2)) return 's';
        return null;
      }
      if (!inside(x - 1, y) || !inside(x + 1, y) || !inside(x, y - 1) || !inside(x, y + 1)) return 'K';
      if (!inside(x - 2, y) || !inside(x, y - 2)) return 'H';
      if (!inside(x + 2, y) || !inside(x, y + 2)) return 'D';
      if (arrow.has(`${x},${y}`)) return 'A';
      if (arrow.has(`${x - 1},${y}`) && !arrow.has(`${x},${y}`) && x > mid || arrow.has(`${x},${y - 1}`) && y > mid) return 'a';
      // The dish where the arms meet: its rim in shade on the upper left, lit on the lower right (it's hollow).
      const dx = x + 0.5 - size / 2;
      const dy = y + 0.5 - size / 2;
      const d = Math.hypot(dx, dy);
      if (d < 3.2 && d > 1.6) return dx + dy < -0.5 ? 'D' : dx + dy > 0.5 ? 'H' : 'G';
      return 'G';
    }),
  };
}

/**
 * Four round face buttons in a diamond, X on top, Y left, A right, B at the bottom (the Nintendo order both
 * handhelds use): radius [r], [gap] pixels from the middle to each centre. [colours] maps a letter to
 * { K outline, H light rim, G face, D dark rim, W glint, L letter }.
 */
export function abxy(colours, r = 5.5, gap = 9.5) {
  const n = Math.ceil(2 * (gap + r));
  const c = n / 2;
  const buttons = [['X', c, c - gap], ['Y', c - gap, c], ['A', c + gap, c], ['B', c, c + gap]];
  const pal = { S: SHADOW, s: SHADOW_SOFT };
  const keys = {};
  for (const [ch] of buttons) {
    const k = { X: 'xpqrtn', Y: 'yuvwzj', A: 'abcdeg', B: 'fhiklm' }[ch];
    const col = colours[ch];
    keys[ch] = { K: k[0], H: k[1], G: k[2], D: k[3], W: k[4], L: k[5] };
    for (const part of ['K', 'H', 'G', 'D', 'W', 'L']) pal[keys[ch][part]] = col[part];
  }
  // A little inside the radius, so no lone pixel pokes out at the top, bottom and sides.
  const within = (x, y, cx, cy, rr = r) => Math.hypot(x + 0.5 - cx, y + 0.5 - cy) <= rr - 0.25;
  const which = (x, y) => buttons.find(([, cx, cy]) => within(x, y, cx, cy));
  return {
    pal,
    rows: paint(n + 2, n + 2, (x, y) => {
      const b = which(x, y);
      if (!b) {
        if (which(x - 1, y - 1)) return 'S';
        if (which(x - 2, y - 2)) return 's';
        return null;
      }
      const [ch, cx, cy] = b;
      const k = keys[ch];
      if (!within(x, y, cx, cy, r - 1)) return k.K;
      // The letter, centred: 3 x 5.
      const lx = x - Math.round(cx - 1.5);
      const ly = y - Math.round(cy - 2.5);
      if (lx >= 0 && lx < 3 && ly >= 0 && ly < 5 && letterAt(ch, lx, ly)) return k.L;
      // Domed: lit where a circle nudged down-right doesn't reach, shaded where one nudged up-left doesn't.
      if (!within(x, y, cx + 1.1, cy + 1.1, r - 1)) return k.H;
      if (!within(x, y, cx - 1, cy - 1, r - 1)) return k.D;
      if (within(x, y, cx - r * 0.55, cy - r * 0.55, 1.2)) return k.W;
      return k.G;
    }),
  };
}

/**
 * An analog stick in its round well, [size] across: a ridged cap with a dished top, and on the Thor a
 * light ring between cap and well ([glow]: the ring's colours, lit / mid / dim, and a faint halo on the
 * shell). [c] = { K outline, B well, R / r / Q ridges light / mid / dark, P / p dish light / dark, E dish edge }.
 */
export function stick(c, size = 24, glow = null) {
  const pad = glow ? 1 : 0;
  const n = size + pad * 2;
  const R = size / 2;
  const capR = R * (glow ? 0.68 : 0.76);
  const dishR = capR - 2.4;
  return {
    pal: { K: c.K, B: c.B, R: c.R, r: c.r, Q: c.Q, P: c.P, p: c.p, E: c.E, L: c.L ?? c.R, ...(glow ? { C: glow[0], M: glow[1], m: glow[2], h: glow[3] } : {}) },
    rows: paint(n, n, (x, y) => {
      const dx = x + 0.5 - n / 2;
      const dy = y + 0.5 - n / 2;
      const d = Math.hypot(dx, dy);
      // Light from the top left: +1 lit, -1 in shade.
      const lit = -(dx + dy) / (Math.SQRT2 * Math.max(d, 0.001));
      if (d > R) return glow && d <= R + 1 ? 'h' : null;
      if (d > R - 1) return 'K';
      if (d > capR + 1) {
        if (!glow) return d > R - 2 && lit < -0.3 ? 'L' : 'B';
        // The ring hugs the cap, brightest at the top left, a pixel of dark well outside it.
        if (d > R - 1.8) return 'B';
        return lit > 0.35 ? 'C' : lit > -0.35 ? 'M' : 'm';
      }
      if (d > capR) return 'K';
      // The cap's rim: ridges round it, catching the light on the upper left.
      if (d > capR - 2.4) {
        const ridge = Math.floor(((Math.atan2(dy, dx) + Math.PI) / (2 * Math.PI)) * 22) % 2;
        if (lit > 0.4) return ridge ? 'R' : 'r';
        if (lit > -0.4) return ridge ? 'r' : 'Q';
        return 'Q';
      }
      if (d > dishR) return 'E';
      // The dish: hollow, so its rim is in shade on the upper left and lit on the lower right.
      if (d > dishR - 1.4) return lit > 0.3 ? 'p' : lit < -0.3 ? 'R' : 'P';
      return 'P';
    }),
  };
}

/** The AYN Thor, black: dark plastic controls, grey letters, cyan stick lights. */
const THOR_BUTTON = { K: '#060608', H: '#4c4c58', G: '#2a2a32', D: '#18181e', W: '#70707e', L: '#b4b4c4' };
const THOR_CAP = { K: '#060608', B: '#101014', R: '#5a5a66', r: '#3e3e48', Q: '#26262e', P: '#2c2c34', p: '#34343e', E: '#1a1a20' };
const THOR_GLOW = ['#b4f4ff', '#58d8f8', '#2a9cd0', 'rgba(110, 220, 255, 0.28)'];

/** The Retroid Pocket 5's light grey "16-bit" shell: a dark d-pad, lavender X / Y, purple A / B, black sticks. */
const RP5_LAVENDER = { K: '#3a2f6a', H: '#ded6fa', G: '#b8aae6', D: '#8e7ec8', W: '#f2eeff', L: '#3a2c78' };
const RP5_PURPLE = { K: '#241a52', H: '#8a78d6', G: '#5e4cac', D: '#3e3086', W: '#a898ea', L: '#d4ccf6' };
const RP5_CAP = { K: '#0c0c10', B: '#18181e', R: '#5c5c68', r: '#3e3e48', Q: '#24242c', P: '#2c2c34', p: '#363640', E: '#18181e', L: '#3a3a44' };

export const HANDHELD_SPRITES = {
  thorDpad2: dpad({ K: '#060608', H: '#4e4e5a', G: '#2c2c34', D: '#1a1a20', A: '#18181e', a: '#40404a' }),
  thorAbxy2: abxy({ X: THOR_BUTTON, Y: THOR_BUTTON, A: THOR_BUTTON, B: THOR_BUTTON }, 6, 10.5),
  thorStick2: stick(THOR_CAP, 24, THOR_GLOW),
  rp5Dpad: dpad({ K: '#121218', H: '#6a6a78', G: '#44444e', D: '#2c2c34', A: '#2a2a32', a: '#5a5a66' }, 22, 8),
  rp5Abxy: abxy({ X: RP5_LAVENDER, Y: RP5_LAVENDER, A: RP5_PURPLE, B: RP5_PURPLE }, 5, 8.5),
  rp5Stick: stick(RP5_CAP, 20),
};
