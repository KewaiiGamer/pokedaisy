// The hero's MAP tab: an invented region in the style of the games' region maps (no game art) - sea with
// its wave lines, green land with a dark shore and round coasts, forests, mountains, sandy routes, sea
// routes and red towns. Laid out as tiles in TILES below, each tile [T] pixels square, so the demo can put
// the cursor and the player's head on a place by its tile.

export const T = 7;

// 20 x 16 tiles. ~ sea, , land, f forest, m mountain, = route, : sea route, and a letter for each place
// (PLACES, below). Routes join every route or place next to them.
const TILES = [
  '~~~~~~~~~~~~~~~~~~~~',
  '~~~~~~,,,mmm,~~~~~~~',
  '~~~~,,,,mmPmm,~~~~~~',
  '~~~,,ff,,,=,,fff,~~~',
  '~~,,fff,,,=,,fff,,~~',
  '~~,,,f,,,,=,,,ff,,~~',
  '~~,,B=====+=====S,~~',
  '~~,,=,,,,,=,,,,,,,~~',
  '~~,,=,ff,,=,,mm,,,~~',
  '~~~,=,fff,=,mmm,,~~~',
  '~~~,=,,f,,D,,,,,,~~~',
  '~~~,=,,,,,=~~~~~~~~~',
  '~~~~H=====+::::=C,~~',
  '~~~~,,,,~~~~~~~,,,~~',
  '~~~~~~~~~~~~~~~,,~~~',
  '~~~~~~~~~~~~~~~~~~~~',
];

/** Each place's letter, name and tile. MEADOW PATH is the route the player stands on. */
export const PLACES = [
  { id: 'H', name: 'PETAL TOWN' },
  { id: 'B', name: 'BLOSSOM CITY' },
  { id: 'P', name: 'SUNNY PEAK' },
  { id: 'S', name: 'STARFALL CITY' },
  { id: 'D', name: 'DEWDROP PORT' },
  { id: 'C', name: 'COVE ISLE' },
].map((p) => {
  const row = TILES.findIndex((r) => r.includes(p.id));
  return { ...p, x: TILES[row].indexOf(p.id), y: row };
});
export const PLAYER = { name: 'MEADOW PATH', x: 4, y: 9 };

const PAL = {
  '~': '#4878d0', w: '#7aa6ec', f: '#a8d0f8',
  g: '#8cd060', G: '#78bc50', k: '#2c6a34',
  t: '#2e8a3c', T: '#1e6a2e', u: '#58b050',
  m: '#a08060', M: '#c8a880', n: '#6e5440', s: '#f8f8f8',
  r: '#f0d088', R: '#c89848',
  K: '#5a1810', o: '#e83828', O: '#ff8870',
};

const at = (x, y) => (TILES[y]?.[x] ?? '~');
const isSea = (c) => c === '~' || c === ':';
const joins = (c) => c === '=' || c === '+' || c === ':' || /[A-Z]/.test(c);

/** Whether pixel (px, py) of the map is land: tiles, with their outer corners rounded where both sides meet sea. */
function land(px, py) {
  const tx = Math.floor(px / T);
  const ty = Math.floor(py / T);
  if (isSea(at(tx, ty))) return false;
  const lx = px - tx * T + 0.5;
  const ly = py - ty * T + 0.5;
  const h = T / 2;
  for (const [sx, sy] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) {
    if (!isSea(at(tx + sx, ty)) || !isSea(at(tx, ty + sy)) || !isSea(at(tx + sx, ty + sy))) continue;
    const cx = sx < 0 ? Math.max(0, h - lx) : Math.max(0, lx - h);
    const cy = sy < 0 ? Math.max(0, h - ly) : Math.max(0, ly - h);
    if (Math.hypot(cx, cy) > h) return false;
  }
  return true;
}

