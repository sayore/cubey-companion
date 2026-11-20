import { existsSync, readFileSync, writeFileSync, openSync, closeSync, fsyncSync, renameSync, truncateSync } from 'node:fs';
import { join } from 'node:path';

/** Transport-only UUID → native article number allocation, not a second memory index. */
export class ArticleIds {
    private readonly ids: Record<string, number>;
    private next: number;
    private poisoned = false;
    private readonly checkpointPath: string;
    private readonly journalPath: string;
    constructor(private readonly root: string) {
        this.checkpointPath = join(root, 'ids.json');
        this.journalPath = join(root, 'allocations.jsonl');
        this.ids = existsSync(this.checkpointPath) ? JSON.parse(readFileSync(this.checkpointPath, 'utf8')) : {};
        if (typeof this.ids !== 'object' || this.ids === null || Array.isArray(this.ids)
            || Object.keys(this.ids).some(key => !/^[a-f0-9-]{36}$/.test(key))) throw new Error('Invalid allocation checkpoint');
        if (existsSync(this.journalPath)) {
            const journal = readFileSync(this.journalPath);
            const complete = journal.lastIndexOf(10) + 1;
            // Native upsert only follows a fully written, fsynced allocation line.
            // A torn final line cannot have acknowledged native writes.
            for (const line of journal.subarray(0, complete).toString('utf8').split('\n').filter(Boolean)) {
                const entries: [string, number][] = JSON.parse(line);
                for (const [key, value] of entries) {
                    if (!/^[a-f0-9-]{36}$/.test(key) || !Number.isInteger(value) || value < 0 || value >= 999999
                        || (this.ids[key] !== undefined && this.ids[key] !== value)) throw new Error('Invalid allocation journal');
                    this.ids[key] = value;
                }
            }
            if (complete < journal.length) truncateSync(this.journalPath, complete);
        }
        const allocated = Object.values(this.ids);
        if (allocated.some(v => !Number.isInteger(v) || v < 0 || v >= 999999) || new Set(allocated).size !== allocated.length)
            throw new Error('Invalid article allocation checkpoint');
        this.next = allocated.reduce((max, id) => Math.max(max, id), -1) + 1;
    }
    get(key: string): number {
        const value = this.ids[key];
        if (value === undefined) throw new Error('Unallocated article');
        return value;
    }
    allocate(keys: string[]): boolean {
        if (this.poisoned) throw new Error('Allocation journal unavailable; restart required');
        const fresh = [...new Set(keys)].filter(key => this.ids[key] === undefined);
        if (this.next + fresh.length > 999999) return false;
        if (!fresh.length) return true;
        if (fresh.some(key => !/^[a-f0-9-]{36}$/.test(key))) throw new Error('Invalid article identity');
        const entries = fresh.map((key, offset) => [key, this.next + offset] as const);
        const created = !existsSync(this.journalPath);
        try {
            const fd = openSync(this.journalPath, 'a', 0o600);
            try { writeFileSync(fd, JSON.stringify(entries) + '\n'); fsyncSync(fd); }
            finally { closeSync(fd); }
            if (created) this.syncDirectory();
        } catch (error) {
            this.poisoned = true; // Never append behind a potentially torn write during this process.
            throw error;
        }
        for (const [key, id] of entries) this.ids[key] = id;
        this.next += entries.length;
        return true;
    }
    checkpoint(): void {
        if (this.poisoned) throw new Error('Cannot checkpoint a failed allocation journal');
        const temp = this.checkpointPath + '.tmp';
        writeFileSync(temp, JSON.stringify(this.ids), { mode: 0o600 });
        const fd = openSync(temp, 'r');
        try { fsyncSync(fd); } finally { closeSync(fd); }
        renameSync(temp, this.checkpointPath);
        this.syncDirectory();
        const journal = openSync(this.journalPath, 'w', 0o600);
        try { fsyncSync(journal); } finally { closeSync(journal); }
    }
    private syncDirectory(): void {
        const directory = openSync(this.root, 'r');
        try { fsyncSync(directory); } finally { closeSync(directory); }
    }
}
