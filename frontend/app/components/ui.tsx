import type { ReactNode } from "react";
import { statusLabels } from "../lib/labels";
import type { ToastState } from "../lib/types";

export function Brand({ light = false, mobile = false }: { light?: boolean; mobile?: boolean }) {
  return <div className={`${mobile ? "mobile-brand " : ""}brand ${light ? "light" : ""}`}>
    <span className="brand-mark">L·F</span>
    <div><b>LABFLOW</b><small>实验室设备运行台</small></div>
  </div>;
}

export function StatusBadge({ value }: { value: string }) {
  return <span className={`status status-${value.toLowerCase()}`}>{statusLabels[value] ?? value}</span>;
}

export function Modal({ title, subtitle, onClose, children }: { title: string; subtitle: string; onClose: () => void; children: ReactNode }) {
  return <div className="modal-backdrop" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
    <section className="modal" role="dialog" aria-modal="true" aria-label={title}>
      <header>
        <div><span>LABFLOW · ACTION</span><h2>{title}</h2><p>{subtitle}</p></div>
        <button className="icon-button" onClick={onClose} aria-label="关闭">×</button>
      </header>
      {children}
    </section>
  </div>;
}

export function Empty({ text }: { text: string }) {
  return <div className="empty"><span>◇</span><p>{text}</p></div>;
}

export function Toast({ toast }: { toast: ToastState }) {
  if (!toast) return null;
  return <div className={`toast ${toast.error ? "error" : ""}`}>
    <span>{toast.error ? "!" : "✓"}</span>{toast.message}
  </div>;
}