function pixel(px, py) {
  const tx = Math.floor(px / T);
  const ty = Math.floor(py / T);
  const lx = px - tx * T;
  const ly = py - ty * T;
  const c = at(tx, ty);
  const mid = (T - 1) / 2;

  // A route (or a place's link to one): a 3-pixel sandy band to the middle of each joining side.
  const onRoute = (path) => {
    if (!joins(c)) return null;
    const arms = [[0, -1], [0, 1], [-1, 0], [1, 0]].filter(([dx, dy]) => joins(at(tx + dx, ty + dy)));
    const inBand = (along, across, dir) => Math.abs(across - mid) <= 1 && (dir < 0 ? along <= mid : along >= mid);
    for (const [dx, dy] of arms) {
      if (dx === 0 && inBand(ly, lx, dy)) return path(Math.abs(lx - mid) === 1);
      if (dy === 0 && inBand(lx, ly, dx)) return path(Math.abs(ly - mid) === 1);
    }
    if (Math.abs(lx - mid) <= 1 && Math.abs(ly - mid) <= 1) return path(false);
    return null;
  };

  if (!land(px, py)) {
    // Sea routes: a dotted line over the water.
    if (c === ':') {
      const dot = onRoute((edge) => (edge ? null : (lx + ly) % 2 ? 'f' : null));
      if (dot) return dot;
    }
    // Foam where the sea meets land, then wave lines in a staggered pattern.
    if (land(px - 1, py) || land(px + 1, py) || land(px, py - 1) || land(px, py + 1)) return 'f';
    const wave = (py % 6 === 2 && (px + (py % 12 === 2 ? 0 : 5)) % 10 < 3);
    return wave ? 'w' : '~';
  }
  if (!land(px - 1, py) || !land(px + 1, py) || !land(px, py - 1) || !land(px, py + 1)) return 'k';

  if (/[A-Z]/.test(c)) {
    // A town: a red square with a dark outline and a lit top, on its routes.
    if (lx >= 1 && lx <= 5 && ly >= 1 && ly <= 5) {
      if (lx === 1 || lx === 5 || ly === 1 || ly === 5) return 'K';
      return ly === 2 ? 'O' : 'o';
    }
  }
  const route = onRoute((edge) => (edge ? 'R' : 'r'));
  if (route) return route;
  if (c === 'f') {
    // Two round trees a tile, light on top.
    const tree = (cx, cy) => Math.hypot(lx + 0.5 - cx, ly + 0.5 - cy) <= 2.1;
    for (const [cx, cy] of [[2, 2.5], [5, 5]]) {
      if (tree(cx, cy)) return ly + 0.5 < cy - 0.8 ? 'u' : lx + 0.5 > cx + 0.6 ? 'T' : 't';
    }
  }
  if (c === 'm') {
    // A peak: a triangle, lit on its left, snow on top.
    const w = ly;
    if (ly >= 1 && Math.abs(lx - 3) <= w / 2 + 0.2 && ly <= 6) {
      if (ly <= 2) return 's';
      if (Math.abs(lx - 3) >= w / 2 - 0.3) return 'n';
      return lx < 3 ? 'M' : 'm';
    }
  }
  return (px * 7 + py * 3) % 11 === 0 ? 'G' : 'g';
}

/** The open sea alone, one period of its wave lines (10 x 12): the MAP pane's backdrop past the map's sides. */
export function seaSprite() {
  const rows = [];
  for (let y = 0; y < 12; y++) {
    let row = '';
    for (let x = 0; x < 10; x++) row += (y % 6 === 2 && (x + (y % 12 === 2 ? 0 : 5)) % 10 < 3) ? 'w' : '~';
    rows.push(row);
  }
  return { pal: PAL, rows };
}

export function regionMapSprite() {
  const w = TILES[0].length * T;
  const h = TILES.length * T;
  const rows = [];
  for (let y = 0; y < h; y++) {
    let row = '';
    for (let x = 0; x < w; x++) row += pixel(x, y);
    rows.push(row);
  }
  return { pal: PAL, rows };
}
