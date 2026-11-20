package com.nekitvp.marathonbot.gamification.service;

import com.nekitvp.marathonbot.gamification.support.PostgresTestContainerConfig;
import com.nekitvp.marathonbot.gamification.rule.RewardCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainerConfig.class, RewardCatalog.class, HistoryMutationRecalculationListener.class})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class HistoryMutationRecalculationListenerIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean private GamificationService gamificationService;

    private Long marathonId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM marathon");
        marathonId = jdbc.queryForObject(
                "INSERT INTO marathon(name,gamification_enabled,gamification_rule_version) "
                        + "VALUES ('M',true,1) RETURNING id", Long.class);
        reset(gamificationService);
    }

    @Test
    void committedMutationRunsAfterCommitInItsOwnListenerTransaction() {
        HistoryMutationRecalculationEvent event = HistoryMutationRecalculationEvent.history(
                marathonId, 101L, LocalDate.of(2026, 8, 3), false);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> publisher.publishEvent(event));

        verify(gamificationService).recalculateSafely(any(GamificationService.RecalculationRequest.class));
    }

    @Test
    void rolledBackMutationDoesNotRunRecalculation() {
        HistoryMutationRecalculationEvent event = HistoryMutationRecalculationEvent.history(
                marathonId, 101L, LocalDate.of(2026, 8, 3), false);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publisher.publishEvent(event);
            status.setRollbackOnly();
        });

        verify(gamificationService, never()).recalculateSafely(any());
    }
}
