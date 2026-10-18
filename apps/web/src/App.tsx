import { useEffect, useRef, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { BrowserRouter, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { ArrowLeft, ArrowRight, Check, Download, FileArchive, Link2, LockKeyhole, Moon, Plus, Sun, X } from "lucide-react";
import { createRecord, downloadBlob, embedId, errorMessage, extractId, generateCarriers, getRecord, restoreFromLocal, restoreFromRemote, verifyUrls, type GeneratedCarrier, type ResourceRecord } from "./lib/workflow";

const ease = [0.22, 1, 0.36, 1] as const;
type Busy = false | string;

function ThemeToggle() {
  const [dark, setDark] = useState(() => localStorage.theme === "dark");
  useEffect(() => { document.documentElement.classList.toggle("dark", dark); }, [dark]);
  return <button className="theme-toggle" aria-label="切换夜间模式" onClick={() => setDark((value) => { localStorage.theme = String(!value); return !value; })}>{dark ? <Sun /> : <Moon />}</button>;
}
function Back() { const navigate = useNavigate(); return <button className="back" onClick={() => navigate("/")}><ArrowLeft />返回</button>; }
function Drop({ onFiles, title, hint, accept, multiple, disabled }: { onFiles: (files: File[]) => void; title: string; hint: string; accept?: string; multiple?: boolean; disabled?: boolean }) {
  const input = useRef<HTMLInputElement>(null);
  return <button className="drop" disabled={disabled} onClick={() => input.current?.click()}><input ref={input} hidden type="file" accept={accept} multiple={multiple} onChange={(event) => { onFiles([...(event.target.files || [])]); event.currentTarget.value = ""; }} /><i><Plus /></i><strong>{title}</strong><small>{hint}</small></button>;
}
function Heading({ title, text }: { title: string; text: string }) { return <div className="heading"><h2>{title}</h2><p>{text}</p></div>; }
function Page({ children, tint }: { children: React.ReactNode; tint: "blue" | "green" }) { return <main className={`page ${tint}`}><ThemeToggle />{children}</main>; }
function ErrorBox({ text }: { text: string }) { return <div className="error-box" role="alert">{text}</div>; }
function FileRow({ file, remove }: { file: File; remove: () => void }) { return <div className="file-row"><i><FileArchive /></i><span><strong>{file.name}</strong><small>{(file.size / 1048576).toFixed(2)} MB · {file.type || "未知类型"}</small></span><button aria-label="移除文件" onClick={remove}><X /></button></div>; }

function Home() {
  const navigate = useNavigate();
  return <motion.main className="home" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ x: "-35vw", opacity: 0 }} transition={{ duration: .58, ease }}><ThemeToggle /><div className="home-stage"><div className="home-copy"><span>PIXEL VAULT</span><h1>把重要的内容，<br />藏进一张普通图片。</h1><p>文件在浏览器中完成加密、分片、嵌入和恢复，服务器只保存受保护的密钥与第三方图片链接。</p></div><div className="balls"><button className="ball embed" onClick={() => navigate("/embed")}>嵌入</button><button className="ball extract" onClick={() => navigate("/extract")}>提取</button></div><div className="home-note"><LockKeyhole />AES-256-GCM · SHA-256 · TrustMark 4 字节 ID</div></div></motion.main>;
}

