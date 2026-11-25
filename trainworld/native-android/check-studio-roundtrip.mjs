// SPDX-License-Identifier: AGPL-3.0-only
// Run with the Studio web app's node_modules and generated track WASM available.
// node check-studio-roundtrip.mjs [path/to/trainworld/app] [jak_bogor.save android-after-playing.save]
// The optional pair checks a real passenger service that ran on a device and was paused without edits.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { copyFile, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const native = fileURLToPath(new URL('./', import.meta.url));
const web = resolve(process.argv[2] || join(native, '../app'));
const wasm = join(web, 'src/wasm/track');
const { default: init, TrackApi } = await import(pathToFileURL(join(wasm, 'sim.js')));
await init({ module_or_path: await readFile(join(wasm, 'sim_bg.wasm')) });
const { rolldown } = await import(pathToFileURL(createRequire(join(web, 'package.json')).resolve('rolldown')));

function rows(values, stride) {
  assert.equal(values.length % stride, 0);
  return Array.from({ length: values.length / stride }, (_, i) =>
    Array.from(values.subarray(i * stride, (i + 1) * stride)));
}

function lines(api) {
  return rows(api.lines_info(), 22).map(([id]) => ({
    id, name: api.line_name(id), stops: Array.from(api.line_stops(id)),
    path: Array.from(api.run_polyline(id, 0, 70)), weekend: Array.from(api.line_weekend(id)),
  }));
}

function stations(api) {
  return rows(api.nodes_info(), 10).filter(r => r[7] > 0 && r[8] > 0)
    .map(([id, x, y]) => ({ id, x, y, name: api.node_name(id) }));
}

function addTokyoLine(api, projection) {
  api.set_unlimited(true);
  const a = projection.fromLonLat(139.75, 35.67), b = projection.fromLonLat(139.78, 35.67);
  assert.equal(api.add_route(Float64Array.of(0, 0, ...a, 0, 0, 0, ...b, 0), new Float64Array(), 2, false), 0);
  const stops = [a, b].map(([x, y], index) => {
    const node = rows(api.nodes_info(), 10).find(r => Math.hypot(x - r[1], y - r[2]) < 1);
    assert.ok(node);
    assert.equal(api.add_station(1, node[0], x, y, 160, '東京 ' + index), 0);
    return node[0];
  });
  const id = api.new_line_id();
  assert.equal(api.set_line_with_trains(id, Uint32Array.from(stops), 4, 3, 2, 20, 60, 4, 0x007866, '東京線'), 0);
  assert.equal(api.construct_line(id), 0);
}

function inMercator(track) {
  return String.fromCharCode(...track.subarray(0, 4)) === 'TWT6' ? track : TrackApi.convert_save_frame(track, 2);
}

