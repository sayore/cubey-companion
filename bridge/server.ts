import { ArticleIds } from "./article-ids";
import { mkdirSync, openSync, closeSync, unlinkSync, existsSync, readFileSync, writeFileSync, renameSync } from "node:fs";
import { resolve, join } from "node:path";
import { pathToFileURL } from "node:url";

// Deliberately consumes the NERVE package entry point, never a copied search implementation.
process.umask(0o077);
const repo = resolve(process.env.QB2_NERVE_REPO ?? "/mnt/lfs/lfs/repos/nerve");
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
const token = process.env.QB2_NERVE_TOKEN ?? "";
if(token.length<24) {unlinkSync(lock);throw new Error("Set QB2_NERVE_TOKEN to at least 24 random characters");}
type Episode={id:string,scope:string,kind:string,keywords:string[],text:string,createdAt:string};
const ids = new ArticleIds(root);
const deletedFile = join(root, "deleted.json");
const deleted = new Set<string>(
    existsSync(deletedFile)
        ? (JSON.parse(readFileSync(deletedFile, "utf8")) as unknown[]).filter((x): x is string => typeof x === "string")
        : []
);
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
const server=Bun.serve({hostname:"127.0.0.1",port:Number(process.env.QB2_NERVE_PORT??17842),maxRequestBodySize:131072,
    async fetch(req) {
        if(req.headers.get("authorization")!=="Bearer "+token) return new Response("Unauthorized",{status:401});
        if(req.method!=="POST") return new Response("Method not allowed",{status:405});
        try {
            const data=await req.json();
            const path=new URL(req.url).pathname;
            if(path==="/memories") {
                if(!validEpisode(data)) return new Response("Invalid episode",{status:400});
                const e=data as Episode;
                if(!ids.allocate([e.id])) return new Response("Memory capacity reached",{status:507});
                const id=ids.get(e.id);
                Engine.upsertArticle(id,JSON.stringify(e),[...new Set(e.keywords.map(word=>e.scope+":"+word))]);
                return Response.json({ok:true});
            }
            if(path==="/knowledge") {
                if(!Array.isArray(data)||data.length<1||data.length>24||!data.every(e=>validEpisode(e)&&e.kind.startsWith("knowledge_"))) return new Response("Invalid knowledge batch",{status:400});
                if(!ids.allocate(data.map((e:Episode)=>e.id))) return new Response("Memory capacity reached",{status:507});
                const writes=(data as Episode[]).filter(e=>Engine.getArticleText(ids.get(e.id))!==JSON.stringify(e));
                if(writes.length) {
                    Engine.beginTransaction();
                    try {
                        for(const e of writes)
                            Engine.upsertArticle(ids.get(e.id),JSON.stringify(e),[...new Set(e.keywords.map(word=>e.scope+":"+word))]);
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
                    deleted.add(data.id);
                    saveDeleted();
                }
                return Response.json({ok:true});
            }
            if(path==="/search") {
                if(typeof data.scope!=="string"||!/^[a-f0-9]{64}$/.test(data.scope)||!Array.isArray(data.keywords)||data.keywords.length>1000||!data.keywords.every((w:unknown)=>typeof w==="string"&&w.length<=200)||!Number.isInteger(data.topK)||data.topK<1||data.topK>20) return new Response("Invalid search",{status:400});
                // Scope prefixes isolate keyword scores between worlds without inventing a native filter.
                const excluded:number[]=[];
                for(const delId of deleted) { const num=ids.get(delId); if(num!==undefined) excluded.push(num); }
                const matches=Engine.getTopMatchesUnique([...new Set(data.keywords.map((word:string)=>data.scope+":"+word))],excluded,Math.min(60,data.topK*3));
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
    try {ids.checkpoint();unlinkSync(lock);process.exit(0);}
    catch {console.error("Allocation metadata checkpoint failed; inspect the private memory directory before restarting.");process.exit(1);}
}
process.on("SIGTERM",shutdown);process.on("SIGINT",shutdown);
console.log(`QB2 NERVE bridge listening on loopback port ${server.port}`);
