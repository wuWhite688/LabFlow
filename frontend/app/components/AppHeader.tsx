import { roleLabels } from "../lib/labels";
import { isViewAllowed, navItemsForRole, workspaceEyebrow, workspaceTitle } from "../lib/roles";
import type { UserProfile, View } from "../lib/types";
import { Brand } from "./ui";

export function AppHeader({ user, view, onViewChange, onLogout }: {
  user: UserProfile;
  view: View;
  onViewChange: (view: View) => void;
  onLogout: () => void;
}) {
  const navItems = navItemsForRole(user.role);
  return <aside className="sidebar" data-role={user.role}>
    <Brand light />
    <nav aria-label={`${roleLabels[user.role]}导航`}>
      {navItems.map((item) =>
        <button
          key={item.id}
          type="button"
          className={view === item.id ? "active" : ""}
          data-nav={item.id}
          onClick={() => onViewChange(item.id)}
        >
          <span>{item.icon}</span>{item.label}
        </button>)}
    </nav>
    <div className="system-note"><span></span><div><b>系统运行正常</b><small>{roleLabels[user.role]}空间 · 数据已同步</small></div></div>
    <div className="profile">
      <div className="avatar">{user.displayName.slice(0, 1)}</div>
      <div><b>{user.displayName}</b><small>{roleLabels[user.role]} · {user.username}</small></div>
      <button type="button" onClick={onLogout} title="退出登录">↪</button>
    </div>
  </aside>;
}

export function WorkspaceHeader({ user, view, onRefresh }: { user: UserProfile; view: View; onRefresh: () => void }) {
  const safeView = isViewAllowed(user.role, view) ? view : "dashboard";
  return <header className="topbar" data-role={user.role}>
    <div>
      <span className="eyebrow">{workspaceEyebrow(user.role)}</span>
      <h1>{workspaceTitle(user.role, safeView)}</h1>
    </div>
    <div className="top-actions">
      <span className="role-chip">{roleLabels[user.role]}</span>
      <button type="button" className="icon-button" onClick={onRefresh} title="刷新">↻</button>
    </div>
  </header>;
}
