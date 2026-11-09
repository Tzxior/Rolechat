import csv, json, os, re, subprocess, sys, threading, time, wave
from datetime import datetime
from pathlib import Path

import requests
from PySide6.QtCore import QThread, QTimer, Signal
from PySide6.QtWidgets import *

HERE = Path(__file__).resolve().parent
for d in ("voices", "input", "output", "reports", "logs"):
    (HERE / d).mkdir(exist_ok=True)
CFG = HERE / "config.json"
DEFAULT = {"api_url":"http://127.0.0.1:9880","launch_command":"","launch_workdir":"",
           "timeout":600,"retries":1,"skip_existing":True,"text_lang":"zh","media_type":"wav"}

def config_load():
    try: return {**DEFAULT, **json.loads(CFG.read_text(encoding="utf-8"))}
    except Exception: return DEFAULT.copy()

def safe(s):
    return (re.sub(r'[<>:"/\\|?*]+', "_", s.strip()) or "voice")[:64]

def read_lines(path):
    raw = Path(path).read_bytes()
    for enc in ("utf-8-sig","utf-8","gb18030"):
        try: data = raw.decode(enc); break
        except UnicodeDecodeError: pass
    else: data = raw.decode("utf-8", errors="replace")
    return [(i, s.strip()) for i,s in enumerate(data.splitlines(),1) if s.strip()]

def duration(path):
    try:
        with wave.open(str(path),"rb") as w: return round(w.getnframes()/w.getframerate(),3)
    except Exception: return ""

class VoiceDialog(QDialog):
    def __init__(self, parent=None, data=None):
        super().__init__(parent); self.setWindowTitle("声音角色"); self.setMinimumWidth(560)
        data = data or {}
        self.name=QLineEdit(data.get("name","")); self.audio=QLineEdit(data.get("ref_audio",""))
        self.prompt=QPlainTextEdit(data.get("prompt_text","")); self.lang=QComboBox()
        self.lang.addItems(["zh","ja","en","ko","yue"]); self.lang.setCurrentText(data.get("prompt_lang","zh"))
        browse=QPushButton("选择音频…"); browse.clicked.connect(self.pick)
        row=QHBoxLayout(); row.addWidget(self.audio,1); row.addWidget(browse)
        form=QFormLayout(); form.addRow("角色名称",self.name); form.addRow("参考音频",row)
        form.addRow("参考文本（需与录音一致）",self.prompt); form.addRow("参考语言",self.lang)
        buttons=QDialogButtonBox(QDialogButtonBox.Save|QDialogButtonBox.Cancel)
        buttons.accepted.connect(self.accept); buttons.rejected.connect(self.reject)
        lay=QVBoxLayout(self); lay.addLayout(form); lay.addWidget(buttons)
    def pick(self):
        p,_=QFileDialog.getOpenFileName(self,"选择参考音频","","音频 (*.wav *.mp3 *.flac *.ogg)")
        if p: self.audio.setText(p)
    def value(self):
        d={"name":self.name.text().strip(),"ref_audio":self.audio.text().strip(),
           "prompt_text":self.prompt.toPlainText().strip(),"prompt_lang":self.lang.currentText()}
        if not d["name"] or not Path(d["ref_audio"]).is_file() or not d["prompt_text"]:
            QMessageBox.warning(self,"资料不完整","请填写角色名、有效参考音频和参考文本。"); return None
        return d

