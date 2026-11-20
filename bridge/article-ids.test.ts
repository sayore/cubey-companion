import { test, expect } from 'bun:test';
import { mkdtempSync, rmSync, appendFileSync, readFileSync, writeFileSync, statSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ArticleIds } from './article-ids';
const first = 'a'.repeat(8)+'-aaaa-aaaa-aaaa-'+'a'.repeat(12);
const second = first.replaceAll('a','b');
function isolated(run: (root: string) => void) {
    const root = mkdtempSync(join(tmpdir(), 'qb2-ids-'));
    try { run(root); } finally { rmSync(root, {recursive: true}); }
}
test('fsynced allocations recover without a graceful checkpoint; retries stay idempotent', () => isolated(root => {
    const ids = new ArticleIds(root);
    expect(ids.allocate([first,second,first])).toBe(true);
    expect(ids.get(first)).toBe(0);
    const recovered = new ArticleIds(root);
    expect(recovered.get(second)).toBe(1);
    expect(recovered.allocate([first,second])).toBe(true);
    expect(readFileSync(join(root,'allocations.jsonl'),'utf8').split('\n').filter(Boolean).length).toBe(1);
}));
test('checkpoint compacts the journal and remains compatible with ids.json readers', () => isolated(root => {
    const ids = new ArticleIds(root);
    ids.allocate([first]); ids.checkpoint();
    expect(JSON.parse(readFileSync(join(root,'ids.json'),'utf8'))[first]).toBe(0);
    expect(statSync(join(root,'allocations.jsonl')).size).toBe(0);
    const recovered = new ArticleIds(root);
    recovered.allocate([second]);
    expect(recovered.get(second)).toBe(1);
}));
test('only a torn final allocation line is discarded, not confirmed entries', () => isolated(root => {
    new ArticleIds(root).allocate([first]);
    appendFileSync(join(root,'allocations.jsonl'),'[["incomplete');
    const recovered = new ArticleIds(root);
    expect(recovered.get(first)).toBe(0);
    expect(() => recovered.get(second)).toThrow();
    expect(readFileSync(join(root,'allocations.jsonl'),'utf8').endsWith('\n')).toBe(true);
}));
test('conflicting complete journal entries fail closed', () => isolated(root => {
    new ArticleIds(root).allocate([first]);
    appendFileSync(join(root,'allocations.jsonl'),JSON.stringify([[first,5]])+'\n');
    expect(() => new ArticleIds(root)).toThrow();
}));
test('native article numbers cannot be shared by different UUIDs', () => isolated(root => {
    writeFileSync(join(root,'ids.json'),JSON.stringify({[first]:0,[second]:0}));
    expect(() => new ArticleIds(root)).toThrow();
}));

test('failed appends prevent further allocation until restart', () => isolated(root => {
    const ids = new ArticleIds(root);
    mkdirSync(join(root,'allocations.jsonl'));
    expect(() => ids.allocate([first])).toThrow();
    rmSync(join(root,'allocations.jsonl'),{recursive:true});
    expect(() => ids.allocate([first])).toThrow();
    expect(() => ids.checkpoint()).toThrow();
    expect(new ArticleIds(root).allocate([first])).toBe(true);
}));
test('invalid checkpoint shape cannot silently discard UUID allocations', () => isolated(root => {
    writeFileSync(join(root,'ids.json'),'[]');
    expect(() => new ArticleIds(root)).toThrow();
}));
