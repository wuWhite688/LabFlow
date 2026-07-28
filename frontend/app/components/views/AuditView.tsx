import { actionLabels, formatDate } from "../../lib/labels";
import type { AuditLog, PageData } from "../../lib/types";
import { Empty, StatusBadge } from "../ui";

export function AuditView({ auditLogs }: { auditLogs: PageData<AuditLog> | null }) {
  return <div className="view-stack">
    <section className="panel table-panel">
      <header><div><span>OPERATION AUDIT</span><h3>操作审计日志</h3></div><small>仅管理员可见 · {auditLogs?.totalElements ?? 0} 条</small></header>
      <div className="timeline">{auditLogs?.content.map((item) => <article key={item.id}><span className="timeline-dot"></span><div><header><b>{actionLabels[item.action] ?? item.action}</b><StatusBadge value={item.actorRole} /></header><p>{item.details}</p><small>{item.actorUsername} · {item.targetType} #{item.targetId ?? "—"} · {formatDate(item.createdAt)}</small></div></article>)}{!auditLogs?.content.length && <Empty text="暂无审计记录" />}</div>
    </section>
  </div>;
}

