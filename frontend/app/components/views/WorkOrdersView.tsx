import { useState } from "react";
import { formatDate } from "../../lib/labels";
import type { PageData, Technician, UserProfile, WorkOrder } from "../../lib/types";
import { Empty, StatusBadge } from "../ui";

export function WorkOrdersView({ user, workorders, technicians, filter, canAssign, canClaim, canProcess, onFilterChange, onLoad, onCreate, onTransition, onClaim }: {
  user: UserProfile;
  workorders: PageData<WorkOrder> | null;
  technicians: Technician[];
  filter: string;
  canAssign: boolean;
  canClaim: boolean;
  canProcess: boolean;
  onFilterChange: (filter: string) => void;
  onLoad: () => void;
  onCreate: () => void;
  onTransition: (item: WorkOrder, targetStatus: string, assigneeId?: number) => void;
  onClaim: (item: WorkOrder) => void;
}) {
  const [selectedAssignees, setSelectedAssignees] = useState<Record<number, string>>({});
  const technicianName = (id: number | null) => technicians.find((item) => item.id === id)?.displayName
    || (id ? `维修员 #${id}` : "待分派");
  const isOwn = (item: WorkOrder) => item.assigneeId === user.id;
  const canActOn = (item: WorkOrder) => canAssign || (canProcess && isOwn(item));

  return <div className="view-stack">
    <section className="toolbar">
      <select value={filter} onChange={(e) => onFilterChange(e.target.value)}>
        <option value="">全部状态</option>
        {(canAssign || canClaim) && <option value="SUBMITTED">待派单</option>}
        <option value="ASSIGNED">已派单</option>
        <option value="IN_PROGRESS">处理中</option>
        <option value="RESOLVED">已解决</option>
        <option value="CLOSED">已关闭</option>
      </select>
      <button className="secondary" onClick={onLoad}>筛选</button>
      <div className="spacer"></div>
      <button className="primary" onClick={onCreate}>＋ 故障报修</button>
    </section>
    <section className="panel table-panel">
      <header>
        <div>
          <span>FAULT WORK ORDERS</span>
          <h3>{canAssign ? "工单队列" : "维修工单"}</h3>
        </div>
        <small>
          {canAssign
            ? "管理员可派给任意维修员；维修员可主动接单"
            : "可接未分配工单，并处理分配给你的工单"}
          · 共 {workorders?.totalElements ?? 0} 条
        </small>
      </header>
      <div className="table-scroll"><table>
        <thead><tr><th>故障描述</th><th>优先级</th><th>设备 ID</th><th>处理人员</th><th>状态</th><th>更新时间</th><th>操作</th></tr></thead>
        <tbody>{workorders?.content.map((item) => {
          const selected = selectedAssignees[item.id] || String(technicians[0]?.id || "");
          return <tr key={item.id}>
            <td><div className="entity"><span className="warn">!</span><div><b>{item.title}</b><small>{item.reporterName} · {item.description}</small></div></div></td>
            <td><StatusBadge value={item.priority} /></td>
            <td className="mono">EQ-{item.equipmentId}</td>
            <td>{technicianName(item.assigneeId)}</td>
            <td><StatusBadge value={item.status} /></td>
            <td>{formatDate(item.updatedAt)}</td>
            <td><div className="row-actions">
              {canAssign && item.status === "SUBMITTED" && (
                <div className="assign-control">
                  <select aria-label={`为工单 ${item.id} 选择维修员`} value={selected} onChange={(e) => setSelectedAssignees({ ...selectedAssignees, [item.id]: e.target.value })}>
                    {technicians.map((technician) => <option key={technician.id} value={technician.id}>{technician.displayName}</option>)}
                  </select>
                  <button disabled={!selected} onClick={() => onTransition(item, "ASSIGNED", Number(selected))}>派单</button>
                </div>
              )}
              {canClaim && item.status === "SUBMITTED" && (
                <button className="primary" onClick={() => onClaim(item)}>接单</button>
              )}
              {canAssign && (item.status === "SUBMITTED" || item.status === "ASSIGNED") && (
                <button className="danger-link" onClick={() => onTransition(item, "CANCELLED")}>取消</button>
              )}
              {canActOn(item) && item.status === "ASSIGNED" && (
                <button onClick={() => onTransition(item, "IN_PROGRESS")}>开始处理</button>
              )}
              {canActOn(item) && item.status === "IN_PROGRESS" && (
                <button onClick={() => onTransition(item, "RESOLVED")}>标记解决</button>
              )}
              {canActOn(item) && item.status === "RESOLVED" && (
                <>
                  <button onClick={() => onTransition(item, "CLOSED")}>关闭</button>
                  <button onClick={() => onTransition(item, "IN_PROGRESS")}>重新打开</button>
                </>
              )}
            </div></td>
          </tr>;
        })}</tbody>
      </table>{!workorders?.content.length && <Empty text={canAssign ? "没有符合条件的故障工单" : "暂无可接或分配给你的工单"} />}</div>
    </section>
  </div>;
}
