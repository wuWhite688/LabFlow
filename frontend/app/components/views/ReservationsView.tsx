import { formatDate } from "../../lib/labels";
import type { Equipment, PageData, Reservation, UserProfile } from "../../lib/types";
import { Empty, StatusBadge } from "../ui";

const scheduleStatuses = new Set(["PENDING", "APPROVED"]);

function startOfDay(date: Date) {
  const value = new Date(date);
  value.setHours(0, 0, 0, 0);
  return value;
}

function dayKey(date: Date) {
  return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
}

function timeLabel(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(value));
}

function ReservationSchedule({ reservations, equipment }: { reservations: Reservation[]; equipment: Equipment[] }) {
  const today = startOfDay(new Date());
  const days = Array.from({ length: 7 }, (_, index) => {
    const date = new Date(today);
    date.setDate(today.getDate() + index);
    return date;
  });
  const equipmentNames = new Map(equipment.map((item) => [item.id, item.name]));

  return <section className="panel schedule-panel">
    <header><div><span>7-DAY EQUIPMENT SCHEDULE</span><h3>未来 7 日预约排期</h3></div><small>橙色为已占用，虚线为待审批</small></header>
    <div className="schedule-scroll"><div className="schedule-grid">{days.map((day, index) => {
      const items = reservations.filter((item) => scheduleStatuses.has(item.status) && dayKey(new Date(item.startTime)) === dayKey(day));
      return <article className={`schedule-day ${index === 0 ? "today" : ""}`} key={dayKey(day)}>
        <header><span>{index === 0 ? "今天" : new Intl.DateTimeFormat("zh-CN", { weekday: "short" }).format(day)}</span><b>{new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit" }).format(day)}</b></header>
        <div>{items.map((item) => <div className={`schedule-slot ${item.status === "PENDING" ? "pending" : ""}`} key={item.id}>
          <strong>{timeLabel(item.startTime)}–{timeLabel(item.endTime)}</strong>
          <span>{equipmentNames.get(item.equipmentId) ?? `设备 EQ-${item.equipmentId}`}</span>
          <small>{item.status === "PENDING" ? "待审批" : "已占用"} · {item.requesterName}</small>
        </div>)}{items.length === 0 && <p>暂无排期</p>}</div>
      </article>;
    })}</div></div>
  </section>;
}

export function ReservationsView({ user, reservations, scheduleReservations, equipment, filter, canApprove, onFilterChange, onLoad, onCreate, onDecide, onCancel, onComplete }: {
  user: UserProfile;
  reservations: PageData<Reservation> | null;
  scheduleReservations: PageData<Reservation> | null;
  equipment: PageData<Equipment> | null;
  filter: string;
  canApprove: boolean;
  onFilterChange: (filter: string) => void;
  onLoad: () => void;
  onCreate: () => void;
  onDecide: (id: number, decision: "APPROVED" | "REJECTED") => void;
  onCancel: (id: number) => void;
  onComplete: (id: number) => void;
}) {
  return <div className="view-stack">
    <section className="toolbar"><select value={filter} onChange={(e) => onFilterChange(e.target.value)}><option value="">全部状态</option><option value="PENDING">待审批</option><option value="APPROVED">已批准</option><option value="COMPLETED">已完成</option><option value="CANCELLED">已取消</option><option value="EXPIRED">已过期</option></select><button className="secondary" onClick={onLoad}>筛选</button><div className="spacer"></div><button className="primary" onClick={onCreate}>＋ 发起预约</button></section>
    <ReservationSchedule reservations={scheduleReservations?.content ?? []} equipment={equipment?.content ?? []} />
    <section className="panel table-panel">
      <header><div><span>RESERVATION FLOW</span><h3>预约记录</h3></div><small>共 {reservations?.totalElements ?? 0} 条</small></header>
      <div className="table-scroll"><table><thead><tr><th>用途与申请人</th><th>使用时段</th><th>设备 ID</th><th>状态</th><th>操作</th></tr></thead><tbody>{reservations?.content.map((item) => <tr key={item.id}><td><div className="entity"><span>◷</span><div><b>{item.purpose}</b><small>{item.requesterName} · #{item.id}</small></div></div></td><td><b>{formatDate(item.startTime)}</b><small className="block">至 {formatDate(item.endTime)}</small></td><td className="mono">EQ-{item.equipmentId}</td><td><StatusBadge value={item.status} /></td><td><div className="row-actions">{canApprove && item.status === "PENDING" && <><button onClick={() => onDecide(item.id, "APPROVED")}>批准</button><button className="danger-link" onClick={() => onDecide(item.id, "REJECTED")}>拒绝</button></>}{item.requesterId === user.id && ["PENDING", "APPROVED"].includes(item.status) && <button onClick={() => onCancel(item.id)}>取消</button>}{canApprove && item.status === "APPROVED" && <button onClick={() => onComplete(item.id)}>完成</button>}</div></td></tr>)}</tbody></table>{!reservations?.content.length && <Empty text="没有符合条件的预约" />}</div>
    </section>
  </div>;
}
