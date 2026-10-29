// Run from the project root: node tools/mock-location-ui-selftest.js
// Minimal DOM/bridge simulation: checks page logic, not actual WebView rendering.
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');

class Classes {
  constructor() { this.items = new Set(); }
  add(...names) { names.forEach(n => this.items.add(n)); }
  remove(...names) { names.forEach(n => this.items.delete(n)); }
  contains(name) { return this.items.has(name); }
  toggle(name, force) {
    const enabled = force === undefined ? !this.items.has(name) : !!force;
    if (enabled) this.items.add(name); else this.items.delete(name);
    return enabled;
  }
}
class Element {
  constructor(tag, id) {
    this.tagName = tag.toUpperCase(); this.id = id || '';
    this.style = {}; this.dataset = {}; this.classList = new Classes(); this.children = [];
    this.handlers = {}; this.attributes = {}; this.value = '';
    this.clientWidth = 400; this.clientHeight = 560; this._text = ''; this._html = '';
  }
  set textContent(value) { this._text = String(value); this.children = []; }
  get textContent() { return this._text + this.children.map(c => c.textContent).join(''); }
  set innerHTML(value) { this._html = String(value); this.children = []; this._text = ''; }
  get innerHTML() { return this._html; }
  get firstChild() { return this.children[0] || null; }
  get firstElementChild() { return this.firstChild; }
  appendChild(child) { child.parentNode = this; this.children.push(child); return child; }
  insertBefore(child, before) {
    child.parentNode = this; const index = this.children.indexOf(before);
    if (index < 0) this.children.push(child); else this.children.splice(index, 0, child);
    return child;
  }
  remove() { if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(c => c !== this); }
  addEventListener(name, callback) { (this.handlers[name] ||= []).push(callback); }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  getAttribute(name) { return this.attributes[name] || null; }
  getBoundingClientRect() { return {left: 0, top: 0, width: this.clientWidth, height: this.clientHeight}; }
  setPointerCapture() {}
  closest() { return null; }
  blur() {}
  focus() {}
  click() { if (typeof this.onclick === 'function') this.onclick({target: this}); }
}
function setup(mode, status) {
  const html = fs.readFileSync('app/src/main/assets/mock_location.html', 'utf8');
  const match = html.match(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/i);
  assert(match, 'map page must contain its script');
  const elements = {};
  for (const found of html.matchAll(/\bid="([^"]+)"/g)) elements[found[1]] = new Element('div', found[1]);
  const document = {body: new Element('body'), documentElement: new Element('html'),
    getElementById: id => elements[id] || null,
    createElement: tag => new Element(tag), createElementNS: (_, tag) => new Element(tag),
    querySelector: selector => selector.startsWith('#') ? elements[selector.slice(1)] : null,
    addEventListener() {}};
  if (elements.bar) elements.bar.appendChild(new Element('i'));
  if (elements.speed) elements.speed.value = '1.4';
  if (elements.jitter) elements.jitter.value = '2';
  const calls = {starts: [], searches: [], canceled: 0};
  const transform = (csv, sign) => csv.split(',').map(x => Number(x) + sign * 0.002).join(',');
  const bridge = {
    toGcj02: value => transform(value, 1), toWgs84: value => transform(value, -1),
    status: () => JSON.stringify(status), permitted: () => true, locationEnabled: () => true,
    developerOptionsEnabled: () => true,
    search: value => calls.searches.push(value), cancelSearch: () => calls.canceled++,
    start: (...args) => calls.starts.push(args), stop() {}, togglePause() {}, setMultiplier() {},
    openDeveloperOptions() {}, openLocationSettings() {}
  };
  const context = {document, location: {search: '?mode=' + mode}, ToolboxMock: bridge,
    console, navigator: {onLine: true}, setTimeout() {return 1;}, clearTimeout() {},
    requestAnimationFrame() {return 1;}, setInterval() {return 1;}, clearInterval() {}, addEventListener() {}};
  context.window = context; vm.createContext(context); vm.runInContext(match[1], context, {filename: 'mock_location.html'});
  return {elements, context, calls};
}

const current = {state: 'running', lat: 31.22, lng: 121.44, total: 0, travelled: 0, multiplier: 1};
const ui = setup('footprint', current);
const initialText = ui.elements.pts.textContent;
assert(initialText.includes('31.222') && initialText.includes('121.442'), 'active mock position must be carried into footprint as the starting point (GCJ-02)');
ui.elements.start.click();
assert.strictEqual(ui.calls.starts.length, 0, 'one point must not start a footprint');
assert(ui.elements.stext.textContent.includes('终点'), 'missing destination must be explained');
ui.elements.q.value = '31.3000，121.5000';
ui.elements.go.click();
assert(ui.elements.pts.textContent.includes('31.3000'), 'coordinate input adds the destination');
assert.strictEqual(ui.calls.searches.length, 0, 'coordinates must not be sent to a search provider');
assert.strictEqual(ui.calls.canceled, 1, 'manual coordinates cancel any in-flight search');
current.lat = 31.221; current.lng = 121.441;
ui.elements.start.click();
assert.strictEqual(ui.calls.starts.length, 1, 'the route starts after selecting a destination');
const path = ui.calls.starts[0][0].split(';').map(p => p.split(',').map(Number));
assert(Math.abs(path[0][0] - 31.221) < 1e-6 && Math.abs(path[0][1] - 121.441) < 1e-6, 'route starts at latest active WGS-84 position, not the stale seed');
assert(Math.abs(path[1][0] - 31.298) < 1e-6 && Math.abs(path[1][1] - 121.498) < 1e-6, 'destination is converted back to WGS-84');

