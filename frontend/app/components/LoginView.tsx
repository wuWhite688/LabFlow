import type { FormEvent } from "react";
import type { ToastState } from "../lib/types";
import { Brand, Toast } from "./ui";

type LoginState = { username: string; password: string };

export function LoginView({ login, busy, toast, onLoginChange, onSubmit, onDemo }: {
  login: LoginState;
  busy: boolean;
  toast: ToastState;
  onLoginChange: (next: LoginState) => void;
  onSubmit: (event: FormEvent) => void;
  onDemo: (username: string, password: string) => void;
}) {
  return <main className="login-shell">
    <section className="login-story">
      <Brand light />
      <div className="story-copy">
        <span className="eyebrow">LAB OPERATIONS GRID / 2026</span>
        <h1>设备预约与维护，<br />一张工作台搞定。</h1>
        <p>用清晰的排期、设备状态和维修进度，让实验室每天都按计划运转。</p>
      </div>
      <div className="story-metrics">
        <div><strong>01 / 排期</strong><span>预约冲突实时拦截</span></div>
        <div><strong>02 / 设备</strong><span>运行状态一眼识别</span></div>
        <div><strong>03 / 维修</strong><span>故障进度全程追踪</span></div>
      </div>
    </section>
    <section className="login-panel">
      <Brand mobile />
      <form className="login-card" onSubmit={onSubmit}>
        <span className="eyebrow">SECURE ACCESS / 安全访问</span>
        <h2>进入设备运行台</h2>
        <p>使用实验室账号进入对应角色空间</p>
        <label>账号<input value={login.username} onChange={(e) => onLoginChange({ ...login, username: e.target.value })} autoComplete="username" required /></label>
        <label>密码<input type="password" value={login.password} onChange={(e) => onLoginChange({ ...login, password: e.target.value })} autoComplete="current-password" required /></label>
        <button className="primary wide" disabled={busy}>{busy ? "正在验证…" : "进入 LabFlow"}<span>→</span></button>
        <div className="demo-divider"><span>快速体验角色</span></div>
        <div className="demo-grid">
          <button type="button" onClick={() => onDemo("student", "student123")}><b>学生</b><small>提交预约与报修</small></button>
          <button type="button" onClick={() => onDemo("teacher", "teacher123")}><b>教师</b><small>审批与设备管理</small></button>
          <button type="button" onClick={() => onDemo("technician", "tech123")}><b>维修员</b><small>处理故障工单</small></button>
          <button type="button" onClick={() => onDemo("admin", "admin123")}><b>管理员</b><small>全局管理与审计</small></button>
        </div>
      </form>
    </section>
    <Toast toast={toast} />
  </main>;
}