class Worker(QThread):
    progress=Signal(int,int,str); row=Signal(dict); log=Signal(str); done=Signal(dict)
    def __init__(self,url,voice,lines,out,cfg):
        super().__init__(); self.url=url.rstrip("/"); self.voice=voice; self.lines=lines
        self.out=Path(out); self.cfg=cfg; self.stop_flag=threading.Event()
        self.pause_flag=threading.Event(); self.pause_flag.set()
    def stop(self): self.stop_flag.set(); self.pause_flag.set()
    def pause(self): self.pause_flag.clear()
    def resume(self): self.pause_flag.set()
    def run(self):
        self.out.mkdir(parents=True,exist_ok=True); rows=[]; total=len(self.lines)
        report=HERE/"reports"/f"{safe(self.voice['name'])}_{datetime.now():%Y%m%d_%H%M%S}.csv"
        fields=["line_number","text","char_count","generation_time_seconds","audio_duration_seconds",
                "tokens","retry_count","status","output_file","error"]
        for n,(line,text) in enumerate(self.lines,1):
            while not self.pause_flag.wait(.2):
                if self.stop_flag.is_set(): break
            if self.stop_flag.is_set(): break
            dest=self.out/f"{line:04d}.wav"
            r={"line_number":line,"text":text,"char_count":len(text),"generation_time_seconds":"",
               "audio_duration_seconds":"","tokens":"","retry_count":0,"status":"failed",
               "output_file":str(dest),"error":""}
            if dest.exists() and self.cfg["skip_existing"]:
                r["status"]="skipped_existing"
            else:
                start=time.perf_counter()
                for attempt in range(self.cfg["retries"]+1):
                    if self.stop_flag.is_set(): break
                    try:
                        payload={"text":text,"text_lang":self.cfg["text_lang"],
                          "ref_audio_path":self.voice["ref_audio"],"prompt_text":self.voice["prompt_text"],
                          "prompt_lang":self.voice["prompt_lang"],"media_type":self.cfg["media_type"],
                          "streaming_mode":False,"text_split_method":"cut5"}
                        res=requests.post(self.url+"/tts",json=payload,timeout=self.cfg["timeout"])
                        res.raise_for_status()
                        if not res.content: raise RuntimeError("API 返回空音频")
                        tmp=dest.with_suffix(".wav.part"); tmp.write_bytes(res.content); tmp.replace(dest)
                        r["status"]="success"; r["retry_count"]=attempt
                        r["generation_time_seconds"]=round(time.perf_counter()-start,3)
                        r["audio_duration_seconds"]=duration(dest); break
                    except Exception as e:
                        r["error"]=str(e); r["retry_count"]=attempt
                        if attempt < self.cfg["retries"]: self.log.emit(f"第{line}行失败，重试：{e}")
                if r["status"]=="failed": r["generation_time_seconds"]=round(time.perf_counter()-start,3)
            if self.stop_flag.is_set(): break
            rows.append(r); self.row.emit(r); self.progress.emit(n,total,f"{r['status']} · 原始第 {line} 行")
        try:
            with report.open("w",newline="",encoding="utf-8-sig") as f:
                w=csv.DictWriter(f,fieldnames=fields); w.writeheader(); w.writerows(rows)
        except Exception as e: self.log.emit(f"报告写入失败：{e}")
        self.done.emit({"report":str(report),"processed":len(rows),
          "success":sum(x["status"]=="success" for x in rows),
          "failed":sum(x["status"]=="failed" for x in rows),
          "skipped":sum(x["status"]=="skipped_existing" for x in rows),
          "stopped":self.stop_flag.is_set(),
          "audio":round(sum(float(x["audio_duration_seconds"] or 0) for x in rows),2)})

