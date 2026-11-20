package com.nekitvp.marathonbot.service;

import com.nekitvp.marathonbot.gamification.service.HistoryMutationRecalculationEvent;
import com.nekitvp.marathonbot.model.GoalEntity;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.repository.GoalRepository;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.util.MarkdownUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.nekitvp.marathonbot.util.DateTimeUtil.isRestDay;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoalService {

    private final GoalRepository goalRepository;
    private final MarathonRepository marathonRepository;

    private final UserService userService;
    private final TemplatePhraseService templatePhraseService;
    private final LetterSender letterSender;
    private final ApplicationEventPublisher eventPublisher;

    @Lazy
    @Autowired
    private MarathonService marathonService;

    /**
     * Достаем актуальные цели у пользователя
     */
    @Transactional(readOnly = true)
    public List<GoalEntity> getGoalByUser(Long chatId) {
        var user = userService.getUser(chatId);
        var marathonId = user.getMarathonId();
        return goalRepository.findAllByUserIdAndMarathonIdOrderByPosition(chatId, marathonId);
    }

    @Transactional
    public void sendEveningQuestion() {
        LocalDate today = LocalDate.now();
        List<GoalEntity> listForSave = new ArrayList<>();
        marathonService.getAllMarathonsForEveningQuestion()
                .forEach(marathon -> {

                    if (isRestDay(marathon, today)) {
                        log.info("Skip Question, rest day in marathon {}", marathon.getName());
                        return;
                    }

                    GoalEntity goal = goalRepository.findGoalForEveningQuestion(marathon.getId());
                    if (goal == null) return;

                    var user = userService.getUser(goal.getUserId());

                    var template = templatePhraseService.getRandomEveningQuestion();
                    var goalName = MarkdownUtil.escape(goal.getName());
                    var userName = MarkdownUtil.escape(user.getEffectiveName());

                    letterSender.publish(marathon.getGroupId(), String.format(template, userName, goalName));

                    goal.setAskedCount(goal.getAskedCount() + 1);
                    listForSave.add(goal);
                });
        goalRepository.saveAll(listForSave);
    }

    /**
     * Добавить новую цель участнику марафона
     */
    @Transactional
    public GoalEntity addGoal(Long userId, Long marathonId, String name) {
        List<GoalEntity> existing = goalRepository.findAllByUserIdAndMarathonIdOrderByPosition(userId, marathonId);
        int nextPos = existing.stream()
                .mapToInt(GoalEntity::getPosition)
                .filter(p -> p != 0)
                .max()
                .orElse(0) + 1;
        if (nextPos > 10) {
            throw new IllegalStateException("Достигнут максимум целей (10)");
        }
        MarathonEntity marathon = marathonRepository.findById(marathonId)
                .orElseThrow(() -> new RuntimeException("Марафон не найден: " + marathonId));
        GoalEntity goal = GoalEntity.builder()
                .userId(userId)
                .name(name)
                .position(nextPos)
                .marathon(marathon)
                .build();
        return goalRepository.save(goal);
    }

    /**
     * Удалить цель по id и пересчитать позиции оставшихся
     */
    @Transactional
    public void deleteGoal(Long goalId) {
        GoalEntity goal = goalRepository.findByIdAndArchivedAtIsNull(goalId)
                .orElseThrow(() -> new RuntimeException("Цель не найдена: " + goalId));
        Long userId = goal.getUserId();
        Long marathonId = goal.getMarathon().getId();
        HistoryMutationRecalculationEvent.GoalSnapshot snapshot =
                new HistoryMutationRecalculationEvent.GoalSnapshot(goal.getId(), goal.getName());
        LocalDateTime archivedAt = LocalDateTime.now();
        goal.setArchivedAt(archivedAt);
        goalRepository.save(goal);

        List<GoalEntity> remaining = goalRepository
                .findAllByUserIdAndMarathonIdOrderByPosition(userId, marathonId)
                .stream()
                .filter(g -> g.getPosition() != 0)
                .sorted(Comparator.comparingInt(GoalEntity::getPosition))
                .toList();

        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setPosition(i + 1);
        }
        goalRepository.saveAll(remaining);
        eventPublisher.publishEvent(new HistoryMutationRecalculationEvent(
                marathonId, userId, archivedAt.toLocalDate(), false, snapshot));
    }

    /**
     * Переименовать цель
     */
    @Transactional
    public void updateGoalName(Long goalId, String name) {
        GoalEntity goal = goalRepository.findById(goalId)
                .orElseThrow(() -> new RuntimeException("Цель не найдена: " + goalId));
        goal.setName(name);
        goalRepository.save(goal);
    }

    @Transactional
    public void createReportGoalIfNotExists(UserEntity user, MarathonEntity marathon) {
        boolean exists = goalRepository.existsByUserIdAndMarathonIdAndPosition(
                user.getTelegramId(), marathon.getId(), 0);
        if (!exists) {
            GoalEntity reportGoal = GoalEntity.builder()
                    .userId(user.getTelegramId())
                    .name("Отчет")
                    .position(0)
                    .marathon(marathon)
                    .build();
            goalRepository.save(reportGoal);
        }
    }
}
