package com.equity.store;

import com.equity.domain.user.UserId;
import com.equity.provisioning.IpAllocation;
import com.equity.provisioning.IpAllocationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** A per-user address allocation, on disk. See {@link IpAllocation} for what the fields mean. */
@Entity
@Table(name = "ip_allocation", indexes = {
        @Index(name = "idx_ip_alloc_user", columnList = "trading_user_id"),
        @Index(name = "idx_ip_alloc_status", columnList = "status")
})
public class IpAllocationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_user_id", nullable = false, length = 36)
    private String tradingUserId;

    @Column(name = "private_ip", length = 45)         private String privateIp;
    @Column(name = "public_ip", length = 45)          private String publicIp;
    @Column(name = "eni_id", length = 64)             private String eniId;
    @Column(name = "instance_id", length = 64)        private String instanceId;
    @Column(name = "eip_allocation_id", length = 64)  private String eipAllocationId;
    @Column(name = "eip_association_id", length = 64) private String eipAssociationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private IpAllocationStatus status;

    @Column(name = "whitelisted_with_broker", nullable = false)
    private boolean whitelistedWithBroker;

    @Column(name = "last_error", length = 1024)       private String lastError;
    @Column(name = "created_at", nullable = false)    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)    private Instant updatedAt;
    @Column(name = "updated_by", length = 128)        private String updatedBy;

    protected IpAllocationEntity() {}

    static IpAllocationEntity from(IpAllocation a) {
        IpAllocationEntity e = new IpAllocationEntity();
        e.id = a.id();
        e.tradingUserId = a.userId().toString();
        e.createdAt = a.createdAt();
        e.apply(a);
        return e;
    }

    void apply(IpAllocation a) {
        this.privateIp = a.privateIp();
        this.publicIp = a.publicIp();
        this.eniId = a.eniId();
        this.instanceId = a.instanceId();
        this.eipAllocationId = a.eipAllocationId();
        this.eipAssociationId = a.eipAssociationId();
        this.status = a.status();
        this.whitelistedWithBroker = a.whitelistedWithBroker();
        this.lastError = a.lastError();
        this.updatedAt = a.updatedAt();
        this.updatedBy = a.updatedBy();
    }

    IpAllocation toAllocation() {
        return new IpAllocation(id, UserId.of(tradingUserId), privateIp, publicIp, eniId, instanceId,
                eipAllocationId, eipAssociationId, status, whitelistedWithBroker, lastError,
                createdAt, updatedAt, updatedBy);
    }

    public Long getId() { return id; }
}