const centerBefore = ui.elements.coords.textContent;
ui.context.MockUI.onStatus(JSON.stringify({...current, lat: 31.24, total: 1000, travelled: 20}));
assert.strictEqual(ui.elements.coords.textContent, centerBefore, 'location updates must not move the map while editing');
assert(ui.elements.markers.children.some(e => e.className === 'live-position'), 'active position has a map marker');

ui.elements.q.value = '91,121';
ui.elements.go.click();
assert.strictEqual(ui.elements.results.dataset.state, 'error', 'invalid coordinates show an error');
assert(ui.elements.results.textContent.includes('坐标超出范围'));
ui.elements.q.value = '上海外滩';
ui.elements.go.click();
assert.deepStrictEqual(ui.calls.searches, ['上海外滩']);
ui.context.MockUI.onSearchResult(JSON.stringify({ok:false, error:'<img src=x onerror=alert(1)>'}));
assert(ui.elements.results.textContent.includes('<img src=x onerror=alert(1)>'), 'errors are displayed as text');
assert.strictEqual(ui.elements.results.innerHTML, '', 'error text must not be interpolated into HTML');
ui.elements.q.value = '上海外滩'; ui.elements.go.click();
ui.context.MockUI.onSearchResult(JSON.stringify([{name:'外滩',detail:'上海',lat:31.24,lng:121.49}]));
assert.strictEqual(ui.elements.results.children.length, 1, 'one search result is displayed');
ui.elements.results.children[0].click();
assert(ui.elements.pts.textContent.includes('31.2400'), 'choosing a search result adds a route point');

const zeroUi = setup('footprint', {state:'running', lat:0, lng:0, total:0, travelled:0, multiplier:1});
assert(zeroUi.elements.pts.textContent.includes('0.0020'), 'zero latitude and longitude are valid active coordinates');
zeroUi.elements.clear.click();
zeroUi.context.MockUI.onStatus(JSON.stringify({state:'running', lat:0, lng:0, total:0, travelled:0, multiplier:1}));
assert(zeroUi.elements.pts.textContent.includes('还没有添加足迹点'), 'clearing a seeded route must not reseed it');
const idle = {state:'idle', lat:0, lng:0, total:0, travelled:0, multiplier:1};
const permissionUi = setup('fixed', idle);
delete permissionUi.context.ToolboxMock.locationEnabled;
permissionUi.context.MockUI.refreshPermission();
assert.strictEqual(permissionUi.elements.start.disabled, true, 'permission-check errors must not enable the start button');
assert(permissionUi.elements.banner.innerHTML.includes('无法检查定位权限'));
const mapUi = setup('footprint', idle);
const tiles = mapUi.elements.map.children.filter(e => e.tagName === 'IMG');
assert(tiles.length > 0, 'initial viewport requests map tiles');
const tile = tiles[0];
const amap = new URL(tile.src);
tile.onerror();
const tencent = new URL(tile.src);
assert.strictEqual(tencent.hostname, 'rt0.map.gtimg.com', 'a failed primary tile uses independent fallback');
assert.strictEqual(Number(tencent.searchParams.get('x')), Number(amap.searchParams.get('x')));
assert.strictEqual(Number(tencent.searchParams.get('y')), Math.pow(2, Number(amap.searchParams.get('z'))) - 1 - Number(amap.searchParams.get('y')), 'Tencent tile Y axis is inverted');
tile.onerror();
assert(tile.src.includes('webrd0'), 'a second Amap source is tried after Tencent');
for (const image of tiles) {
  while (image._state === 'loading') image.onerror();
}
assert(mapUi.elements.mapstatus.innerHTML.includes('地图没加载出来'), 'complete tile failure has a visible error');
const down = {target:{closest:()=>null},pointerId:1,clientX:200,clientY:180};
mapUi.elements.map.handlers.pointerdown[0](down);
mapUi.elements.map.handlers.pointerup[0]({...down,type:'pointerup'});
assert(!mapUi.elements.pts.textContent.includes('还没有'), 'map remains selectable even if all tiles fail');

console.log('PASS: current position, moving handoff, route conversion, manual coordinates, search, error handling, tile fallback and offline selection');
