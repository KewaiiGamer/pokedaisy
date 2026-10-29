// Sizes a hero handheld ([data-handheld], [data-units] units wide) to its box: one unit (--u) is the
// handheld's pixel - its controls are sprites one sprite pixel a unit - so it takes a whole number of device
// pixels when that's within 10% of the box (crisp), else the exact fraction (a phone's screen, too sharp to
// tell). The box's width never depends on the handheld (it's measured, the handheld sized from it).
export function fitHandhelds() {
  for (const el of document.querySelectorAll('[data-handheld]')) {
    if (el.dataset.fitted) continue;
    el.dataset.fitted = '1';
    const box = el.parentElement;
    const n = Number(el.dataset.units);
    const fit = () => {
      const dpr = window.devicePixelRatio || 1;
      const exact = box.clientWidth / n;
      const crisp = Math.floor(exact * dpr) / dpr;
      el.style.setProperty('--u', `${crisp >= exact * 0.9 ? crisp : exact}px`);
    };
    fit();
    new ResizeObserver(fit).observe(box);
  }
}
