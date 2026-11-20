import { ArticleIds } from "./article-ids";
import { mkdirSync, openSync, closeSync, unlinkSync, existsSync, readFileSync, writeFileSync, renameSync } from "node:fs";
import { resolve, join } from "node:path";
import { pathToFileURL } from "node:url";

// Deliberately consumes the NERVE package entry point, never a copied search implementation.
process.umask(0o077);
const repo = resolve(process.env.QB2_NERVE_REPO ?? join(import.meta.dir, "../../nerve"));
const spatialRepo = resolve(process.env.QB2_SPATIAL_REPO ?? join(import.meta.dir, "../../spatial"));
const root = resolve(process.env.QB2_MEMORY_DIR ?? join(import.meta.dir, "data"));
mkdirSync(join(root, "db"), {recursive: true});
const lock=join(root,"bridge.lock");
try { const fd=openSync(lock,"wx",0o600);closeSync(fd); }
catch { throw new Error("Memory directory is locked; stop the other bridge or remove a stale bridge.lock"); }
process.chdir(root);
type NerveEngine={
    setMode(mode:number):void;
    beginTransaction():void;
    commitTransaction():void;
    upsertArticle(id:number,text:string,keywords:string[]):void;
    getTopMatchesUnique(keywords:string[],exclude:number[],topK:number):{id:number,score:number}[];
    getArticleText(id:number):string;
};
function isEngine(value:unknown):value is NerveEngine {
    return typeof value==="object"&&value!==null&&["setMode","beginTransaction","commitTransaction","upsertArticle","getTopMatchesUnique","getArticleText"].every(name=>name in value&&typeof Reflect.get(value,name)==="function");
}
let Engine:NerveEngine;
try {
    const pkg=await import(pathToFileURL(join(repo,"packages/nerve-bun")).href);
    if(!isEngine(pkg.Engine)||pkg.DbMode?.StrictAcid!==2)throw new Error("Unsupported NERVE package");
    Engine=pkg.Engine;
}
catch (error) { unlinkSync(lock);if(process.env.QB2_NERVE_DEBUG==="1") console.error(error);throw new Error("NERVE package/native library unavailable or recovery failed. Build nerve-core and check its data."); }
Engine.setMode(2); // Validated native StrictAcid mode.
type SpatialIndex = {
    setMode(mode: number): void;
    upsert(id: number, x: number, y: number): void;
    remove(id: number): void;
    queryRadius(x: number, y: number, radius: number): Uint32Array;
    flushAll(): void;
};
let spatial: SpatialIndex | null = null;
const SPATIAL_SPAN = 4096;
const wrapCoord = (v: number) => ((v % SPATIAL_SPAN) + SPATIAL_SPAN) % SPATIAL_SPAN;
try {
    const libPath = join(spatialRepo, "crates/spatial-core/target/release/libspatial_core.so");
    if (existsSync(libPath)) {
        const spkg = await import(pathToFileURL(join(spatialRepo, "packages/spatial-bun/src/index.ts")).href);
        const grid = new spkg.SpatialGrid2D(libPath, { dbDir: join(root, "spatial"), maxEntities: 524288, gridDim: 512, cellSize: 8.0 }) as SpatialIndex;
        grid.setMode(2); // StrictAcid
        spatial = grid;
    }
} catch (error) {
    if (process.env.QB2_NERVE_DEBUG === "1") console.error("Spatial index unavailable:", error);
}
const token = process.env.QB2_NERVE_TOKEN ?? "";
if(token.length<24) {unlinkSync(lock);throw new Error("Set QB2_NERVE_TOKEN to at least 24 random characters");}
type Episode={id:string,scope:string,kind:string,keywords:string[],text:string,createdAt:string,x?:number|null,y?:number|null,z?:number|null};
const ids = new ArticleIds(root);
const deletedFile = join(root, "deleted.json");
const deleted = new Set<string>(
    existsSync(deletedFile)
        ? (JSON.parse(readFileSync(deletedFile, "utf8")) as unknown[]).filter((x): x is string => typeof x === "string")
        : []
);
const excludedArticleIds: number[] = [];
for (const delId of deleted) {
    const num = ids.get(delId);
    if (num !== undefined) excludedArticleIds.push(num);
}
function saveDeleted() {
    const tmp = deletedFile + ".tmp";
    writeFileSync(tmp, JSON.stringify([...deleted]), { mode: 0o600 });
    renameSync(tmp, deletedFile);
}
function validEpisode(e:Episode):boolean {
    return e!==null&&typeof e==="object"&&typeof e.id==="string"&&/^[a-f0-9-]{36}$/.test(e.id)&&typeof e.scope==="string"&&/^[a-f0-9]{64}$/.test(e.scope)
      &&typeof e.text==="string"&&e.text.length<=2000&&typeof e.kind==="string"&&e.kind.length<=40
      &&typeof e.createdAt==="string"&&Number.isFinite(Date.parse(e.createdAt))&&Array.isArray(e.keywords)&&e.keywords.length>0&&e.keywords.length<=1000
      &&e.keywords.every(w=>typeof w==="string"&&w.length>0&&w.length<=200);
}
const whisperBin = process.env.QB2_WHISPER_BIN || join(process.env.HOME || "", ".local/bin/whisper-cli");
const whisperModel = process.env.QB2_WHISPER_MODEL || join(process.env.HOME || "", ".local/share/qb2-whisper/models/ggml-base-q5_1.bin");
const server=Bun.serve({hostname:"127.0.0.1",port:Number(process.env.QB2_NERVE_PORT??17842),maxRequestBodySize:2097152,
    async fetch(req) {
        const url = new URL(req.url);
        const path = url.pathname;
        if(req.method!=="POST") return new Response("Method not allowed",{status:405});
        // Local loopback Push-to-Talk speech-to-text via whisper.cpp (CPU-only, 0 MB VRAM!)
        if(path==="/transcribe") {
            const auth = req.headers.get("authorization");
            if(auth && auth !== "Bearer " + token) return new Response("Unauthorized",{status:401});
            if(!existsSync(whisperBin) || !existsSync(whisperModel)) {
                return Response.json({error:"whisper.cpp or ggml-base-q5_1.bin not installed"}, {status:503});
            }
            const langRaw = url.searchParams.get("lang") || "de";
            const lang = /^[a-z]{2,4}$/.test(langRaw) ? langRaw : "auto";
            const wavBytes = new Uint8Array(await req.arrayBuffer());
            if(wavBytes.length < 100 || wavBytes.length > 2000000) return new Response("Invalid audio",{status:400});
            const tmpWav = join(root, `ptt-${Date.now()}-${Math.random().toString(36).slice(2,8)}.wav`);
            try {
                writeFileSync(tmpWav, wavBytes, { mode: 0o600 });
                const whisperPrompt = "Minecraft, Craftoria, Cubey, Create, Crushing Wheel, Crusher, Encased Fan, Depot, Brass Funnel, Cogwheel, AE2, Applied Energistics, Mekanism, Farmer's Delight, Waystones, Ars Nouveau, ComputerCraft, Smite, Redstone.";
                const proc = Bun.spawn([whisperBin, "-m", whisperModel, "-l", lang, "--prompt", whisperPrompt, "-t", "4", "-nt", "-np", "-f", tmpWav], {
                    stdout: "pipe",
                    stderr: "pipe"
                });
                const stdout = await new Response(proc.stdout).text();
                await proc.exited;
                const clean = stdout
                    .replace(/\[BLANK_AUDIO\]/gi, "")
                    .replace(/\([^)]*\)/g, "")
                    .replace(/\s+/g, " ")
                    .trim();
                return Response.json({text: clean});
            } catch {
                return Response.json({error:"Transcription failed"}, {status:500});
            } finally {
                try { if(existsSync(tmpWav)) unlinkSync(tmpWav); } catch {}
            }
        }
        if(req.headers.get("authorization")!=="Bearer "+token) return new Response("Unauthorized",{status:401});
        try {
            const data=await req.json();
            if(path==="/memories") {
                if(!validEpisode(data)) return new Response("Invalid episode",{status:400});
                const e=data as Episode;
                if(!ids.allocate([e.id])) return new Response("Memory capacity reached",{status:507});
                const id=ids.get(e.id);
                Engine.upsertArticle(id,JSON.stringify(e),[...new Set(e.keywords.map(word=>e.scope+":"+word))]);
                if(spatial && id < 524288 && typeof e.x==="number" && Number.isFinite(e.x) && typeof e.y==="number" && Number.isFinite(e.y) && typeof e.z==="number" && Number.isFinite(e.z)) {
                    spatial.upsert(id, wrapCoord(e.x), wrapCoord(e.z));
                    spatial.flushAll();
                }
                return Response.json({ok:true});
            }
            if(path==="/knowledge") {
                if(!Array.isArray(data)||data.length<1||data.length>128||!data.every(e=>validEpisode(e)&&e.kind.startsWith("knowledge_"))) return new Response("Invalid knowledge batch",{status:400});
                if(!ids.allocate(data.map((e:Episode)=>e.id))) return new Response("Memory capacity reached",{status:507});
                const writes:( {id:number, text:string, keywords:string[]} )[]=[];
                for(const e of data as Episode[]) {
                    const numId=ids.get(e.id);
                    const serialized=JSON.stringify(e);
                    if(Engine.getArticleText(numId)!==serialized)
                        writes.push({id:numId,text:serialized,keywords:[...new Set(e.keywords.map(word=>e.scope+":"+word))]});
                }
                if(writes.length) {
                    Engine.beginTransaction();
                    try {
                        for(const w of writes)
                            Engine.upsertArticle(w.id,w.text,w.keywords);
                    } finally {Engine.commitTransaction();}
                }
                return Response.json({ok:true,count:data.length});
            }
            if(path==="/delete") {
                if(typeof data.scope!=="string"||!/^[a-f0-9]{64}$/.test(data.scope)||typeof data.id!=="string"||!/^[a-f0-9-]{36}$/.test(data.id)) return new Response("Invalid delete",{status:400});
                const articleId=ids.get(data.id);
                if(articleId!==undefined) {
                    const text=Engine.getArticleText(articleId);
                    if(text) {
                        const existing=JSON.parse(text) as Episode;
                        if(existing.scope!==data.scope) return new Response("Forbidden",{status:403});
                    }
                    if(!deleted.has(data.id)) {
                        deleted.add(data.id);
                        excludedArticleIds.push(articleId);
                        if(spatial && articleId < 524288) {
                            spatial.remove(articleId);
                            spatial.flushAll();
                        }
                        saveDeleted();
                    }
                }
                return Response.json({ok:true});
            }
            if(path==="/nearby") {
                if(typeof data.scope!=="string"||!/^[a-f0-9]{64}$/.test(data.scope)||typeof data.x!=="number"||!Number.isFinite(data.x)||typeof data.y!=="number"||!Number.isFinite(data.y)||typeof data.z!=="number"||!Number.isFinite(data.z)||typeof data.radius!=="number"||!Number.isFinite(data.radius)||data.radius<=0||data.radius>64||!Number.isInteger(data.topK)||data.topK<1||data.topK>20) return new Response("Invalid nearby query",{status:400});
                if(!spatial) return Response.json([]);
                const hits=spatial.queryRadius(wrapCoord(data.x),wrapCoord(data.z),data.radius);
                const found:{episode:Episode,distance:number}[]=[];
                for(let i=0;i<hits.length;i++) {
                    const text=Engine.getArticleText(hits[i]);
                    if(!text) continue;
                    const e=JSON.parse(text) as Episode;
                    if(e.scope!==data.scope||deleted.has(e.id)||typeof e.x!=="number"||typeof e.y!=="number"||typeof e.z!=="number") continue;
                    const dist=Math.hypot(e.x-data.x,e.y-data.y,e.z-data.z);
                    if(dist<=data.radius) found.push({episode:e,distance:Math.round(dist*10)/10});
                }
                found.sort((a,b)=>a.distance-b.distance);
                return Response.json(found.slice(0,data.topK));
            }
            if(path==="/search") {
                if(typeof data.scope!=="string"||!/^[a-f0-9]{64}$/.test(data.scope)||!Array.isArray(data.keywords)||data.keywords.length>1000||!data.keywords.every((w:unknown)=>typeof w==="string"&&w.length<=200)||!Number.isInteger(data.topK)||data.topK<1||data.topK>20) return new Response("Invalid search",{status:400});
                // Scope prefixes isolate keyword scores between worlds without inventing a native filter.
                const matches=Engine.getTopMatchesUnique([...new Set(data.keywords.map((word:string)=>data.scope+":"+word))],excludedArticleIds,Math.min(60,data.topK*3));
                const ranked=matches.map(match=>({episode:JSON.parse(Engine.getArticleText(match.id)) as Episode,score:match.score}));
                const relevance=(m:typeof ranked[number])=>m.score*(0.9+0.1/(1+Math.max(0,Date.now()-Date.parse(m.episode.createdAt))/604800000));
                ranked.sort((a,b)=>relevance(b)-relevance(a));
                const result:Episode[]=[];
                for(const match of ranked) {
                    const e=match.episode;
                    if(e.scope===data.scope&&!deleted.has(e.id)&&!result.some(x=>x.text===e.text)) result.push(e);
                    if(result.length>=data.topK) break;
                }
                return Response.json(result);
            }
            return new Response("Not found",{status:404});
        } catch { return new Response("NERVE operation failed",{status:503}); }
    }
});
function shutdown(){
    server.stop(true);
    try {spatial?.flushAll();ids.checkpoint();unlinkSync(lock);process.exit(0);}
    catch {console.error("Allocation metadata checkpoint failed; inspect the private memory directory before restarting.");process.exit(1);}
}
process.on("SIGTERM",shutdown);process.on("SIGINT",shutdown);
console.log(`QB2 NERVE bridge listening on loopback port ${server.port}`);
