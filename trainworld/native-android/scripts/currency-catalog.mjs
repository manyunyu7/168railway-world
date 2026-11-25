// SPDX-License-Identifier: AGPL-3.0-only
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { writeFileSync } from 'node:fs';

const [app, output] = process.argv.slice(2);
const { CURRENCY, CURRENCIES } = await import(pathToFileURL(resolve(app, 'src/game/currency.ts')));
writeFileSync(output, JSON.stringify(CURRENCIES.map(code => ({code, ...CURRENCY[code]}))) + '\n');
