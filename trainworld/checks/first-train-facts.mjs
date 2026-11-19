import assert from 'node:assert/strict';
import {firstTrainFacts} from '../app/src/game/firstTrain.ts';

const station = (num, built = false) => ({id: String(num), num, built});
const edge = (a, b, built = false) => ({a, b, built});
const world = (edges, stations, lines = [], lineStats = {}) => ({edges, save: {stations, lines}, lineStats});
const stations = [station(1), station(2)];
assert.equal(firstTrainFacts(world([edge(1, 3), edge(2, 4)], stations)).connectedStations, 1,
  'two isolated stations do not finish the connection step');
assert.equal(firstTrainFacts(world([edge(1, 3), edge(3, 2)], stations)).connectedStations, 2);
assert.equal(firstTrainFacts(world([edge(1, 2)], stations)).builtStations, 0);
assert.equal(firstTrainFacts(world([{...edge(1, 2), off: true}], stations)).connectedStations, 0);
const built = stations.map(s => ({...s, built: true}));
const line = {id: '0', num: 0, stops: ['1', '2']};
assert.equal(firstTrainFacts(world([edge(1, 2, true)], built, [line], {'0': {status: 'running'}})).connectedLine, line);
assert.equal(firstTrainFacts(world([edge(1, 2, true)], built, [line], {'0': {status: 'broken'}})).connectedLine, undefined);
assert.equal(firstTrainFacts(world([edge(1, 2, true)], built, [line], {'0': {status: 'running', bus: {}}})).line, false);
assert.equal(firstTrainFacts(world([edge(1, 2, true)], built, [{...line, stops: ['1', '1']}], {'0': {status: 'running'}})).connectedLine, undefined);
console.log('PASS 8 first-train topology cases');