const dir = await mkdtemp(join(tmpdir(), 'studio-roundtrip-'));
const before = new TrackApi(-74, 40.7, true);
const after = new TrackApi(-74, 40.7, true);
try {
  const bundle = await rolldown({
    input: {
      codec: join(web, 'src/workers/saveFile.ts'), frame: join(web, 'src/game/frame.ts'),
      timing: join(web, 'src/game/moneyTiming.ts'), calendar: join(web, 'src/game/days.ts'),
    },
    platform: 'node',
    plugins: [{
      name: 'period-names-without-ui',
      resolveId(id, importer) {
        if (id === '../i18n' && /\/(periods|days|lazyText)\.ts$/.test(importer || '')) return '\0test-labels';
      },
      load(id) { if (id === '\0test-labels') return 'export const t = key => key;'; },
    }],
  });
  try { await bundle.write({ dir, entryFileNames: '[name].mjs', format: 'es' }); }
  finally { await bundle.close(); }
  const { encodeSave, decodeSave, isThisWorld, headerUnread, SAVE_HEADER_FIELDS } = await import(pathToFileURL(join(dir, 'codec.mjs')));
  const { makeFrame } = await import(pathToFileURL(join(dir, 'frame.mjs')));
  const projection = makeFrame({ kind: 'merc', lon: -73.985, lat: 40.758 });
  const registry = JSON.parse(await readFile(join(web, 'src/game/registry.json'), 'utf8'));
  const zones = Float64Array.from([registry.clockUtcOffset, 120000,
    ...registry.cities.flatMap(c => [...c.center, c.utcOffset ?? Math.round(c.center[0] / 15)])]);
  before.set_time_zones(zones);
  after.set_time_zones(zones);
  if (process.argv.length > 3) {
    assert.equal(process.argv.length, 5, 'pass both the Jakarta template and its Android checkpoint');
    const source = await decodeSave(await readFile(process.argv[3]));
    const checkpoint = await decodeSave(await readFile(process.argv[4]));
    for (const [file, api] of [[source, before], [checkpoint, after]]) {
      assert.ok(isThisWorld(file.header));
      assert.equal(headerUnread(file.header, TrackApi.save_level()), false);
      assert.equal(TrackApi.save_unread(file.track), 0);
      assert.ok(api.load(inMercator(file.track)));
    }
    const bogor = lines(before).find(line => line.name === 'Commuter Line Bogor');
    assert.ok(bogor, 'expected a populated Jakarta–Bogor fixture');
    assert.equal(checkpoint.header.mode, 'normal');
    assert.ok(checkpoint.header.clock > source.header.clock);
    assert.deepEqual(stations(before), stations(after));
    assert.deepEqual(lines(before), lines(after));
    const end = source.header.clock + 7200;
    const trips = before.line_trips(bogor.id, source.header.clock, end);
    assert.ok(trips.length);
    assert.deepEqual(after.line_trips(bogor.id, source.header.clock, end), trips);
    const total = (ledger, key) => ledger.reduce((sum, row) => sum + (row[key] ?? 0), 0);
    const earned = key => total(checkpoint.header.ledger, key) - total(source.header.ledger, key);
    for (const key of ['fares', 'rides', 'running', 'upkeep']) assert.ok(earned(key) > 0, key);
    assert.ok(Math.abs(checkpoint.header.cash - source.header.cash -
      (earned('fares') - earned('running') - earned('upkeep'))) < 1e-5);
    console.log('Android reference checkpoint loaded by Studio:', JSON.stringify({
      fares: earned('fares'), rides: earned('rides'), running: earned('running'),
      upkeep: earned('upkeep'), cash: checkpoint.header.cash, stations: stations(after).length,
    }));
  }
  const original = await decodeSave(await readFile(join(native, '../T-007-real-nyc.save')));
  const fixtures = [];
  for (const frame of [0, 1, 2]) {
    assert.ok(before.load(frame === 0 ? original.track : TrackApi.convert_save_frame(original.track, frame)));
    if (frame === 2) addTokyoLine(before, projection);
    const track = before.save();
    const header = {
      ...original.header, mode: 'normal', start: 'nyc', home: frame === 2 ? 'world' : 'nyc',
      opened: frame === 2 ? ['nyc', 'tyo'] : ['nyc'], fleet: before.cars_needed(),
      fleetFast: 7, fleetBus: 5, fleetFerry: 2, fleetTram: 3,
      fares: { base: 2, perKm: 0.25, nodes: [[0, 2], [10, 4.5]], lines: { '0': 1.2 } },
      achievements: { sample: { at: 86500, of: 'Gare du Nord 🚆' } }, notes: { '0': [4, 'nyc'] },
      ledger: [{ day: 0, fares: 19, running: 3, trains: 2, build: 4, rides: 127 }],
    };
    if (frame > 0) header.level = frame === 2 ? TrackApi.save_level() : 1;
    if (SAVE_HEADER_FIELDS.has('tpl')) header.tpl = true;
    assert.equal(headerUnread(header, TrackApi.save_level()), false);
    fixtures.push({ header, track });
    await writeFile(join(dir, `web-${frame}.save`), await encodeSave(header, track));
  }
  const future = { ...fixtures[2].header, level: TrackApi.save_level() + 1 };
  await writeFile(join(dir, 'future-level.save'), await encodeSave(future, fixtures[2].track));
  const unknown = Uint8Array.from([...original.track, 0x58, 0x59]);
  assert.ok(TrackApi.save_unread(unknown) > 0);
  await writeFile(join(dir, 'unread-network.save'), await encodeSave(original.header, unknown));
  const localJakarta = new TrackApi(106.8022, -6.1876, true);
  try {
    const legacy = { ...original.header, city: 'jak', mode: 'sandbox', nativeWorkshop: true, nativeLastLine: 0 };
    await writeFile(join(dir, 'legacy-jak.save'), await encodeSave(legacy, localJakarta.save()));
  } finally { localJakarta.free(); }
  // UTC-5 game clock 11:59:50 is 23:59:50 in Jakarta; the next 20 seconds span both books.
  const accounting = { ...fixtures[2].header, clock: 43190, cash: 1000, start: 'jak', ledger: [], hours: [], regions: {} };
  await writeFile(join(dir, 'accounting-web.save'), await encodeSave(accounting, fixtures[2].track));
  execFileSync('cargo', ['test', '--locked', '--release', '--lib', 'tests::edit_studio_fixture',
    '--', '--ignored', '--exact'], { cwd: native, stdio: 'inherit', env: { ...process.env, STUDIO_FIXTURE_DIR: dir } });

  for (const [frame, { header, track }] of fixtures.entries()) {
    const restored = await decodeSave(await readFile(join(dir, `android-${frame}.save`)));
    assert.equal(String.fromCharCode(...restored.track.subarray(0, 4)), `TWT${frame + 4}`);
    assert.ok(before.load(inMercator(track)));
    assert.ok(isThisWorld(restored.header), 'Studio must recognize its world');
    assert.equal(headerUnread(restored.header, TrackApi.save_level()), false, 'Studio must allow normal saves');
    assert.equal(TrackApi.save_unread(restored.track), 0);
    assert.ok(after.load(inMercator(restored.track)));
    const oldLines = lines(before), newLines = lines(after);
    const oldStations = stations(before), newStations = stations(after);
    const oldLineIds = new Set(oldLines.map(line => line.id));
    const oldStationIds = new Set(oldStations.map(station => station.id));
    assert.deepEqual(newLines.filter(line => oldLineIds.has(line.id)), oldLines);
    assert.deepEqual(newStations.filter(station => oldStationIds.has(station.id)), oldStations);
    assert.equal(newLines.length, oldLines.length + 1);
    assert.equal(newStations.length, oldStations.length + 2);
    const added = newLines.find(line => line.name === 'Lintas Android');
    assert.ok(added);
    assert.deepEqual(added.stops.map(id => newStations.find(s => s.id === id).name), ['Lintas Android A', 'Lintas Android B']);
    assert.ok(after.line_trips(added.id, header.clock, header.clock + 7200).length);
    for (const [key, value] of Object.entries(header)) {
      if (!['cash', 'fleet', 'ledger'].includes(key)) assert.deepEqual(restored.header[key], value, key);
    }
    assert.ok(restored.header.cash < header.cash, 'construction must debit the normal game');
    assert.ok(restored.header.fleet > header.fleet);
    assert.deepEqual(restored.header.ledger.slice(0, -1), header.ledger, 'previous days');
    const payment = header.cash - restored.header.cash;
    const day = restored.header.ledger.at(-1), hour = restored.header.hours.at(-1);
    assert.equal(day.day, Math.floor(header.clock / 86400));
    assert.equal(hour.hour, Math.floor(header.clock / 3600));
    assert.ok(day.build > 0 && day.trains > 0);
    assert.ok(Math.abs(day.build + day.trains - payment) < 1e-8);
    assert.equal(hour.build, day.build);
    assert.equal(hour.trains, day.trains);
    assert.deepEqual(restored.header.regions[''].ledger.at(-1), day);
    assert.deepEqual(restored.header.regions[''].hours.at(-1), hour);
    const resaved = await decodeSave(await encodeSave(restored.header, after.save()));
    assert.deepEqual(resaved.header, restored.header);
    assert.ok(before.load(resaved.track));
    assert.deepEqual(lines(before), newLines);
    console.log(`TWT${frame + 4} → Android Rust → Studio: ${oldLines.length} existing lines, ${oldStations.length} existing stations, 1 new service and 2 new stations.`);
  }
  for (const file of ['native-jak.save', 'migrated-jak.save']) {
    const { header, track } = await decodeSave(await readFile(join(dir, file)));
    assert.ok(isThisWorld(header));
    assert.equal(headerUnread(header, TrackApi.save_level()), false);
    assert.equal(header.home, 'jak');
    assert.equal(header.start, 'jak');
    assert.deepEqual(header.opened, ['jak']);
    assert.equal(TrackApi.save_unread(track), 0);
    assert.ok(after.load(inMercator(track)));
    if (file === 'native-jak.save') {
      assert.equal(after.line_name(0), 'Lintas Jakarta');
      const expected = [[106.8236, -6.1861], [106.8311, -6.1964]];
      for (const [i, { x, y }] of stations(after).entries()) {
        const [lon, lat] = projection.toLonLat(x, y);
        assert.ok(Math.hypot(lon - expected[i][0], lat - expected[i][1]) < 1e-5);
      }
      assert.ok(after.line_trips(0, header.clock, header.clock + 7200).length);
    }
    console.log(file, 'opened in Studio, home Jakarta');
  }
  const timed = await decodeSave(await readFile(join(dir, 'accounting-android.save')));
  assert.ok(after.load(timed.track));
  const upkeep = after.upkeep()[0] * 20 / 86400;
  const equalMoney = (a, b) => assert.ok(Math.abs(a - b) < 1e-8, `${a} differs from ${b}`);
  const japan = lines(after).find(line => line.name === '東京線');
  assert.ok(japan);
  const income = JSON.parse(await readFile(join(web, 'src/game/countryIncome.json'), 'utf8')).usd;
  after.set_line_run_factors(Float64Array.of(japan.id, Math.round(Math.sqrt(income.JP / income.US) * 100) / 100));
  // Both sampled hours are wholly inside operating periods, including the web worker's lag window:
  // weekday midday in NYC, overnight in Tokyo. No service ends at either boundary.
  const run = (after.running_cost_in(0, 0) + after.running_cost_in(2, 14 * 3600)) * 20 / 3600;
  equalMoney(accounting.cash - timed.header.cash, upkeep + run);
  for (const row of timed.header.hours) equalMoney(row.running, run / 2);
  assert.deepEqual(timed.header.ledger.map(row => row.day), [0, 1]);
  assert.deepEqual(timed.header.hours.map(row => row.hour), [11, 12]);
  for (const row of [...timed.header.ledger, ...timed.header.hours]) equalMoney(row.upkeep, upkeep / 2);
  assert.deepEqual(timed.header.regions[''].ledger, timed.header.ledger);
  console.log('Jakarta midnight: upkeep', upkeep, 'running', run, 'USD million over 20 s, split across two days/hours');
  const serviceDay = await decodeSave(await readFile(join(dir, 'timetable-web.save')));
  const charged = await decodeSave(await readFile(join(dir, 'timetable-android.save')));
  assert.ok(after.load(serviceDay.track));
  const [{ lagged, runLag }, { dayKindOf }] = await Promise.all(
    ['timing.mjs', 'calendar.mjs'].map(m => import(pathToFileURL(join(dir, m)))));
  const row = rows(after.lines_info(), 22)[0];
  const price = Math.round(Math.sqrt(income.ID / income.US) * 100) / 100;
  after.set_line_run_factors(Float64Array.of(row[0], price));
  const lag = runLag([{ tph: row[6], lengthM: row[9], cars: row[21], oneWayS: row[11] / 2 - row[20] }]);
  const bands = [0, 0, 0, 1, 2], cost = Array.from({ length: 6 }, (_, k) => after.running_cost(k));
  const localShift = after.line_shift(row[0]);
  assert.equal(localShift, 12 * 3600);
  assert.equal(charged.header.hours.length, 12);
  for (const hour of charged.header.hours) {
    const expected = lagged((day, period) => cost[(day ? 3 : 0) + bands[period]], null,
      hour.hour * 3600 + localShift, lag, lag / 2, day => dayKindOf(day) ? 1 : 0);
    equalMoney(hour.running, expected);
  }
  console.log('12 hourly debits match Studio moneyTiming across Friday rush, night and Saturday opening');
  if (process.env.STUDIO_ROUNDTRIP_EXPORT_DIR) {
    for (const file of ['android-0.save', 'android-1.save', 'android-2.save', 'native-jak.save']) {
      await copyFile(join(dir, file), join(process.env.STUDIO_ROUNDTRIP_EXPORT_DIR, file));
    }
  }
} finally {
  before.free();
  after.free();
  await rm(dir, { recursive: true, force: true });
}
