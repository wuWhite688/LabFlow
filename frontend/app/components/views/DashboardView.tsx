import { formatDate } from "../../lib/labels";
import { dashboardCopy } from "../../lib/roles";
import type { ModalKind, PageData, Reservation, Stats, UserProfile, View, WorkOrder } from "../../lib/types";
import { Empty, StatusBadge } from "../ui";

export function DashboardView({
  user,
  stats,
  reservations,
  workorders,
  onViewChange,
  onOpenCreate,
}: {
  user: UserProfile;
  stats: Stats | null;
  reservations: PageData<Reservation> | null;
  workorders: PageData<WorkOrder> | null;
  onViewChange: (view: View) => void;
  onOpenCreate: (kind: ModalKind) => void;
}) {
  const health = stats?.equipmentTotal
    ? Math.round(((stats.equipmentTotal - stats.equipmentMaintenance) / stats.equipmentTotal) * 100)
    : 100;
  const copy = dashboardCopy(user.role, user.displayName);
  const reservationList = reservations?.content ?? [];
  const workorderList = workorders?.content ?? [];

  const myPending = reservationList.filter((item) => item.status === "PENDING");
  const myActiveReservations = reservationList.filter((item) =>
    item.status === "PENDING" || item.status === "APPROVED");
  const pendingApprovals = reservationList.filter((item) => item.status === "PENDING");
  const upcomingSchedule = [...reservationList]
    .filter((item) => item.status === "PENDING" || item.status === "APPROVED")
    .sort((a, b) => new Date(a.startTime).getTime() - new Date(b.startTime).getTime())
    .slice(0, 5);
  const claimPool = workorderList.filter((item) => item.status === "SUBMITTED");
  const myInProgress = workorderList.filter((item) =>
    item.status === "ASSIGNED" || item.status === "IN_PROGRESS" || item.status === "RESOLVED");
  const urgentOrders = workorderList.filter((item) =>
    item.priority === "URGENT" || item.priority === "HIGH");

  return <div className="view-stack" data-dashboard-role={user.role}>
    <section className="welcome">
      <div>
        <span>{copy.kicker}</span>
        <h2>{copy.title}</h2>
        <p>{copy.subtitle}</p>
        <small className="role-hint">{copy.hint}</small>
      </div>
      <div className="date-card">
        <small>值班日期</small>
        <strong>{new Intl.DateTimeFormat("zh-CN", { month: "long", day: "numeric", weekday: "short" }).format(new Date())}</strong>
      </div>
    </section>

    <section className="stat-grid" aria-label="角色指标">
      {user.role === "STUDENT" && <>
        <article><span className="stat-icon accent">01</span><div><small>我的待审批</small><strong>{myPending.length}</strong><p>跟踪审批进度</p></div></article>
        <article><span className="stat-icon amber">02</span><div><small>进行中预约</small><strong>{myActiveReservations.length}</strong><p>含待批与已批准</p></div></article>
        <article><span className="stat-icon coral">03</span><div><small>我的报修</small><strong>{workorders?.totalElements ?? workorderList.length}</strong><p>故障进度可查</p></div></article>
        <article><span className="stat-icon graphite">04</span><div><small>可预约设备</small><strong>{stats?.equipmentAvailable ?? 0}</strong><p>共 {stats?.equipmentTotal ?? 0} 台在册</p></div></article>
      </>}
      {user.role === "TEACHER" && <>
        <article><span className="stat-icon amber">01</span><div><small>待审批预约</small><strong>{stats?.reservationsPending ?? pendingApprovals.length}</strong><p>优先处理申请</p></div></article>
        <article><span className="stat-icon accent">02</span><div><small>今日相关排期</small><strong>{stats?.reservationsToday ?? 0}</strong><p>已排期 {stats?.reservationsApproved ?? 0} 条</p></div></article>
        <article><span className="stat-icon graphite">03</span><div><small>可预约设备</small><strong>{stats?.equipmentAvailable ?? 0}</strong><p>维护中 {stats?.equipmentMaintenance ?? 0} 台</p></div></article>
        <article><span className="stat-icon coral">04</span><div><small>故障动态</small><strong>{stats?.workOrdersOpen ?? 0}</strong><p>紧急 {stats?.workOrdersUrgent ?? 0} 条</p></div></article>
      </>}
      {user.role === "TECHNICIAN" && <>
        <article><span className="stat-icon coral">01</span><div><small>待接单池</small><strong>{claimPool.length}</strong><p>可主动接单</p></div></article>
        <article><span className="stat-icon amber">02</span><div><small>我的处理中</small><strong>{myInProgress.length}</strong><p>派给自己的在办单</p></div></article>
        <article><span className="stat-icon graphite">03</span><div><small>维护中设备</small><strong>{stats?.equipmentMaintenance ?? 0}</strong><p>健康度 {health}%</p></div></article>
        <article><span className="stat-icon accent">04</span><div><small>紧急/高优</small><strong>{urgentOrders.length || (stats?.workOrdersUrgent ?? 0)}</strong><p>优先恢复可用</p></div></article>
      </>}
      {user.role === "ADMIN" && <>
        <article><span className="stat-icon accent">01</span><div><small>在册设备</small><strong>{stats?.equipmentTotal ?? 0}</strong><p>可预约 {stats?.equipmentAvailable ?? 0}</p></div></article>
        <article><span className="stat-icon amber">02</span><div><small>待审批</small><strong>{stats?.reservationsPending ?? 0}</strong><p>已排期 {stats?.reservationsApproved ?? 0}</p></div></article>
        <article><span className="stat-icon coral">03</span><div><small>开放工单</small><strong>{stats?.workOrdersOpen ?? 0}</strong><p>紧急 {stats?.workOrdersUrgent ?? 0}</p></div></article>
        <article><span className="stat-icon graphite">04</span><div><small>设备健康度</small><strong>{health}%</strong><p>维护中 {stats?.equipmentMaintenance ?? 0} · 用户 {stats?.usersTotal ?? 0}</p></div></article>
      </>}
    </section>

    <section className="quick-actions" aria-label="快捷操作">
      {user.role === "STUDENT" && <>
        <button type="button" className="quick-card" onClick={() => onOpenCreate("reservation")}><span>◷</span><div><b>快速预约</b><small>选择设备与时段提交申请</small></div></button>
        <button type="button" className="quick-card" onClick={() => onOpenCreate("workorder")}><span>◇</span><div><b>故障报修</b><small>设备异常时立即上报</small></div></button>
        <button type="button" className="quick-card" onClick={() => onViewChange("reservations")}><span>⌂</span><div><b>我的预约</b><small>查看进度与取消</small></div></button>
      </>}
      {user.role === "TEACHER" && <>
        <button type="button" className="quick-card" onClick={() => onViewChange("reservations")}><span>◷</span><div><b>去审批</b><small>处理待审批预约队列</small></div></button>
        <button type="button" className="quick-card" onClick={() => onViewChange("equipment")}><span>▦</span><div><b>设备管理</b><small>建档、筛选与退役</small></div></button>
        <button type="button" className="quick-card" onClick={() => onOpenCreate("equipment")}><span>＋</span><div><b>新建设备</b><small>录入实验室资产</small></div></button>
      </>}
      {user.role === "TECHNICIAN" && <>
        <button type="button" className="quick-card" onClick={() => onViewChange("workorders")}><span>◇</span><div><b>待接单池</b><small>认领未分配故障单</small></div></button>
        <button type="button" className="quick-card" onClick={() => onViewChange("workorders")}><span>⚙</span><div><b>我的处理中</b><small>开始维修与闭环</small></div></button>
        <button type="button" className="quick-card" onClick={() => onViewChange("equipment")}><span>▦</span><div><b>维修状态</b><small>查看维护中设备</small></div></button>
      </>}
      {user.role === "ADMIN" && <>
        <button type="button" className="quick-card" onClick={() => onViewChange("workorders")}><span>◇</span><div><b>派单入口</b><small>分配维修员处理故障</small></div></button>
        <button type="button" className="quick-card" onClick={() => onViewChange("workorders")}><span>!</span><div><b>异常情况</b><small>开放工单与紧急故障</small></div></button>
        <button type="button" className="quick-card" onClick={() => onViewChange("audit")}><span>≣</span><div><b>审计日志</b><small>关键操作留痕查询</small></div></button>
      </>}
    </section>

    <section className="dashboard-grid">
      {user.role === "STUDENT" && <>
        <article className="panel">
          <header><div><span>MY RESERVATIONS</span><h3>我的预约</h3></div><button type="button" onClick={() => onViewChange("reservations")}>查看全部 →</button></header>
          <div className="compact-list">
            {reservationList.slice(0, 5).map((item) => <div key={item.id}><span className="round-icon">◷</span><div><b>{item.purpose}</b><small>{formatDate(item.startTime)} · 至 {formatDate(item.endTime)}</small></div><StatusBadge value={item.status} /></div>)}
            {!reservationList.length && <Empty text="你还没有预约，点击「快速预约」发起第一条" />}
          </div>
        </article>
        <article className="panel">
          <header><div><span>MY REPORTS</span><h3>我的报修</h3></div><button type="button" onClick={() => onViewChange("workorders")}>查看全部 →</button></header>
          <div className="compact-list">
            {workorderList.slice(0, 5).map((item) => <div key={item.id}><span className="round-icon warn">!</span><div><b>{item.title}</b><small>{formatDate(item.createdAt)} · {item.priority}</small></div><StatusBadge value={item.status} /></div>)}
            {!workorderList.length && <Empty text="暂无报修记录，设备异常时可立即上报" />}
          </div>
        </article>
      </>}

      {user.role === "TEACHER" && <>
        <article className="panel">
          <header><div><span>PENDING APPROVALS</span><h3>待审批预约</h3></div><button type="button" onClick={() => onViewChange("reservations")}>去审批 →</button></header>
          <div className="compact-list">
            {pendingApprovals.slice(0, 5).map((item) => <div key={item.id}><span className="round-icon">◷</span><div><b>{item.purpose}</b><small>{item.requesterName} · {formatDate(item.startTime)}</small></div><StatusBadge value={item.status} /></div>)}
            {!pendingApprovals.length && <Empty text="当前没有待审批预约，排期保持畅通" />}
          </div>
        </article>
        <article className="panel">
          <header><div><span>NEAR-TERM SCHEDULE</span><h3>近期排期</h3></div><button type="button" onClick={() => onViewChange("reservations")}>打开排期 →</button></header>
          <div className="compact-list">
            {upcomingSchedule.map((item) => <div key={item.id}><span className="round-icon">▦</span><div><b>{item.purpose}</b><small>{formatDate(item.startTime)} · {item.requesterName}</small></div><StatusBadge value={item.status} /></div>)}
            {!upcomingSchedule.length && <Empty text="近期暂无占用排期" />}
          </div>
        </article>
      </>}

      {user.role === "TECHNICIAN" && <>
        <article className="panel">
          <header><div><span>CLAIM POOL</span><h3>待接单池</h3></div><button type="button" onClick={() => onViewChange("workorders")}>去接单 →</button></header>
          <div className="compact-list">
            {claimPool.slice(0, 5).map((item) => <div key={item.id}><span className="round-icon warn">!</span><div><b>{item.title}</b><small>{item.reporterName} · {item.priority}</small></div><StatusBadge value={item.status} /></div>)}
            {!claimPool.length && <Empty text="待接单池为空，暂无未分配故障" />}
          </div>
        </article>
        <article className="panel">
          <header><div><span>MY ACTIVE JOBS</span><h3>我的处理中工单</h3></div><button type="button" onClick={() => onViewChange("workorders")}>打开工单 →</button></header>
          <div className="compact-list">
            {myInProgress.slice(0, 5).map((item) => <div key={item.id}><span className="round-icon">◇</span><div><b>{item.title}</b><small>更新于 {formatDate(item.updatedAt)}</small></div><StatusBadge value={item.status} /></div>)}
            {!myInProgress.length && <Empty text="你还没有处理中的工单，可从待接单池认领" />}
          </div>
        </article>
      </>}

      {user.role === "ADMIN" && <>
        <article className="panel">
          <header><div><span>GLOBAL OPS</span><h3>全局运营动态</h3></div><button type="button" onClick={() => onViewChange("reservations")}>预约管理 →</button></header>
          <div className="compact-list">
            {reservationList.slice(0, 4).map((item) => <div key={`r-${item.id}`}><span className="round-icon">◷</span><div><b>{item.purpose}</b><small>{item.requesterName} · {formatDate(item.startTime)}</small></div><StatusBadge value={item.status} /></div>)}
            {workorderList.slice(0, 3).map((item) => <div key={`w-${item.id}`}><span className="round-icon warn">!</span><div><b>{item.title}</b><small>{item.reporterName} · {formatDate(item.createdAt)}</small></div><StatusBadge value={item.status} /></div>)}
            {!reservationList.length && !workorderList.length && <Empty text="暂无近期运营动态" />}
          </div>
        </article>
        <article className="panel">
          <header><div><span>EXCEPTIONS</span><h3>异常与审计入口</h3></div><button type="button" onClick={() => onViewChange("audit")}>打开审计 →</button></header>
          <div className="compact-list">
            <div><span className="round-icon warn">!</span><div><b>开放工单 {stats?.workOrdersOpen ?? 0}</b><small>紧急 {stats?.workOrdersUrgent ?? 0} · 需关注派单进度</small></div><button type="button" onClick={() => onViewChange("workorders")}>派单</button></div>
            <div><span className="round-icon">▦</span><div><b>维护中设备 {stats?.equipmentMaintenance ?? 0}</b><small>健康度 {health}%</small></div><button type="button" onClick={() => onViewChange("equipment")}>查看</button></div>
            <div><span className="round-icon">≣</span><div><b>操作审计</b><small>追溯关键业务动作</small></div><button type="button" onClick={() => onViewChange("audit")}>进入</button></div>
          </div>
        </article>
      </>}
    </section>
  </div>;
}