const stepNames = ["文件", "承载图片", "链接", "水印图片", "完成"];
function Embed() {
  const [step, setStep] = useState(0), [file, setFile] = useState<File>(), [carrierFiles, setCarrierFiles] = useState<File[]>([]);
  const [generated, setGenerated] = useState<{ encrypted: Awaited<ReturnType<typeof generateCarriers>>["encrypted"]; carriers: GeneratedCarrier[] }>();
  const [urls, setUrls] = useState<string[]>([]), [resourceId, setResourceId] = useState(""), [cover, setCover] = useState<File>(), [result, setResult] = useState<Blob>();
  const [busy, setBusy] = useState<Busy>(false), [error, setError] = useState("");
  const run = async (label: string, action: () => Promise<void>) => { setBusy(label); setError(""); try { await action(); } catch (reason) { setError(errorMessage(reason)); } finally { setBusy(false); } };
  const makeCarriers = () => run("正在加密并生成承载图…", async () => { if (!file) throw new Error("请先选择要隐藏的文件"); const value = await generateCarriers(file, carrierFiles); setGenerated(value); setUrls(value.carriers.map(() => "")); });
  const register = () => run("正在下载远程图片并校验…", async () => { if (!generated) throw new Error("请先生成承载图"); await verifyUrls(generated.carriers, urls); setResourceId(await createRecord(generated.encrypted, generated.carriers, urls)); setStep(3); });
  const makeFinal = () => run("正在加载模型并嵌入 4 字节 ID…", async () => { if (!cover || !resourceId) throw new Error("缺少水印图片或资源 ID"); setResult(await embedId(cover, resourceId)); setStep(4); });
  const reset = () => { setStep(0); setFile(undefined); setCarrierFiles([]); setGenerated(undefined); setUrls([]); setResourceId(""); setCover(undefined); setResult(undefined); setError(""); };
  const canNext = step === 0 ? !!file : step === 1 ? !!generated : false;
  return <Page tint="blue"><motion.section className="flow-card" initial={{ x: "55vw", opacity: 0 }} animate={{ x: 0, opacity: 1 }} exit={{ x: "-35vw", opacity: 0 }} transition={{ duration: .64, ease }}><div className="card-top"><Back /><h1>嵌入</h1><span /></div><div className="steps">{stepNames.map((name, index) => <button key={name} className={`${step === index ? "now" : ""} ${step > index ? "done" : ""}`} onClick={() => index < step && setStep(index)}><i>{step > index ? <Check /> : index + 1}</i><span>{name}</span>{index < 4 && <em />}</button>)}</div><div className="card-body"><AnimatePresence mode="wait" initial={false}><motion.div className="step-panel" key={step} initial={{ x: 35, opacity: 0 }} animate={{ x: 0, opacity: 1 }} exit={{ x: -35, opacity: 0 }} transition={{ duration: .28, ease }}>
    {step === 0 && <><Heading title="选择要隐藏的文件" text="文件不会上传到索引服务器；浏览器按 20 MB 分片并使用 AES-256-GCM 加密。" />{file ? <FileRow file={file} remove={() => { setFile(undefined); setGenerated(undefined); }} /> : <Drop title="选择文件" hint="最大 500 MB" onFiles={(files) => { setFile(files[0]); setGenerated(undefined); }} />}</>}
    {step === 1 && <><Heading title="生成加密承载图" text="请选择 PNG；可以用 1 张图承载所有分片，或为每个分片分别选择。" /><Drop accept="image/png,.png" multiple title={carrierFiles.length ? `已选择 ${carrierFiles.length} 张 PNG` : "选择承载 PNG"} hint="JPEG/WEBP 不能附加无损文件尾数据" onFiles={(files) => { setCarrierFiles(files); setGenerated(undefined); }} disabled={!!busy} /><button className="primary wide action-gap" disabled={!carrierFiles.length || !!busy} onClick={makeCarriers}>{busy || "加密并生成"}</button>{generated && <div className="output-list">{generated.carriers.map((carrier) => <button key={carrier.name} className="secondary" onClick={() => downloadBlob(carrier.blob, carrier.name)}><Download />{carrier.name} · {(carrier.blob.size / 1048576).toFixed(2)} MB</button>)}</div>}</>}
    {step === 2 && generated && <><Heading title="填写公开图片直链" text="必须是生成承载图上传后的原图直链。浏览器会重新下载并核对整图 SHA-256。" /><div className="url-list">{urls.map((url, index) => <label className="url" key={index}><Link2 /><input aria-label={`分片 ${index + 1} 链接`} value={url} placeholder={`第 ${index + 1} 张承载图的 HTTPS 直链`} onChange={(event) => setUrls((values) => values.map((value, at) => at === index ? event.target.value : value))} /></label>)}</div><button className="primary wide action-gap" disabled={!!busy || urls.some((url) => !url)} onClick={register}>{busy || "验证并登记"}</button></>}
    {step === 3 && <><Heading title="把 4 字节 ID 嵌入图片" text={`资源 ID：${resourceId}。首次运行需要下载约 65 MB 官方 TrustMark 模型。`} />{cover ? <FileRow file={cover} remove={() => { setCover(undefined); setResult(undefined); }} /> : <Drop accept="image/*" title="选择最终水印图片" hint="建议使用清晰、尺寸不低于 256×256 的图片" onFiles={(files) => setCover(files[0])} disabled={!!busy} />}<button className="primary wide action-gap" disabled={!cover || !!busy} onClick={makeFinal}>{busy || "嵌入 ID"}</button></>}
    {step === 4 && result && <><Heading title="处理完成" text="最终 PNG 只嵌入 4 字节资源 ID；隐藏文件位于你上传到第三方图床的承载图中。" /><div className="result"><i className="result-check"><Check /></i><code>{resourceId}</code><button className="primary wide" onClick={() => downloadBlob(result, "trustmark-result.png")}><Download />下载最终图片</button></div></>}
    {error && <ErrorBox text={error} />}
  </motion.div></AnimatePresence></div><div className="card-actions"><button className="secondary" disabled={step === 0 || !!busy} onClick={() => { setError(""); setStep((value) => value - 1); }}><ArrowLeft />上一步</button>{step < 2 ? <button className="primary" disabled={!canNext || !!busy} onClick={() => setStep((value) => value + 1)}>下一步<ArrowRight /></button> : step === 4 ? <button className="primary" onClick={reset}>重新开始</button> : <span />}</div></motion.section></Page>;
}

