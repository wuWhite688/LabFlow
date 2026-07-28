import type { FormEvent } from "react";
import type { Equipment, EquipmentForm, ModalKind, PageData, ReservationForm, WorkOrderForm } from "../lib/types";
import { Modal } from "./ui";

export function ActionModals({ modal, busy, equipment, equipmentForm, reservationForm, workorderForm, onClose, onEquipmentChange, onReservationChange, onWorkorderChange, onSubmitEquipment, onSubmitReservation, onSubmitWorkorder }: {
  modal: ModalKind | null;
  busy: boolean;
  equipment: PageData<Equipment> | null;
  equipmentForm: EquipmentForm;
  reservationForm: ReservationForm;
  workorderForm: WorkOrderForm;
  onClose: () => void;
  onEquipmentChange: (form: EquipmentForm) => void;
  onReservationChange: (form: ReservationForm) => void;
  onWorkorderChange: (form: WorkOrderForm) => void;
  onSubmitEquipment: (event: FormEvent) => void;
  onSubmitReservation: (event: FormEvent) => void;
  onSubmitWorkorder: (event: FormEvent) => void;
}) {
  if (modal === "equipment") return <Modal title="新建设备" subtitle="录入实验室资产的基础信息" onClose={onClose}>
    <form className="modal-form" onSubmit={onSubmitEquipment}><div className="field-grid">
      <label>设备编号<input value={equipmentForm.code} onChange={(e) => onEquipmentChange({ ...equipmentForm, code: e.target.value })} placeholder="例如 SEM-001" required /></label>
      <label>设备名称<input value={equipmentForm.name} onChange={(e) => onEquipmentChange({ ...equipmentForm, name: e.target.value })} placeholder="扫描电子显微镜" required /></label>
      <label>设备分类<input value={equipmentForm.category} onChange={(e) => onEquipmentChange({ ...equipmentForm, category: e.target.value })} placeholder="显微成像" required /></label>
      <label>所在位置<input value={equipmentForm.location} onChange={(e) => onEquipmentChange({ ...equipmentForm, location: e.target.value })} placeholder="材料楼 A208" required /></label>
      <label>制造厂商<input value={equipmentForm.manufacturer} onChange={(e) => onEquipmentChange({ ...equipmentForm, manufacturer: e.target.value })} placeholder="例如 ZEISS" /></label>
      <label>设备型号<input value={equipmentForm.model} onChange={(e) => onEquipmentChange({ ...equipmentForm, model: e.target.value })} placeholder="例如 GeminiSEM 300" /></label>
      <label>设备负责人<input value={equipmentForm.responsiblePerson} onChange={(e) => onEquipmentChange({ ...equipmentForm, responsiblePerson: e.target.value })} placeholder="例如 周教授" /></label>
      <label>购置日期<input type="date" value={equipmentForm.purchaseDate} onChange={(e) => onEquipmentChange({ ...equipmentForm, purchaseDate: e.target.value })} /></label>
    </div><label>设备用途说明<textarea value={equipmentForm.description} onChange={(e) => onEquipmentChange({ ...equipmentForm, description: e.target.value })} placeholder="说明主要用途、可开展实验或使用注意事项" /></label><footer><button type="button" className="secondary" onClick={onClose}>取消</button><button className="primary" disabled={busy}>保存设备</button></footer></form>
  </Modal>;

  if (modal === "reservation") return <Modal title="发起设备预约" subtitle="提交后将进入教师审批流程" onClose={onClose}>
    <form className="modal-form" onSubmit={onSubmitReservation}>
      <label>预约设备<select value={reservationForm.equipmentId} onChange={(e) => onReservationChange({ ...reservationForm, equipmentId: e.target.value })} required><option value="">请选择可用设备</option>{equipment?.content.filter((item) => item.status === "AVAILABLE").map((item) => <option key={item.id} value={item.id}>{item.name} · {item.code}</option>)}</select></label>
      <label>使用目的<textarea value={reservationForm.purpose} onChange={(e) => onReservationChange({ ...reservationForm, purpose: e.target.value })} placeholder="说明实验内容与设备用途" required /></label>
      <div className="field-grid"><label>开始时间<input type="datetime-local" value={reservationForm.startTime} onChange={(e) => onReservationChange({ ...reservationForm, startTime: e.target.value })} required /></label><label>结束时间<input type="datetime-local" value={reservationForm.endTime} onChange={(e) => onReservationChange({ ...reservationForm, endTime: e.target.value })} required /></label></div>
      <footer><button type="button" className="secondary" onClick={onClose}>取消</button><button className="primary" disabled={busy}>提交预约</button></footer>
    </form>
  </Modal>;

  if (modal === "workorder") return <Modal title="提交故障工单" subtitle="报修后设备将自动进入维护状态" onClose={onClose}>
    <form className="modal-form" onSubmit={onSubmitWorkorder}>
      <div className="field-grid"><label>故障设备<select value={workorderForm.equipmentId} onChange={(e) => onWorkorderChange({ ...workorderForm, equipmentId: e.target.value })} required><option value="">请选择设备</option>{equipment?.content.map((item) => <option key={item.id} value={item.id}>{item.name} · {item.code}</option>)}</select></label><label>优先级<select value={workorderForm.priority} onChange={(e) => onWorkorderChange({ ...workorderForm, priority: e.target.value })}><option value="LOW">低</option><option value="MEDIUM">中</option><option value="HIGH">高</option><option value="URGENT">紧急</option></select></label></div>
      <label>故障标题<input value={workorderForm.title} onChange={(e) => onWorkorderChange({ ...workorderForm, title: e.target.value })} placeholder="简要描述故障现象" required /></label>
      <label>详细描述<textarea value={workorderForm.description} onChange={(e) => onWorkorderChange({ ...workorderForm, description: e.target.value })} placeholder="发生时间、异常表现、已尝试的处理方式…" required /></label>
      <footer><button type="button" className="secondary" onClick={onClose}>取消</button><button className="primary" disabled={busy}>提交工单</button></footer>
    </form>
  </Modal>;

  return null;
}