class Window(QMainWindow):
    def __init__(self):
        super().__init__(); self.setWindowTitle("Game Voice Studio · 本地角色语音制作")
        self.resize(1100,740); self.cfg=config_load(); self.process=None; self.logfile=None
        self.worker=None; self.txt=None; self.output=HERE/"output"; self.voices=[]
        tabs=QTabWidget(); tabs.addTab(self.dashboard(),"运行控制"); tabs.addTab(self.voice_page(),"声音角色")
        tabs.addTab(self.batch_page(),"批量生成"); tabs.addTab(self.settings_page(),"设置")
        self.setCentralWidget(tabs); self.reload_voices()
        self.timer=QTimer(self); self.timer.timeout.connect(self.check_service); self.timer.start(5000)
        self.check_service(); self.say("工具已启动。请先确认 API 地址与声音角色配置。")
    def say(self,s):
        if hasattr(self,"logs"): self.logs.appendPlainText(datetime.now().strftime("[%H:%M:%S] ")+s)
    def dashboard(self):
        w=QWidget(); l=QVBoxLayout(w); title=QLabel("本地推理服务"); title.setStyleSheet("font-size:22px;font-weight:bold")
        self.status=QLabel("正在检查…"); self.url_label=QLabel()
        row=QHBoxLayout()
        for label,fn in [("启动 GPT-SoVITS",self.start_service),("停止本工具启动的服务",self.stop_service),("立即检查",self.check_service)]:
            b=QPushButton(label); b.clicked.connect(fn); row.addWidget(b)
            if label.startswith("启动"): self.start_btn=b
            if label.startswith("停止"): self.stop_btn=b
        self.logs=QPlainTextEdit(); self.logs.setReadOnly(True)
        l.addWidget(title); l.addWidget(self.status); l.addWidget(self.url_label); l.addLayout(row)
        l.addWidget(QLabel("服务说明：推理服务常驻后台，模型加载一次；也可手动启动后由本工具连接。")); l.addWidget(QLabel("运行日志")); l.addWidget(self.logs,1)
        return w
    def voice_page(self):
        w=QWidget(); l=QVBoxLayout(w); self.vtable=QTableWidget(0,4)
        self.vtable.setHorizontalHeaderLabels(["角色","参考音频","参考文本","语言"])
        self.vtable.horizontalHeader().setSectionResizeMode(1,QHeaderView.Stretch)
        self.vtable.horizontalHeader().setSectionResizeMode(2,QHeaderView.Stretch)
        row=QHBoxLayout()
        for label,fn in [("新增",self.add_voice),("编辑",self.edit_voice),("删除",self.delete_voice)]:
            b=QPushButton(label); b.clicked.connect(fn); row.addWidget(b)
        row.addStretch(); l.addWidget(self.vtable); l.addLayout(row); return w
    def batch_page(self):
        w=QWidget(); l=QVBoxLayout(w); form=QFormLayout()
        self.vcombo=QComboBox(); self.txt_label=QLabel("尚未导入 TXT"); self.out_label=QLabel(str(self.output))
        tb=QPushButton("导入 TXT…"); tb.clicked.connect(self.pick_txt)
        ob=QPushButton("输出目录…"); ob.clicked.connect(self.pick_output)
        tr=QHBoxLayout(); tr.addWidget(self.txt_label,1); tr.addWidget(tb)
        orow=QHBoxLayout(); orow.addWidget(self.out_label,1); orow.addWidget(ob)
        form.addRow("声音角色",self.vcombo); form.addRow("台词文件",tr); form.addRow("输出目录",orow)
        self.bstatus=QLabel("等待任务"); self.bar=QProgressBar()
        self.table=QTableWidget(0,6); self.table.setHorizontalHeaderLabels(["行号","台词","状态","耗时(s)","音频(s)","文件"])
        self.table.horizontalHeader().setSectionResizeMode(1,QHeaderView.Stretch)
        self.table.horizontalHeader().setSectionResizeMode(5,QHeaderView.Stretch)
        row=QHBoxLayout()
        self.go=QPushButton("开始生成"); self.pause=QPushButton("暂停"); self.stop=QPushButton("停止")
        self.go.clicked.connect(self.start_batch); self.pause.clicked.connect(self.pause_batch); self.stop.clicked.connect(self.stop_batch)
        self.pause.setEnabled(False); self.stop.setEnabled(False)
        for b in (self.go,self.pause,self.stop): row.addWidget(b)
        row.addStretch()
        l.addLayout(form); l.addWidget(self.bstatus); l.addWidget(self.bar); l.addLayout(row); l.addWidget(self.table,1)
        return w
    def settings_page(self):
        w=QWidget(); l=QVBoxLayout(w); f=QFormLayout()
        self.api=QLineEdit(self.cfg["api_url"]); self.command=QLineEdit(self.cfg["launch_command"])
        self.workdir=QLineEdit(self.cfg["launch_workdir"]); self.timeout=QSpinBox(); self.timeout.setRange(30,3600); self.timeout.setValue(self.cfg["timeout"])
        self.retries=QSpinBox(); self.retries.setRange(0,10); self.retries.setValue(self.cfg["retries"])
        self.lang=QComboBox(); self.lang.addItems(["zh","ja","en","ko","yue"]); self.lang.setCurrentText(self.cfg["text_lang"])
        self.skip=QCheckBox("跳过已有 WAV（断点续跑）"); self.skip.setChecked(self.cfg["skip_existing"])
        f.addRow("API 根地址",self.api); f.addRow("启动命令（可留空）",self.command)
        f.addRow("启动工作目录",self.workdir); f.addRow("单句超时（秒）",self.timeout)
        f.addRow("失败重试次数",self.retries); f.addRow("生成语言",self.lang)
        l.addLayout(f); l.addWidget(self.skip); save=QPushButton("保存设置"); save.clicked.connect(self.save_settings); l.addWidget(save)
        help=QLabel("启动命令示例（请按本机版本修改）：python api_v2.py -a 127.0.0.1 -p 9880\n工作目录填写 api_v2.py 所在目录。整合包可以填写自己的 .bat 启动脚本。API 地址只填根地址，不要加 /tts。")
        help.setWordWrap(True); l.addWidget(help); l.addStretch(); return w
    def save_settings(self):
        self.cfg.update(api_url=self.api.text().strip().rstrip("/"),launch_command=self.command.text().strip(),
          launch_workdir=self.workdir.text().strip(),timeout=self.timeout.value(),retries=self.retries.value(),
          text_lang=self.lang.currentText(),skip_existing=self.skip.isChecked())
        CFG.write_text(json.dumps(self.cfg,ensure_ascii=False,indent=2),encoding="utf-8")
        self.say("设置已保存。"); self.check_service()
    def reload_voices(self):
        self.voices=[]
        for p in sorted((HERE/"voices").glob("*.json")):
            try: self.voices.append(json.loads(p.read_text(encoding="utf-8")))
            except Exception as e: self.say(f"读取角色失败 {p.name}: {e}")
        if hasattr(self,"vtable"):
            self.vtable.setRowCount(0); self.vcombo.clear()
            for v in self.voices:
                r=self.vtable.rowCount(); self.vtable.insertRow(r)
                for c,x in enumerate([v["name"],v["ref_audio"],v["prompt_text"],v["prompt_lang"]]):
                    self.vtable.setItem(r,c,QTableWidgetItem(str(x)))
                self.vcombo.addItem(v["name"])
    def add_voice(self):
        d=VoiceDialog(self)
        if d.exec()==QDialog.Accepted:
            v=d.value()
            if v: (HERE/"voices"/(safe(v["name"])+".json")).write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding="utf-8"); self.reload_voices()
    def current_voice(self):
        r=self.vtable.currentRow()
        return self.voices[r] if 0<=r<len(self.voices) else None
    def edit_voice(self):
        old=self.current_voice()
        if not old:return
        d=VoiceDialog(self,old)
        if d.exec()==QDialog.Accepted:
            v=d.value()
            if v:
                (HERE/"voices"/(safe(old["name"])+".json")).unlink(missing_ok=True)
                (HERE/"voices"/(safe(v["name"])+".json")).write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding="utf-8"); self.reload_voices()
    def delete_voice(self):
        v=self.current_voice()
        if v and QMessageBox.question(self,"确认",f"删除角色 {v['name']} 配置？不会删除音频。")==QMessageBox.Yes:
            (HERE/"voices"/(safe(v["name"])+".json")).unlink(missing_ok=True); self.reload_voices()
    def pick_txt(self):
        p,_=QFileDialog.getOpenFileName(self,"导入 TXT",str(HERE/"input"),"文本文件 (*.txt)")
        if p:self.txt=Path(p); self.txt_label.setText(p)
    def pick_output(self):
        p=QFileDialog.getExistingDirectory(self,"选择输出目录",str(self.output))
        if p:self.output=Path(p); self.out_label.setText(p)
    def service_ready(self):
        for suffix in ("/","/docs"):
            try:
                r=requests.get(self.cfg["api_url"].rstrip("/")+suffix,timeout=2)
                if r.status_code<500:return True
            except Exception:pass
        return False
    def check_service(self):
        ready=self.service_ready(); self.url_label.setText("API："+self.cfg["api_url"])
        self.status.setText("● HTTP 服务可连接" if ready else "○ 尚未检测到服务")
        self.status.setStyleSheet("font-size:16px;color:#16834a" if ready else "font-size:16px;color:#b36b20")
        if hasattr(self,"start_btn"): self.start_btn.setEnabled(not (self.process and self.process.poll() is None))
        if hasattr(self,"stop_btn"): self.stop_btn.setEnabled(bool(self.process and self.process.poll() is None))
    def start_service(self):
        if self.service_ready(): QMessageBox.information(self,"服务已运行","API 已可连接。"); return
        cmd=self.cfg["launch_command"]; wd=self.cfg["launch_workdir"]
        if not cmd or not Path(wd).is_dir():
            QMessageBox.warning(self,"尚未配置","请在设置页填写启动命令和有效工作目录，或手动启动 GPT-SoVITS。"); return
        log=HERE/"logs"/f"gpt_sovits_{datetime.now():%Y%m%d_%H%M%S}.log"
        try:
            self.logfile=log.open("a",encoding="utf-8",errors="replace")
            flags=getattr(subprocess,"CREATE_NEW_PROCESS_GROUP",0) if os.name=="nt" else 0
            self.process=subprocess.Popen(cmd,cwd=wd,shell=True,stdout=self.logfile,stderr=subprocess.STDOUT,
                                           stdin=subprocess.DEVNULL,creationflags=flags)
            self.say(f"已启动推理服务进程，日志：{log}"); QTimer.singleShot(2000,self.check_service)
        except Exception as e: QMessageBox.critical(self,"启动失败",str(e))
    def stop_service(self):
        if not self.process or self.process.poll() is not None:self.process=None; self.check_service(); return
        try:
            if os.name=="nt": subprocess.run(["taskkill","/PID",str(self.process.pid),"/T","/F"],capture_output=True,timeout=10)
            else:self.process.terminate()
            self.process.wait(timeout=10); self.say("已停止本工具启动的服务。")
        except Exception as e:self.say(f"停止服务时出错：{e}")
        self.process=None
        if self.logfile:self.logfile.close(); self.logfile=None
        self.check_service()
    def start_batch(self):
        if self.worker and self.worker.isRunning():return
        if not self.txt or not self.txt.is_file():QMessageBox.warning(self,"缺少文件","请先导入 TXT。"); return
        v=next((x for x in self.voices if x["name"]==self.vcombo.currentText()),None)
        if not v:QMessageBox.warning(self,"缺少角色","请先新增声音角色。"); return
        try: lines=read_lines(self.txt)
        except Exception as e:QMessageBox.critical(self,"读取失败",str(e)); return
        if not lines:QMessageBox.warning(self,"没有台词","TXT 中没有非空行。"); return
        if not self.service_ready():QMessageBox.warning(self,"服务未就绪","请先启动 GPT-SoVITS API。"); return
        self.table.setRowCount(0); self.bar.setValue(0)
        self.worker=Worker(self.cfg["api_url"],v,lines,self.output/safe(v["name"]),self.cfg.copy())
        self.worker.progress.connect(self.on_progress); self.worker.row.connect(self.on_row)
        self.worker.log.connect(self.say); self.worker.done.connect(self.on_done); self.worker.start()
        self.go.setEnabled(False); self.pause.setEnabled(True); self.stop.setEnabled(True); self.pause.setText("暂停")
    def on_progress(self,n,total,msg):self.bar.setValue(int(n*100/max(total,1))); self.bstatus.setText(f"{n}/{total} · {msg}")
    def on_row(self,r):
        i=self.table.rowCount(); self.table.insertRow(i)
        for c,x in enumerate([r["line_number"],r["text"],r["status"],r["generation_time_seconds"],r["audio_duration_seconds"],r["output_file"]]):
            self.table.setItem(i,c,QTableWidgetItem(str(x)))
        self.table.scrollToBottom()
    def pause_batch(self):
        if not self.worker:return
        if self.pause.text()=="暂停":self.worker.pause(); self.pause.setText("继续"); self.bstatus.setText("暂停中（当前请求结束后生效）")
        else:self.worker.resume(); self.pause.setText("暂停")
    def stop_batch(self):
        if self.worker and self.worker.isRunning():self.worker.stop(); self.bstatus.setText("正在停止，等待当前请求结束…"); self.stop.setEnabled(False)
    def on_done(self,r):
        self.go.setEnabled(True); self.pause.setEnabled(False); self.stop.setEnabled(False)
        self.bstatus.setText(f"处理 {r['processed']} 条：成功 {r['success']}，失败 {r['failed']}，跳过 {r['skipped']}；音频总长 {r['audio']} 秒")
        self.say("CSV 报告："+r["report"])
        QMessageBox.information(self,"批量任务结束",self.bstatus.text()+"\n\n报告："+r["report"])
    def closeEvent(self,event):
        if self.worker and self.worker.isRunning():
            if QMessageBox.question(self,"任务运行中","退出会停止批量任务，确定？")!=QMessageBox.Yes:event.ignore();return
            self.worker.stop(); self.worker.wait(2500)
        if self.process and self.process.poll() is None:
            if QMessageBox.question(self,"推理服务运行中","是否同时停止本工具启动的 GPT-SoVITS？")==QMessageBox.Yes:self.stop_service()
        event.accept()

if __name__=="__main__":
    app=QApplication(sys.argv); app.setStyle("Fusion"); win=Window(); win.show(); sys.exit(app.exec())
