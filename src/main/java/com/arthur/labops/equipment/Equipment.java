package com.arthur.labops.equipment;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "equipment")
public class Equipment {

    /** Ceiling so the billing multiplication cannot overflow: 1,000,000.00 per hour. */
    public static final long MAX_HOURLY_PRICE_CENTS = 100_000_000L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(nullable = false, length = 120)
    private String location;

    @Column(length = 100)
    private String manufacturer;

    @Column(length = 100)
    private String model;

    @Column(name = "responsible_person", length = 80)
    private String responsiblePerson;

    @Column(name = "purchase_date")
    private LocalDate purchaseDate;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EquipmentStatus status;

    /**
     * Reservation price per hour, in cents. Zero means free, and a free
     * reservation never enters the payment flow at all.
     */
    @Column(name = "hourly_price_cents", nullable = false)
    private long hourlyPriceCents;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Equipment() {
    }

    public Equipment(String code, String name, String category, String location) {
        this(code, name, category, location, null, null, null, null, null);
    }

    public Equipment(String code, String name, String category, String location,
                     String manufacturer, String model, String responsiblePerson,
                     LocalDate purchaseDate, String description) {
        this.code = code;
        this.name = name;
        this.category = category;
        this.location = location;
        this.manufacturer = manufacturer;
        this.model = model;
        this.responsiblePerson = responsiblePerson;
        this.purchaseDate = purchaseDate;
        this.description = description;
        this.status = EquipmentStatus.AVAILABLE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public String getLocation() { return location; }
    public String getManufacturer() { return manufacturer; }
    public String getModel() { return model; }
    public String getResponsiblePerson() { return responsiblePerson; }
    public LocalDate getPurchaseDate() { return purchaseDate; }
    public String getDescription() { return description; }
    public EquipmentStatus getStatus() { return status; }
    public long getHourlyPriceCents() { return hourlyPriceCents; }

    public void setHourlyPriceCents(long hourlyPriceCents) {
        if (hourlyPriceCents < 0) {
            throw new IllegalArgumentException("设备价格不能为负数");
        }
        if (hourlyPriceCents > MAX_HOURLY_PRICE_CENTS) {
            throw new IllegalArgumentException("设备价格超出上限");
        }
        this.hourlyPriceCents = hourlyPriceCents;
        this.updatedAt = Instant.now();
    }

    public void markMaintenance() {
        if (status == EquipmentStatus.RETIRED) {
            throw new IllegalStateException("已退役设备不能进入维护状态");
        }
        status = EquipmentStatus.MAINTENANCE;
        updatedAt = Instant.now();
    }

    public void markAvailable() {
        if (status != EquipmentStatus.RETIRED) {
            status = EquipmentStatus.AVAILABLE;
            updatedAt = Instant.now();
        }
    }

    public void markInUse() {
        if (status == EquipmentStatus.RETIRED) {
            throw new IllegalStateException("已退役设备不能进入使用中状态");
        }
        if (status == EquipmentStatus.MAINTENANCE) {
            throw new IllegalStateException("维护中设备不能进入使用中状态");
        }
        status = EquipmentStatus.IN_USE;
        updatedAt = Instant.now();
    }

    /** 由状态同步服务调用；已退役设备不会被覆盖。 */
    public void forceStatus(EquipmentStatus next) {
        if (status == EquipmentStatus.RETIRED) {
            return;
        }
        if (this.status != next) {
            this.status = next;
            this.updatedAt = Instant.now();
        }
    }

    public void updateProfile(String name, String category, String location,
                              String manufacturer, String model, String responsiblePerson,
                              LocalDate purchaseDate, String description) {
        if (status == EquipmentStatus.RETIRED) {
            throw new IllegalStateException("已退役设备不能修改资料");
        }
        this.name = name;
        this.category = category;
        this.location = location;
        this.manufacturer = manufacturer;
        this.model = model;
        this.responsiblePerson = responsiblePerson;
        this.purchaseDate = purchaseDate;
        this.description = description;
        this.updatedAt = Instant.now();
    }

    public void retire() {
        this.status = EquipmentStatus.RETIRED;
        this.updatedAt = Instant.now();
    }

    public void restoreFromRetired() {
        if (status != EquipmentStatus.RETIRED) {
            throw new IllegalStateException("仅已退役设备可恢复");
        }
        this.status = EquipmentStatus.AVAILABLE;
        this.updatedAt = Instant.now();
    }
}
