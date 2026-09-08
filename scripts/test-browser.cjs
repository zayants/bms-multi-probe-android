// Run with Node.js: node scripts/test-browser.cjs
// Executes the actual browser asset with a minimal DOM and a mocked read-only API.
// This checks behaviour, not pixel layout or real-device rendering.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = path.resolve(__dirname, '../app/src/main');
const asset = fs.readFileSync(path.join(source, 'assets/monitor.html'), 'utf8');
const decode = text => text.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
  .replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/\\'/g, "'").replace(/\\n/g, '\n');
function resources(file, tag) {
  return Object.fromEntries([...fs.readFileSync(path.join(source, file), 'utf8')
    .matchAll(new RegExp('<' + tag + ' name="([^"]+)"[^>]*>([\\s\\S]*?)</' + tag + '>', 'g'))]
    .map(match => [match[1], decode(match[2])]));
}
const appearance = resources('res/values/appearance.xml', 'string-array');
const items = value => [...value.matchAll(/<item>(.*?)<\/item>/g)].map(match => match[1]);
const tags = items(appearance.language_tags), names = items(appearance.language_names);
const mappings = fs.readFileSync(path.join(source, 'kotlin/com/zayants/bmsmultiprobe/ui/UiText.kt'), 'utf8');
const config = {language: 'en', theme: 'dark', languages: tags.map((tag, i) => ({tag, name: names[i]})), catalogs: {}, palettes: {}};
for (const tag of tags) {
  const dictionary = resources('res/' + (tag === 'en' ? 'values' : 'values-' + tag) + '/strings.xml', 'string');
  dictionary.statuses = {}; dictionary.alarms = {};
  for (const match of mappings.matchAll(/"([^"]+)" to R\.string\.(\w+)/g)) {
    dictionary[match[2].startsWith('alarm_') ? 'alarms' : 'statuses'][match[1]] = dictionary[match[2]];
  }
  config.catalogs[tag] = dictionary;
}
for (const theme of ['light', 'dark']) {
  config.palettes[theme] = Object.fromEntries(Object.entries(resources(
    'res/' + (theme === 'light' ? 'values' : 'values-night') + '/colors.xml', 'color'))
    .map(([key, value]) => [key.replace('ui_', ''), value]));
}
const fixture = {freshCount: 1, sessionCount: 1, sampledAt: 1700000000000, sessions: [{
  name: '<img src=x onerror=alert(1)>', address: 'AA:BB:CC:DD:EE:FF', status: 'telemetry',
  socPercent: 73, packVoltageV: 26.17, currentA: -2.53, temperatureC: 24.3,
  cellsV: [3.271, 3.268], sampleAgeMs: 460, balancingState: 'off',
  stale: false, connected: true, hasAlarm: true, alarms: ['Cell under-voltage protection']
}]};
function harness(saved = new Map()) {
  const nodes = new Map();
  function node(id) {
    if (!nodes.has(id)) nodes.set(id, {
      textContent: '', innerHTML: '', value: '', children: [], open: false, handlers: {},
      append(child) {this.children.push(child)},
      replaceChildren(...children) {this.children = children},
      addEventListener(event, handler) {this.handlers[event] = handler},
      showModal() {this.open = true}, close() {this.open = false}
    });
    return nodes.get(id);
  }
  const labels = [...asset.matchAll(/data-text="([^"]+)"/g)].map(m => ({dataset: {text: m[1]}, textContent: ''}));
  const style = {setProperty(key, value) {this[key] = value}};
  const state = {mode: 'ok', requests: []};
  const sandbox = {
    Intl, Date, AbortController, console,
    document: {getElementById: node, createElement: () => node(Symbol()), querySelectorAll: () => labels, documentElement: {style}},
    localStorage: {getItem: key => saved.get(key), setItem: (key, value) => saved.set(key, value)},
    setTimeout: (fn, delay) => setTimeout(fn, Math.min(delay, 50)), clearTimeout,
    setInterval: () => 0,
    fetch: async (url, options) => {
      state.requests.push({url, options});
      if (state.mode === 'error') throw Error('offline');
      if (state.mode === 'hang') return new Promise((resolve, reject) => options.signal.addEventListener('abort', () => reject(Error('timeout'))));
      return {ok: true, json: async () => JSON.parse(JSON.stringify(fixture))};
    }
  };
  vm.createContext(sandbox);
  const js = asset.match(/<script>([\s\S]*?)<\/script>/)[1].replace('__UI_CONFIG__', JSON.stringify(config));
  vm.runInContext(js, sandbox);
  return {node, style, sandbox, state, labels, saved};
}
(async () => {
  const h = harness();
  await new Promise(setImmediate);
  for (const language of tags) for (const theme of ['light', 'dark']) {
    h.node('language').value = language; h.node('language').onchange();
    h.node('theme').value = theme; h.node('theme').onchange();
    assert.equal(h.sandbox.document.documentElement.lang, language);
    assert.equal(h.style['--background'], config.palettes[theme].background);
    assert.equal(h.node('error').textContent, '');
    assert.match(h.node('sessions').innerHTML, /73%/);
    assert.ok(h.node('sessions').innerHTML.includes(new Intl.NumberFormat(language, {minimumFractionDigits: 3}).format(3.271)));
    assert.ok(h.node('sessions').innerHTML.includes(config.catalogs[language].voltage));
    assert.ok(h.node('sessions').innerHTML.includes('&lt;img'));
    assert.ok(!h.node('sessions').innerHTML.includes('<img'));
    assert.ok(!h.node('sessions').innerHTML.includes('%1$'));
    vm.runInContext('showAlarms(0)', h.sandbox);
    assert.equal(h.node('alarm-list').children[0].textContent, config.catalogs[language].alarm_3);
    assert.equal(h.node('alarms').open, true);
    h.node('alarms').close();
    assert.ok(vm.runInContext("status('MTU 247; discovering')", h.sandbox).includes('247'));
    assert.ok(vm.runInContext("status('notification error 133')", h.sandbox).includes('133'));
  }
  h.state.mode = 'error';
  await vm.runInContext('refresh()', h.sandbox);
  assert.ok(h.node('error').textContent.includes(config.catalogs.uk.load_failed));
  assert.ok(!h.node('sessions').innerHTML.includes('73%'), 'Old numbers must not look live');
  assert.match(h.node('sessions').innerHTML, /class="stale"/);
  h.state.mode = 'ok';
  await vm.runInContext('refresh()', h.sandbox);
  assert.match(h.node('sessions').innerHTML, /73%/);
  assert.equal(h.node('error').textContent, '');
  h.state.mode = 'hang';
  await vm.runInContext('refresh()', h.sandbox);
  assert.ok(h.node('error').textContent, 'Timeout must mark data stale');
  assert.ok(h.state.requests.every(r => r.url === '/api/v1/multi/snapshot' && !r.options.method && r.options.cache === 'no-store'));
  const reloaded = harness(h.saved);
  await new Promise(setImmediate);
  assert.equal(reloaded.node('language').value, 'uk');
  assert.equal(reloaded.node('theme').value, 'dark');
  console.log('PASS: 6 language/theme combinations; alarm translation; escaping; cell precision; offline/timeout/recovery; saved preferences; read-only requests.');
})().catch(error => {console.error(error); process.exitCode = 1});
