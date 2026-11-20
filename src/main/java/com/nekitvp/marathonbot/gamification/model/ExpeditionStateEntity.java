package com.nekitvp.marathonbot.gamification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "expedition_state",
        schema = "public",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_expedition_state_rule",
                columnNames = {"marathon_id", "rule_version"}
        )
)
public class ExpeditionStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "marathon_id", nullable = false)
    private Long marathonId;

    @Column(name = "rule_version", nullable = false)
    private Integer ruleVersion;

    @Column(name = "activated_at", nullable = false)
    private LocalDateTime activatedAt;

    @Column(name = "route_start_date", nullable = false)
    private LocalDate routeStartDate;

    @Column(name = "route_end_date", nullable = false)
    private LocalDate routeEndDate;

    @Builder.Default
    @Column(name = "rest_on_saturday", nullable = false)
    private Boolean restOnSaturday = false;

    @Builder.Default
    @Column(name = "rest_on_sunday", nullable = false)
    private Boolean restOnSunday = false;

    @Column(name = "planned_active_days", nullable = false)
    private Integer plannedActiveDays;

    @Builder.Default
    @Column(name = "distance", nullable = false, precision = 7, scale = 4)
    private BigDecimal distance = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "last_announced_checkpoint", nullable = false)
    private Integer lastAnnouncedCheckpoint = 0;

    @Column(name = "last_reconciled_date")
    private LocalDate lastReconciledDate;

    @Column(name = "last_digest_week", length = 10)
    private String lastDigestWeek;

    @Column(name = "launch_announced_at")
    private LocalDateTime launchAnnouncedAt;

    @Version
    @Builder.Default
    @Column(name = "version", nullable = false)
    private Long version = 0L;
}