function Extract() {
  const [watermark, setWatermark] = useState<File>(), [record, setRecord] = useState<ResourceRecord>(), [id, setId] = useState(""), [local, setLocal] = useState<File[]>([]);
  const [restored, setRestored] = useState<{ file: Blob; name: string }>(), [busy, setBusy] = useState<Busy>(false), [error, setError] = useState("");
  const run = async (label: string, action: () => Promise<void>) => { setBusy(label); setError(""); setRestored(undefined); try { await action(); } catch (reason) { setError(errorMessage(reason)); } finally { setBusy(false); } };
  const scan = (selected: File) => run("正在提取 4 字节 ID 并查询服务器…", async () => { setWatermark(selected); const found = await extractId(selected); setId(found); setRecord(await getRecord(found)); });
  const remote = () => run("正在下载、校验并解密所有分片…", async () => { if (!record) throw new Error("尚未取得资源记录"); setRestored(await restoreFromRemote(record)); });
  const localRestore = (files: File[]) => run("正在校验并解密本地分片…", async () => { if (!record) throw new Error("尚未取得资源记录"); setLocal(files); setRestored(await restoreFromLocal(record, files)); });
  const reset = () => { setWatermark(undefined); setRecord(undefined); setId(""); setLocal([]); setRestored(undefined); setError(""); };
  return <Page tint="green"><motion.section className="flow-card extract-card" initial={{ x: "105vw", opacity: 0 }} animate={{ x: 0, opacity: 1 }} transition={{ duration: .72, ease }}><div className="card-top"><Back /><h1>提取</h1><span /></div><div className="card-body extract-body">{!record ? <><Heading title="选择含 TrustMark 的图片" text="浏览器将提取 4 字节 ID，再从索引服务器取得加密分片信息。" />{watermark ? <FileRow file={watermark} remove={reset} /> : <Drop accept="image/*" title="选择水印图片" hint="首次运行需要下载官方 TrustMark 模型" onFiles={(files) => files[0] && void scan(files[0])} disabled={!!busy} />}{busy && <p className="busy-text">{busy}</p>}{error && <ErrorBox text={error} />}</> : <div className="recovery"><Heading title="已找到资源" text={`ID ${id} · 共 ${record.partCount} 个加密分片`} /><button className="primary wide" disabled={!!busy} onClick={remote}><Download />从公开链接自动恢复</button><div className="or">或</div><Drop accept="image/png,.png" multiple title={local.length ? `已选择 ${local.length} 张承载图` : "选择已下载的承载图"} hint="图床不支持 CORS 时使用此方式" onFiles={(files) => void localRestore(files)} disabled={!!busy} />{busy && <p className="busy-text">{busy}</p>}{error && <ErrorBox text={error} />}{restored && <div className="found"><FileArchive /><span><strong>{restored.name}</strong><small>{(restored.file.size / 1048576).toFixed(2)} MB · 完整性验证通过</small></span><button aria-label="下载恢复文件" onClick={() => downloadBlob(restored.file, restored.name)}><Download /></button></div>}<button className="example" onClick={reset}>选择其他图片</button></div>}</div></motion.section></Page>;
}

function AppRoutes() { const location = useLocation(); return <AnimatePresence mode="sync"><Routes location={location} key={location.pathname}><Route path="/" element={<Home />} /><Route path="/embed" element={<Embed />} /><Route path="/extract" element={<Extract />} /><Route path="*" element={<Home />} /></Routes></AnimatePresence>; }
export default function App() { return <BrowserRouter><AppRoutes /></BrowserRouter>; }
