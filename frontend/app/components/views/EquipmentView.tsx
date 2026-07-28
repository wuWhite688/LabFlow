import type { Equipment, PageData } from "../../lib/types";
import { Empty, StatusBadge } from "../ui";

export function EquipmentView({ equipment, filter, canManage, onFilterChange, onLoad, onCreate, onRetire, onRestore }: {
  equipment: PageData<Equipment> | null;
  filter: { keyword: string; status: string };
  canManage: boolean;
  onFilterChange: (filter: { keyword: string; status: string }) => void;
  onLoad: () => void;
  onCreate: () => void;
  onRetire?: (item: Equipment) => void;
  onRestore?: (item: Equipment) => void;
}) {
  return <div className="view-stack">
    <section className="toolbar">
      <div className="search"><span>⌕</span><input placeholder="搜索编号、名称或位置" value={filter.keyword} onChange={(e) => onFilterChange({ ...filter, keyword: e.target.value })} onKeyDown={(e) => e.key === "Enter" && onLoad()} /></div>
      <select value={filter.status} onChange={(e) => onFilterChange({ ...filter, status: e.target.value })}>
        <option value="">全部状态</option>
        <option value="AVAILABLE">可预约</option>
        <option value="IN_USE">使用中</option>
        <option value="MAINTENANCE">维护中</option>
        <option value="RETIRED">已退役</option>
      </select>
      <button className="secondary" onClick={onLoad}>筛选</button>
      {canManage && <button className="primary" onClick={onCreate}>＋ 新建设备</button>}
    </section>
    <section className="panel table-panel">
      <header><div><span>EQUIPMENT DIRECTORY</span><h3>设备目录</h3></div><small>共 {equipment?.totalElements ?? 0} 台设备</small></header>
      <div className="equipment-card-grid">{equipment?.content.map((item) => <article className="equipment-card" key={item.id}>
        <header><span className="equipment-code">{item.code}</span><StatusBadge value={item.status} /></header>
        <div className="equipment-identity"><span>▦</span><div><h4>{item.name}</h4><p>{item.manufacturer || "厂商待补充"} · {item.model || "型号待补充"}</p></div></div>
        <p className="equipment-description">{item.description || "暂无设备用途说明"}</p>
        <dl><div><dt>分类</dt><dd>{item.category}</dd></div><div><dt>位置</dt><dd>{item.location}</dd></div><div><dt>负责人</dt><dd>{item.responsiblePerson || "待分配"}</dd></div><div><dt>购置日期</dt><dd>{item.purchaseDate || "待补充"}</dd></div></dl>
        {canManage && <div className="row-actions equipment-actions">
          {item.status !== "RETIRED" && item.status === "AVAILABLE" && onRetire && <button className="danger-link" onClick={() => onRetire(item)}>退役</button>}
          {item.status === "RETIRED" && onRestore && <button onClick={() => onRestore(item)}>恢复可用</button>}
        </div>}
      </article>)}{!equipment?.content.length && <Empty text="没有符合条件的设备" />}</div>
    </section>
  </div>;
}
